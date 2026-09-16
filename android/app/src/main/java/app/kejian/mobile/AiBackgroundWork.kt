package app.kejian.mobile

import android.app.*
import android.app.job.*
import android.content.*
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.*
import android.util.AtomicFile
import android.util.Base64
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File

/** The journal is device-private, excluded from AppData/cloud backups, and scoped to the submitting account. */
data class AiBackgroundTask(val id:String,val owner:String,val kind:String,val requestId:String,val noteId:String,
    val digest:String,val state:String,val createdAt:Long,val attempts:Int,val code:String="",val details:String=""){
    val busy get()=state in setOf("queued","running")||state=="retry"&&attempts<3
}
internal fun aiBackgroundRetrySafe(kind:String,createdAt:Long,now:Long=System.currentTimeMillis()):Boolean=
    now>=createdAt-300_000&&now-createdAt<if(kind in setOf("image","command"))23*60*60*1000L else 7*24*60*60*1000L

internal fun aiCloudFailureCode(status:Int):String=when(status){
    400,422->"INPUT_REQUIRED"
    401,403->"AUTH_REQUIRED"
    402->"QUOTA_REQUIRED"
    404,410->"LOCAL_CACHE_EXPIRED"
    409->"REQUEST_CONFLICT"
    413->"INPUT_TOO_LARGE"
    429->"RATE_LIMITED"
    else->if(status>=500)"SERVER_UNAVAILABLE"else"REQUEST_REJECTED"
}
internal fun aiFailureIsTerminal(code:String)=code in setOf("INPUT_REQUIRED","AUTH_REQUIRED","QUOTA_REQUIRED","REQUEST_CONFLICT","INPUT_TOO_LARGE","REQUEST_REJECTED","failed","cancelled","applied","INSIGHTS_FAILED","INSIGHTS_EXPIRED","INSIGHTS_CANCELLED","MINDMAP_FAILED","MINDMAP_CANCELLED","SUMMARY_FAILED","MODEL_UNAVAILABLE","NOTE_PROVIDER_FAILED","LOCAL_CACHE_EXPIRED")

object AiBackgroundRuntime {
    var revision by mutableIntStateOf(0);private set
    var active by mutableStateOf<AiBackgroundTask?>(null);internal set
    internal fun changed(){revision++}
}

class AiBackgroundJournal(context:Context,name:String="kejian_ai_background.db"):SQLiteOpenHelper(context.applicationContext,name,null,1){
    companion object {private val diskLock=Any()}
    private val root=File(context.filesDir,"ai-work-${sha256Text(name).take(12)}").apply {mkdirs()}
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE work(id TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT NULL,request_id TEXT NOT NULL,note_id TEXT NOT NULL,digest TEXT NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,attempts INTEGER NOT NULL,code TEXT NOT NULL,details TEXT NOT NULL,UNIQUE(owner,request_id))")
        db.execSQL("CREATE TABLE stops(owner TEXT NOT NULL,request_id TEXT NOT NULL,created_at INTEGER NOT NULL,PRIMARY KEY(owner,request_id))")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
    private fun file(id:String,suffix:String):File {require(id.matches(Regex("[A-Za-z0-9_-]{16,80}")));return File(root,"$id.$suffix")}
    private fun write(id:String,suffix:String,json:JSONObject){
        val bytes=json.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=40*1024*1024)
        val target=AtomicFile(file(id,suffix));val stream=target.startWrite()
        try {stream.write(bytes);target.finishWrite(stream)}catch(error:Exception){target.failWrite(stream);throw error}
    }
    fun input(task:AiBackgroundTask)=JSONObject(AtomicFile(file(task.id,"input")).readFully().toString(Charsets.UTF_8))
    fun result(task:AiBackgroundTask)=JSONObject(AtomicFile(file(task.id,"result")).readFully().toString(Charsets.UTF_8))
    @Synchronized fun tasks(owner:String):List<AiBackgroundTask> = readableDatabase.rawQuery("SELECT * FROM work WHERE owner=? ORDER BY created_at",arrayOf(owner)).use {c->buildList{
        while(c.moveToNext()){
            fun s(k:String)=c.getString(c.getColumnIndexOrThrow(k))
            add(AiBackgroundTask(s("id"),s("owner"),s("kind"),s("request_id"),s("note_id"),s("digest"),s("state"),c.getLong(c.getColumnIndexOrThrow("created_at")),c.getInt(c.getColumnIndexOrThrow("attempts")),s("code"),s("details")))
        }
    }}
    @Synchronized fun hasPendingNote(noteId:String):Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM work WHERE note_id=? AND state NOT IN ('consumed','cancelled','failed','expired') LIMIT 1",arrayOf(noteId)).use {it.moveToFirst()}
    @Synchronized fun forgetNote(noteId:String)=synchronized(diskLock){
        check(!hasPendingNote(noteId))
        val ids=readableDatabase.rawQuery("SELECT id FROM work WHERE note_id=?",arrayOf(noteId)).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        writableDatabase.execSQL("UPDATE work SET state='consumed' WHERE note_id=?",arrayOf(noteId))
        ids.forEach {file(it,"input").delete();file(it,"result").delete()}
        AiBackgroundRuntime.changed()
    }
    @Synchronized fun enqueue(owner:String,kind:String,requestId:String,input:JSONObject,noteId:String="",digest:String=""):AiBackgroundTask=synchronized(diskLock){
        require(owner.isNotBlank()&&kind in setOf("image","command","mindmap","insight","resummary"))
        require(!readableDatabase.rawQuery("SELECT 1 FROM stops WHERE owner=? AND request_id=?",arrayOf(owner,requestId)).use {it.moveToFirst()}){"This task was stopped"}
        val existing=tasks(owner).firstOrNull {it.requestId==requestId}
        if(existing!=null){
            require(existing.kind==kind&&existing.noteId==noteId&&existing.digest==digest)
            if(existing.state=="result")return@synchronized existing
            require(existing.state !in setOf("cancelled","consumed")){"This task already ended"}
            require(aiBackgroundRetrySafe(kind,existing.createdAt)){"The safe retry window has ended"}
            require(this.input(existing).toString()==input.toString()){ "The original task input changed" }
            writableDatabase.execSQL("UPDATE work SET state='queued',attempts=0 WHERE id=? AND owner=?",arrayOf(existing.id,owner));AiBackgroundRuntime.changed();return@synchronized existing.copy(state="queued",attempts=0)
        }
        require(root.listFiles().orEmpty().sumOf {it.length()}+input.toString().toByteArray(Charsets.UTF_8).size<=96L*1024*1024){"Free storage before starting another AI task"}
        require(tasks(owner).count {it.state !in setOf("consumed","cancelled")}<24){"Finish or clear pending AI tasks first"}
        val task=AiBackgroundTask(sha256Text("$owner:$requestId"),owner,kind,requestId,noteId,digest,"queued",System.currentTimeMillis(),0)
        write(task.id,"input",input)
        try {writableDatabase.execSQL("INSERT INTO work VALUES(?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(task.id,owner,kind,requestId,noteId,digest,task.state,task.createdAt,0,"",""))}
        catch(error:Exception){file(task.id,"input").delete();throw error}
        AiBackgroundRuntime.changed();task
    }
    @Synchronized fun setState(task:AiBackgroundTask,state:String,code:String="",details:String=""){
        writableDatabase.execSQL("UPDATE work SET state=?,code=?,details=? WHERE id=? AND owner=? AND state NOT IN ('cancelled','consumed')",arrayOf(state,code,details.take(2000),task.id,task.owner));AiBackgroundRuntime.changed()
    }
    @Synchronized fun begin(task:AiBackgroundTask){
        writableDatabase.execSQL("UPDATE work SET state='running',attempts=attempts+1 WHERE id=? AND owner=? AND state IN ('queued','retry','running')",arrayOf(task.id,task.owner));AiBackgroundRuntime.changed()
    }
    @Synchronized fun waitingForServer(task:AiBackgroundTask,code:String){
        writableDatabase.execSQL("UPDATE work SET state='retry',attempts=MAX(0,attempts-1),code=? WHERE id=? AND owner=? AND state NOT IN ('cancelled','consumed')",arrayOf(code,task.id,task.owner));AiBackgroundRuntime.changed()
    }
    @Synchronized fun complete(task:AiBackgroundTask,response:JSONObject)=synchronized(diskLock){
        if(tasks(task.owner).firstOrNull {it.id==task.id}?.state in setOf("cancelled","consumed"))return@synchronized
        write(task.id,"result",response);setState(task,"result")
        file(task.id,"input").delete()
    }
    @Synchronized fun consume(task:AiBackgroundTask)=synchronized(diskLock){
        writableDatabase.execSQL("UPDATE work SET state='consumed' WHERE id=? AND owner=?",arrayOf(task.id,task.owner))
        file(task.id,"input").delete();file(task.id,"result").delete();AiBackgroundRuntime.changed()
    }
    @Synchronized fun cancel(task:AiBackgroundTask)=synchronized(diskLock){
        writableDatabase.execSQL("UPDATE work SET state='cancelled',code='USER_STOPPED' WHERE id=? AND owner=?",arrayOf(task.id,task.owner))
        file(task.id,"input").delete();file(task.id,"result").delete();AiBackgroundRuntime.changed()
    }
    @Synchronized fun markStopped(owner:String,requestId:String)=synchronized(diskLock){writableDatabase.execSQL("INSERT OR IGNORE INTO stops VALUES(?,?,?)",arrayOf<Any?>(owner,requestId,System.currentTimeMillis()))}
    @Synchronized fun cancelledRemotely(task:AiBackgroundTask){writableDatabase.execSQL("UPDATE work SET code='CANCEL_SENT' WHERE id=? AND owner=? AND state='cancelled'",arrayOf(task.id,task.owner))}
    @Synchronized fun retry(task:AiBackgroundTask){
        require(task.state=="retry"&&aiBackgroundRetrySafe(task.kind,task.createdAt))
        writableDatabase.execSQL("UPDATE work SET state='queued',attempts=0 WHERE id=? AND owner=? AND state='retry'",arrayOf(task.id,task.owner));AiBackgroundRuntime.changed()
    }
    /** Bounded sensitive cache: unfinished input at most its safe replay window; completed results seven days. */
    @Synchronized fun prune()=synchronized(diskLock){
        val owners=readableDatabase.rawQuery("SELECT DISTINCT owner FROM work",null).use {c->buildList {while(c.moveToNext())add(c.getString(0))}}
        owners.forEach {owner->tasks(owner).forEach {task->
        if(task.state !in setOf("consumed","cancelled")&&!aiBackgroundRetrySafe(if(task.state=="result")"insight"else task.kind,task.createdAt)){
            setState(task,"failed","LOCAL_CACHE_EXPIRED","Local recovery cache expired")
            file(task.id,"input").delete();file(task.id,"result").delete()
        }
        }}
        writableDatabase.execSQL("DELETE FROM stops WHERE created_at<?",arrayOf<Any?>(System.currentTimeMillis()-7*24*60*60*1000L))
    }
}

object AiBackgroundWork {
    const val CHANNEL="kejian_ai_work"
    const val JOB_ID=8720
    const val NOTIFICATION_ID=8700
    private val gate=Mutex()
    private var activeJob:Job?=null
    fun schedule(context:Context,delay:Long=1000){
        runCatching {context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_ID,ComponentName(context,AiBackgroundJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())}
    }
    /** Only invoked by a user's foreground action. Recovery jobs never launch a foreground service. */
    fun start(context:Context){
        schedule(context)
        try {ContextCompat.startForegroundService(context,Intent(context,AiBackgroundService::class.java))}
        catch(_:Exception){/* Android may prohibit FGS starts; the persisted JobScheduler request remains. */}
    }
    fun cancel(context:Context,owner:String,requestId:String){
        val journal=AiBackgroundJournal(context)
        journal.markStopped(owner,requestId)
        journal.tasks(owner).firstOrNull {it.requestId==requestId}?.let {journal.cancel(it)}
        if(AiBackgroundRuntime.active?.let {it.owner==owner&&it.requestId==requestId}==true)activeJob?.cancel()
        schedule(context)
    }
    suspend fun run(context:Context,onStatus:(AiBackgroundTask?)->Unit={}):Boolean{
        if(!gate.tryLock())return true
        val journal=AiBackgroundJournal(context)
        try {
            journal.prune()
            val owner=CloudAccountClient(context).currentSession?.userId?:return false
            // A user stop must also reach the server after an offline interval. Cancel-before-arrival is safe.
            journal.tasks(owner).filter {it.state=="cancelled"&&it.code=="USER_STOPPED"}.forEach {task->
                if(aiBackgroundRetrySafe(task.kind,task.createdAt))runCatching {CloudAccountClient(context).cancelAiRequest(task.requestId,owner);journal.cancelledRemotely(task)}
            }
            val candidates=journal.tasks(owner).filter {it.state in setOf("queued","running","retry")&&it.attempts<3}
            for(task in candidates){
                currentCoroutineContext().ensureActive()
                if(CloudAccountClient(context).currentSession?.userId!=owner)break
                if(!aiBackgroundRetrySafe(task.kind,task.createdAt)){journal.setState(task,"failed","LOCAL_CACHE_EXPIRED");continue}
                if(journal.tasks(owner).firstOrNull {it.id==task.id}?.state=="cancelled")continue
                journal.begin(task);AiBackgroundRuntime.active=task;onStatus(task)
                try {
                    coroutineScope {
                        val child=async {execute(context,journal,task)};activeJob=child
                        journal.complete(task,child.await())
                        if(journal.tasks(owner).firstOrNull {it.id==task.id}?.state=="result"&&CloudAccountClient(context).currentSession?.userId==owner)
                            runCatching {context.getSystemService(NotificationManager::class.java).notify(8702,notification(context,task,completed=true))}
                    }
                }catch(cancelled:CancellationException){
                    if(journal.tasks(owner).firstOrNull {it.id==task.id}?.state!="cancelled"){
                        journal.setState(task,"retry","INTERRUPTED");throw cancelled
                    }
                }catch(error:Exception){
                    val code=when(error){is NoteInsightRequestException->error.code;is MindMapRequestException->error.code;is AiBackgroundException->error.code;is CloudApiException->aiCloudFailureCode(error.status);else->"NETWORK_OR_STORAGE"}
                    if(code in setOf("running","INSIGHTS_RUNNING","MINDMAP_RUNNING","SUMMARY_RUNNING"))journal.waitingForServer(task,code)
                    else {
                        val terminal=aiFailureIsTerminal(code)||task.attempts>=2
                        journal.setState(task,if(terminal)"failed"else"retry",code,error.message.orEmpty())
                    }
                }finally{activeJob=null;AiBackgroundRuntime.active=null;AiBackgroundRuntime.changed();onStatus(null)}
            }
            return journal.tasks(owner).any {it.state in setOf("queued","running","retry")&&it.attempts<3||it.state=="cancelled"&&it.code=="USER_STOPPED"&&aiBackgroundRetrySafe(it.kind,it.createdAt)}
        }finally {gate.unlock()}
    }
    private suspend fun execute(context:Context,journal:AiBackgroundJournal,task:AiBackgroundTask):JSONObject{
        val input=journal.input(task);val cloud=CloudAccountClient(context)
        check(cloud.currentSession?.userId==task.owner){"Account changed"}
        when(task.kind){
            "image","command"->{
                // A disconnected POST may have succeeded. Query before any retransmission.
                run {
                    for(poll in 0 until 30){
                        currentCoroutineContext().ensureActive()
                        val prior=cloud.queryAiRequest(task.requestId,task.owner)?:break
                        val status=prior.getString("status")
                        if(status=="preview")return prior.getJSONObject("result").put("jobId",prior.getString("jobId")).also {if(task.kind=="command")it.put("clientSnapshot",input.getJSONObject("snapshot"))}
                        if(status=="running"&&poll<29){delay(15_000);continue}
                        throw AiBackgroundException(prior.optString("errorCode").ifBlank {status},prior.optString("error").ifBlank {recordingCopy("任务已结束，原日程未改变。请重新发送完整安排。","The task has ended. Your schedule is unchanged. Send the complete request again.")})
                    }
                }
                return if(task.kind=="image")cloud.recognizeImage(Base64.decode(input.getString("bytes"),Base64.NO_WRAP),input.getString("mime"),input.optString("name"),input.getInt("colorOffset"),task.requestId,task.owner)
                else cloud.runAiTask(input.getString("command"),input.getJSONArray("scheduleLines").let {a->(0 until a.length()).map {a.getString(it)}},task.requestId,task.owner).put("clientSnapshot",input.getJSONObject("snapshot"))
            }
            "resummary"->return cloud.recordingSummary(input,task.owner)
            "mindmap"->{val note=NoteExchange.parseLine(input.getString("note")).getOrThrow();return cloud.generateRecordingMindMap(note,task.requestId,task.owner).toJson()}
            "insight"->{
                val note=NoteExchange.parseLine(input.getString("note")).getOrThrow()
                val turn=NoteInsightTurn(input.getString("turnId"),task.owner,task.noteId,task.digest,task.requestId,input.getString("question"),input.getString("history"),createdAt=input.getLong("turnCreatedAt"),webSearch=input.optBoolean("webSearch",false))
                return JSONObject().put("turnId",turn.id).put("answer",cloud.askRecordingInsight(note,turn,task.owner).toJson())
            }
            else->error("Unsupported task")
        }
    }
    internal fun notification(context:Context,task:AiBackgroundTask?=null,completed:Boolean=false):android.app.Notification{
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,recordingCopy("AI 后台任务","AI background tasks"),NotificationManager.IMPORTANCE_LOW))
        val title=when(task?.kind){"resummary"->recordingCopy("正在重新总结","Regenerating notes");"image"->recordingCopy("正在识别图片","Recognizing image");"command"->recordingCopy("正在处理日程命令","Processing schedule command");"mindmap"->recordingCopy("正在生成思维导图","Creating mind map");"insight"->recordingCopy("正在生成向笔记提问","Preparing answers about your notes");else->recordingCopy("AI 正在处理任务","AI work in progress")}
        val intent=Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("openAiBackground",true).putExtra("aiWorkOwner",task?.owner).putExtra("aiWorkNoteId",task?.noteId).putExtra("aiWorkKind",task?.kind)
        return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(if(completed)recordingCopy("AI 任务已完成","Your AI result is ready")else title)
            .setContentText(if(completed)recordingCopy("结果已保存在本机。点此查看；日程修改仍需确认。","The result is saved locally. Tap to review; schedule changes still need confirmation.")else recordingCopy("可以切换应用，结果会保存在本机；日程仍需确认。","You may switch apps. Results stay local; schedule changes still require confirmation."))
            .setOngoing(!completed).setAutoCancel(completed).setOnlyAlertOnce(true).setContentIntent(PendingIntent.getActivity(context,if(completed)8703 else 8701,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    }
}
class AiBackgroundException(val code:String,message:String):Exception(message)

class AiBackgroundService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var worker:Job?=null
    private var wakeLock:PowerManager.WakeLock?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        try {startForeground(AiBackgroundWork.NOTIFICATION_ID,AiBackgroundWork.notification(this))}
        catch(_:Exception){AiBackgroundWork.schedule(this);stopSelf();return START_NOT_STICKY}
        if(worker?.isActive!=true)worker=scope.launch {
            try {
                wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Kejian:AiWork").apply {acquire(12*60*1000L)}
                if(withTimeout(12*60*1000L){AiBackgroundWork.run(this@AiBackgroundService){task->runCatching {getSystemService(NotificationManager::class.java).notify(AiBackgroundWork.NOTIFICATION_ID,AiBackgroundWork.notification(this@AiBackgroundService,task))}}})AiBackgroundWork.schedule(this@AiBackgroundService,30_000)
            }catch(_:Exception){AiBackgroundWork.schedule(this@AiBackgroundService,30_000)}
            finally {wakeLock?.let {if(it.isHeld)it.release()};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){worker?.cancel();AiBackgroundWork.schedule(this,60_000);stopSelf()}
    override fun onDestroy(){scope.cancel();wakeLock?.let {if(it.isHeld)it.release()};super.onDestroy()}
}
class AiBackgroundJobService:JobService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var worker:Job?=null
    private var activeParams:JobParameters?=null
    override fun onStartJob(params:JobParameters):Boolean{
        activeParams=params
        worker=scope.launch {var retry=false;try{retry=AiBackgroundWork.run(this@AiBackgroundJobService)}catch(_:Exception){retry=true}finally{if(activeParams===params){activeParams=null;jobFinished(params,retry)}}}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{activeParams=null;worker?.cancel();return true}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
