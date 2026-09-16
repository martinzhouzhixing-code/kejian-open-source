package app.kejian.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.content.pm.ServiceInfo
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.*
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

enum class AudioWorkStage { IDLE, PREPARING, UPLOADING, QUEUED, TRANSCRIBING, SUMMARIZING, RECONNECTING, PAUSED, COMPLETED, FAILED }

/** Separate task state prevents a document update from replacing an audio progress message. */
object BackgroundTaskRuntime {
    var audioRunning by mutableStateOf(false); private set
    var documentRunning by mutableStateOf(false); private set
    var documentRequestId by mutableStateOf<String?>(null); private set
    var audioEstimateSeconds by mutableIntStateOf(0); private set
    var audioQueuePosition by mutableIntStateOf(0); private set
    var audioUploadedBytes by mutableLongStateOf(0); private set
    var audioTotalBytes by mutableLongStateOf(0); private set
    var audioPreparedBytes by mutableLongStateOf(0); private set
    var audioBytesPerSecond by mutableLongStateOf(0); private set
    var audioUploadRemainingSeconds by mutableStateOf<Long?>(null); private set
    private var uploadRate=AudioUploadRate()
    var audioStage by mutableStateOf(AudioWorkStage.IDLE); private set
    var audioStatus by mutableStateOf<String?>(null); private set
    var documentStatus by mutableStateOf<String?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    internal fun audio(start:Boolean,estimate:Int=0){
        audioRunning=start
        if(start){audioEstimateSeconds=estimate;audioQueuePosition=0;audioUploadedBytes=0;audioTotalBytes=0;audioPreparedBytes=0;audioBytesPerSecond=0;audioUploadRemainingSeconds=null;uploadRate=AudioUploadRate();audioStage=AudioWorkStage.PREPARING
            audioStatus=taskCopy("正在检查录音与连接","Checking recording and connection");status=audioStatus}
    }
    internal fun audioUploadPreparing(size:Long){audioTotalBytes=size}
    internal fun audioPreparation(read:Long,size:Long){
        audioPreparedBytes=read;audioTotalBytes=size;audioStage=AudioWorkStage.PREPARING
        val percent=if(size>0)read*100/size else 0
        audioStatus=taskCopy("正在本机校验录音 · $percent%（尚未上传）","Checking recording locally · $percent% (not uploading yet)");status=audioStatus
    }
    internal fun audioProgress(root:JSONObject){
        audioRunning=true
        audioUploadedBytes=root.optLong("uploadedBytes",audioUploadedBytes);audioTotalBytes=root.optLong("sizeBytes",audioTotalBytes)
        audioEstimateSeconds=root.optInt("estimatedSeconds",audioEstimateSeconds)
        audioQueuePosition=root.optInt("queuePosition",audioQueuePosition)
        audioStage=when(root.optString("status")){
            "uploading"->AudioWorkStage.UPLOADING;"queued"->AudioWorkStage.QUEUED;"running"->if(root.optString("stage")=="summarizing")AudioWorkStage.SUMMARIZING else AudioWorkStage.TRANSCRIBING
            "reconnecting"->AudioWorkStage.RECONNECTING;"completed"->AudioWorkStage.COMPLETED;else->audioStage
        }
        if(audioStage==AudioWorkStage.UPLOADING){
            audioBytesPerSecond=uploadRate.update(audioUploadedBytes,SystemClock.elapsedRealtime())
            audioUploadRemainingSeconds=uploadRate.remainingSeconds(audioTotalBytes,audioUploadedBytes)
        }
        audioStatus=when(audioStage){
            AudioWorkStage.UPLOADING->{val rate=if(audioBytesPerSecond>0)" · ${audioBytesPerSecond/1024} KB/s" else ""
                val remaining=audioUploadRemainingSeconds?.let {taskCopy(" · 剩余约 ${max(1,(it+59)/60)} 分钟"," · about ${max(1,(it+59)/60)} min left")}.orEmpty()
                taskCopy("正在上传录音 · ${audioPercent()}%","Uploading recording · ${audioPercent()}%")+rate+remaining}
            AudioWorkStage.QUEUED->taskCopy("正在排队 · 第 $audioQueuePosition 位","Queue position $audioQueuePosition")
            AudioWorkStage.TRANSCRIBING->taskCopy("正在转写录音","Transcribing audio")
            AudioWorkStage.SUMMARIZING->taskCopy("正在整理详细课堂笔记","Writing detailed lesson notes")
            AudioWorkStage.RECONNECTING->taskCopy("连接暂时中断，正在自动重连","Connection interrupted. Reconnecting…")
            AudioWorkStage.COMPLETED->taskCopy("课堂总结已完成","Summary ready")
            else->audioStatus
        };status=audioStatus
    }
    fun audioPercent():Int=if(audioTotalBytes<=0)0 else ((audioUploadedBytes*100)/audioTotalBytes).toInt().coerceIn(0,100)
    internal fun audioFinished(message:String,stage:AudioWorkStage){audioRunning=false;audioStage=stage;audioStatus=message;status=message}
    internal fun document(start:Boolean,requestId:String?=null){
        if(start){documentRunning=true;documentRequestId=requestId;documentStatus=taskCopy("正在本机转换文件，随后自动上传","Converting the file on this device, then uploading");status=documentStatus}
            else if(requestId==null||documentRequestId==requestId){documentRunning=false;documentRequestId=null;documentStatus=null}
    }
    internal fun documentProgress(text:String){documentStatus=text;status=text}
    internal fun done(text:String){status=text}
}
internal fun taskCopy(zh:String,en:String)=if(AppLanguage.english)en else zh

object BackgroundTaskResults {
    private const val PREFS="kejian_background_results"
    private fun prefs(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    private fun resultKey(owner:String,value:String)=java.security.MessageDigest.getInstance("SHA-256")
        .digest((owner+"\u0000"+value).toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun results(context:Context,kind:String,owner:String):List<Pair<String,JSONObject>> = prefs(context).all.entries
        .filter {it.key.startsWith("saved_${kind}_")&&it.value is String}
        .mapNotNull {entry->runCatching {JSONObject(entry.value as String)}.getOrNull()?.takeIf {it.optString("owner")==owner}?.let {entry.key to it}}
        .sortedByDescending {it.second.optLong("savedAt")}
    @Synchronized fun saveAudio(context:Context,noteLine:String,audioPath:String,ownerId:String?=currentBackgroundOwner(context)):Boolean {
        if(ownerId==null)return false
        val key="saved_audio_"+resultKey(ownerId,audioPath+"\u0000"+noteLine)
        return prefs(context).edit().putString(key,JSONObject().put("owner",ownerId).put("path",audioPath).put("line",noteLine).put("savedAt",System.currentTimeMillis()).toString()).commit()
    }
    fun hasAnyAudioForPath(context:Context,audioPath:String):Boolean = prefs(context).all.any {(key,value)->
        key.startsWith("saved_audio_")&&value is String&&runCatching {JSONObject(value).optString("path")==audioPath}.getOrDefault(false)
    } || prefs(context).getString("audio_path",null)==audioPath
    fun hasAudioForPath(context:Context,audioPath:String):Boolean {
        val owner=currentBackgroundOwner(context)?:return false
        if(results(context,"audio",owner).any {it.second.optString("path")==audioPath})return true
        val p=prefs(context)
        return p.contains("audio_note")&&p.getString("audio_path",null)==audioPath&&p.getString("audio_owner",null)==owner
    }
    // Compatibility names now peek. Only an exact acknowledgement removes durable results.
    fun takeAudio(context:Context,ownerId:String?=currentBackgroundOwner(context)):Pair<String,String>? {
        if(ownerId==null)return null
        results(context,"audio",ownerId).firstOrNull()?.second?.let {return it.getString("line") to it.getString("path")}
        val p=prefs(context);if(p.getString("audio_owner",null)!=ownerId)return null
        return (p.getString("audio_note",null)?:return null) to p.getString("audio_path","").orEmpty()
    }
    @Synchronized fun acknowledgeAudio(context:Context,audioPath:String,noteLine:String,ownerId:String?=currentBackgroundOwner(context)):Boolean {
        if(ownerId==null)return false
        val p=prefs(context);val key="saved_audio_"+resultKey(ownerId,audioPath+"\u0000"+noteLine)
        if(p.contains(key))return p.edit().remove(key).commit()
        if(p.getString("audio_owner",null)!=ownerId||p.getString("audio_path",null)!=audioPath||p.getString("audio_note",null)!=noteLine)return false
        return p.edit().remove("audio_note").remove("audio_path").remove("audio_owner").commit()
    }
    @Synchronized fun saveDocument(context:Context,json:String,ownerId:String?=currentBackgroundOwner(context)):Boolean {
        if(ownerId==null)return false
        val id=runCatching {JSONObject(json).getString("clientRequestId")}.getOrNull()?:return false
        return prefs(context).edit().putString("saved_document_"+resultKey(ownerId,id),JSONObject().put("owner",ownerId).put("requestId",id).put("payload",json).put("savedAt",System.currentTimeMillis()).toString()).commit()
    }
    fun takeDocument(context:Context,ownerId:String?=currentBackgroundOwner(context)):String? {
        if(ownerId==null)return null
        results(context,"document",ownerId).firstOrNull()?.second?.let {return it.getString("payload")}
        val p=prefs(context);return p.getString("document_result",null)?.takeIf {p.getString("document_owner",null)==ownerId}
    }
    @Synchronized fun acknowledgeDocument(context:Context,requestId:String,ownerId:String?=currentBackgroundOwner(context)):Boolean {
        if(ownerId==null)return false
        val p=prefs(context);val key="saved_document_"+resultKey(ownerId,requestId)
        if(p.contains(key))return p.edit().remove(key).commit()
        if(p.getString("document_owner",null)!=ownerId)return false
        val result=runCatching {JSONObject(p.getString("document_result",null)?:return false)}.getOrNull()?:return false
        if(result.optString("clientRequestId")!=requestId)return false
        return p.edit().remove("document_result").remove("document_owner").commit()
    }
    fun clearDocument(context:Context):Boolean {
        // Successful results (including legacy data) require an exact owner/request acknowledgement.
        val p=prefs(context);if(p.getString("document_error_owner",null)!=currentBackgroundOwner(context))return false
        return p.edit().remove("document_error").remove("document_error_id").remove("document_error_owner").commit()
    }
    @Synchronized fun savePendingDocument(context:Context,value:JSONObject):Boolean {
        val previous=pendingDocument(context)
        if(previous!=null)return previous.optString("requestId")==value.optString("requestId")&&previous.optString("owner")==value.optString("owner")
        return prefs(context).edit().putString("pending_document",value.toString()).commit()
    }
    fun pendingDocument(context:Context):JSONObject?=prefs(context).getString("pending_document",null)?.let {runCatching {JSONObject(it)}.getOrNull()}
    fun clearPendingDocument(context:Context,requestId:String){
        if(pendingDocument(context)?.optString("requestId")==requestId)prefs(context).edit().remove("pending_document").commit()
    }
    fun saveError(context:Context,kind:String,error:String,requestId:String?=null,ownerId:String?=currentBackgroundOwner(context))=prefs(context).edit().putString("${kind}_error",error.take(500)).putString("${kind}_error_owner",ownerId).apply {
        if(kind=="document")putString("document_error_id",requestId)
    }.commit()
    fun takeError(context:Context,kind:String):String? {val p=prefs(context);val value=p.getString("${kind}_error",null)?:return null;if(p.getString("${kind}_error_owner",null)!=currentBackgroundOwner(context))return null;p.edit().remove("${kind}_error").remove("${kind}_error_owner").apply();return value}
    fun takeDocumentError(context:Context,ownerId:String?=currentBackgroundOwner(context)):Pair<String?,String>?{
        val p=prefs(context);val text=p.getString("document_error",null)?:return null;val id=p.getString("document_error_id",null)
        if(ownerId==null||p.getString("document_error_owner",null)!=ownerId)return null
        p.edit().remove("document_error").remove("document_error_id").remove("document_error_owner").commit();return id to text
    }
    @Synchronized fun rememberDocumentCancel(context:Context,requestId:String,ownerId:String){
        val values=documentCancels(context).filter {it.optString("requestId")!=requestId}.toMutableList()
        values.add(JSONObject().put("requestId",requestId).put("owner",ownerId).put("createdAt",System.currentTimeMillis()))
        prefs(context).edit().putString("document_cancels",org.json.JSONArray(values.takeLast(100)).toString()).commit()
    }
    fun documentCancels(context:Context):List<JSONObject>{
        val values=runCatching {org.json.JSONArray(prefs(context).getString("document_cancels","[]"))}.getOrDefault(org.json.JSONArray())
        return (0 until values.length()).mapNotNull {values.optJSONObject(it)}
    }
    fun documentCancelled(context:Context,id:String)=documentCancels(context).any {it.optString("requestId")==id}
    @Synchronized fun clearDocumentCancel(context:Context,id:String){
        prefs(context).edit().putString("document_cancels",org.json.JSONArray(documentCancels(context).filter {it.optString("requestId")!=id}).toString()).commit()
    }
    data class PendingAudio(
        val path:String,val duration:Int,val estimate:Int,val jobId:String?,
        val requestId:String=java.util.UUID.randomUUID().toString(),val sha256:String?=null,
        val ownerId:String?=null,val sizeBytes:Long=0,val paused:Boolean=false,
        val summaryLanguage:String="zh-CN",val summaryRequirements:String=""
    )
    fun savePendingAudio(context:Context,value:PendingAudio)=prefs(context).edit()
        .putString("pending_audio_path",value.path).putInt("pending_audio_duration",value.duration).putInt("pending_audio_estimate",value.estimate)
        .putString("pending_audio_job",value.jobId).putString("pending_audio_request",value.requestId).putString("pending_audio_sha",value.sha256)
        .putString("pending_audio_owner",value.ownerId).putLong("pending_audio_size",value.sizeBytes).putBoolean("pending_audio_paused",value.paused).putString("pending_summary_language",value.summaryLanguage).putString("pending_summary_requirements",value.summaryRequirements).commit()
    fun pendingAudio(context:Context):PendingAudio? {
        val p=prefs(context);val path=p.getString("pending_audio_path",null)?:return null
        val requestId=p.getString("pending_audio_request",null)?:java.util.UUID.randomUUID().toString().also {p.edit().putString("pending_audio_request",it).commit()}
        return PendingAudio(path,p.getInt("pending_audio_duration",0),p.getInt("pending_audio_estimate",30),p.getString("pending_audio_job",null),
            requestId,p.getString("pending_audio_sha",null),p.getString("pending_audio_owner",null),p.getLong("pending_audio_size",0),p.getBoolean("pending_audio_paused",false),p.getString("pending_summary_language","zh-CN")?:"zh-CN",p.getString("pending_summary_requirements","")?:"")
    }
    fun clearPendingAudio(context:Context)=prefs(context).edit().remove("pending_audio_path").remove("pending_audio_duration")
        .remove("pending_audio_estimate").remove("pending_audio_job").remove("pending_audio_request").remove("pending_audio_sha")
        .remove("pending_audio_owner").remove("pending_audio_size").remove("pending_audio_paused").remove("pending_summary_language").remove("pending_summary_requirements").commit()
}

class BackgroundTaskService:Service(){
    companion object {
        const val ACTION_AUDIO="app.kejian.mobile.BG_AUDIO"
        const val ACTION_DOCUMENT="app.kejian.mobile.BG_DOCUMENT"
        private const val ACTION_CANCEL_DOCUMENT="app.kejian.mobile.CANCEL_DOCUMENT"
        private const val CHANNEL="kejian_background_tasks"
        private const val ACTIVE=8610
        private val cancelledDocuments=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        const val ACTION_PAUSE_AUDIO="app.kejian.mobile.PAUSE_AUDIO_TRANSFER"
        fun audio(context:Context,file:File,duration:Int,estimate:Int,language:String=if(AppLanguage.english)"en"else"zh-CN",requirements:String=""):Intent {
            val previous=BackgroundTaskResults.pendingAudio(context)
            if(previous==null)check(BackgroundTaskResults.savePendingAudio(context,BackgroundTaskResults.PendingAudio(file.absolutePath,duration,estimate,null,ownerId=currentBackgroundOwner(context),summaryLanguage=language,summaryRequirements=requirements))){"Could not save upload recovery state"}
            else if(previous.path==file.absolutePath&&previous.ownerId==currentBackgroundOwner(context))
                BackgroundTaskResults.savePendingAudio(context,previous.copy(paused=false))
            return Intent(context,BackgroundTaskService::class.java).setAction(ACTION_AUDIO).putExtra("path",file.absolutePath).putExtra("duration",duration).putExtra("estimate",estimate).putExtra("manual",true)
        }
        fun resumeAudio(context:Context,manual:Boolean=false){
            val pending=BackgroundTaskResults.pendingAudio(context)?:return
            val owner=currentBackgroundOwner(context)
            if((manual||!pending.paused)&&pending.ownerId==owner&&owner!=null){
                if(manual)BackgroundTaskResults.savePendingAudio(context,pending.copy(paused=false))
                ContextCompat.startForegroundService(context,Intent(context,BackgroundTaskService::class.java).setAction(ACTION_AUDIO).putExtra("resume",true).putExtra("manual",manual))
            }
        }
        fun document(context:Context,uri:Uri,name:String?,mime:String?,colorOffset:Int,requestId:String?=null):Intent {
            val id=requestId?:java.util.UUID.randomUUID().toString()
            check(BackgroundTaskResults.savePendingDocument(context,JSONObject().put("uri",uri.toString()).put("name",name?:"schedule")
                .put("mime",mime.orEmpty()).put("color",colorOffset).put("requestId",id).put("createdAt",System.currentTimeMillis())
                .put("owner",currentBackgroundOwner(context)))){taskCopy("请先继续或停止原来的文件任务；不同账号的未完成任务不会被覆盖。","Continue or stop the existing file task first. Another account's unfinished task will not be replaced.")}
            return documentIntent(context,BackgroundTaskResults.pendingDocument(context)!!)
        }
        internal fun documentIntent(context:Context,pending:JSONObject)=Intent(context,BackgroundTaskService::class.java).setAction(ACTION_DOCUMENT)
            .putExtra("uri",pending.getString("uri")).putExtra("name",pending.optString("name")).putExtra("mime",pending.optString("mime"))
            .putExtra("color",pending.optInt("color")).putExtra("requestId",pending.getString("requestId"))
        fun resumeDocument(context:Context){
            val pending=BackgroundTaskResults.pendingDocument(context)?:return
            if(pending.optString("owner")!=currentBackgroundOwner(context)||pending.optString("requestId") in cancelledDocuments)return
            ContextCompat.startForegroundService(context,documentIntent(context,pending))
        }
        fun cancelDocument(context:Context,requestId:String?=null){
            val id=requestId?:BackgroundTaskRuntime.documentRequestId?:return
            val pending=BackgroundTaskResults.pendingDocument(context)
            val owner=pending?.takeIf {it.optString("requestId")==id}?.optString("owner")?:currentBackgroundOwner(context)?:return
            BackgroundTaskResults.rememberDocumentCancel(context,id,owner)
            cancelledDocuments.add(id);BackgroundTaskResults.acknowledgeDocument(context,id,owner);BackgroundTaskResults.clearDocument(context)
            BackgroundTaskResults.clearPendingDocument(context,id)
            AudioBackgroundRecovery.schedule(context,1_000)
            if(pending?.optString("requestId")==id||BackgroundTaskRuntime.documentRequestId==id)BackgroundWorkCoordinator.cancel("document")
            runCatching {context.startService(Intent(context,BackgroundTaskService::class.java).setAction(ACTION_CANCEL_DOCUMENT).putExtra("requestId",id))}
        }
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var audioJob:Job?=null
    private var documentJob:Job?=null
    private var documentId:String?=null
    private var dispatchingTask=false
    private var pendingCancelRequests=0
    private var timedOut=false
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,localized("后台处理"),NotificationManager.IMPORTANCE_DEFAULT).apply {description=localized("文件转换、AI 转写与总结的进度")})}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val action=intent?.action?:return START_NOT_STICKY
        if(action==ACTION_PAUSE_AUDIO){
            val pending=BackgroundTaskResults.pendingAudio(this)
            if(pending!=null&&pending.requestId==intent.getStringExtra("requestId")){
                BackgroundTaskResults.savePendingAudio(this,pending.copy(paused=true));AudioBackgroundRecovery.cancel(this)
                BackgroundWorkCoordinator.cancel("audio");audioJob?.cancel()
                val message=taskCopy("你已暂停传输，点击继续后才会恢复","Transfer paused by you. Tap Continue to resume.")
                BackgroundTaskRuntime.audioFinished(message,AudioWorkStage.PAUSED);RecordingLibrary.setState(this,pending.path,"paused",message)
            }
            stopIfIdle();return START_NOT_STICKY
        }
        if(action==ACTION_CANCEL_DOCUMENT){
            val id=intent.getStringExtra("requestId")
            if(id!=null){
                pendingCancelRequests++
                cancelledDocuments.add(id)
                if(documentId==id)documentJob?.cancel()
                if(documentId==id||BackgroundTaskRuntime.documentRequestId==id)BackgroundWorkCoordinator.cancel("document")
                scope.launch {try {currentBackgroundOwner(this@BackgroundTaskService)?.let {retryDocumentCancellations(this@BackgroundTaskService,it)}}finally{pendingCancelRequests--;stopIfIdle()}}
            }else stopIfIdle()
            return START_NOT_STICKY
        }
        if(action!=ACTION_AUDIO&&action!=ACTION_DOCUMENT){stopIfIdle();return START_NOT_STICKY}
        if(action==ACTION_AUDIO){
            val pending=BackgroundTaskResults.pendingAudio(this)
            if(pending==null||!AudioRecoveryPolicy.canResume(pending.paused,pending.ownerId,currentBackgroundOwner(this))){stopIfIdle();return START_NOT_STICKY}
        }
        try {
            val notice=notification(taskCopy("课间正在后台处理","Kejian background task"),taskCopy("可以离开此页，完成后会通知你","You can leave this page. We will notify you when it is ready."),true)
            if(Build.VERSION.SDK_INT>=29)startForeground(ACTIVE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ACTIVE,notice)
        }catch(_:Exception){
            BackgroundTaskRuntime.audioFinished(taskCopy("系统暂不允许后台处理，回到课间后会继续","Android restricted background work. Open Kejian to continue."),AudioWorkStage.RECONNECTING)
            stopSelf();return START_NOT_STICKY
        }
        dispatchingTask=true
        if(action==ACTION_AUDIO){
            if(audioJob?.isActive!=true)audioJob=scope.launch {
                try {BackgroundWorkCoordinator.run("audio") {BackgroundWorkRunner(this@BackgroundTaskService,currentBackgroundOwner(this@BackgroundTaskService)?:return@run).runAudio(intent)}}finally {audioJob=null;stopIfIdle()}
            }
        }else {
            val id=intent.getStringExtra("requestId")?:java.util.UUID.randomUUID().toString()
            if(documentJob?.isActive==true&&documentId==id){dispatchingTask=false;return START_REDELIVER_INTENT}
            val previous=documentJob;documentId=id;previous?.cancel()
            documentJob=scope.launch {
                try {if(id !in cancelledDocuments)BackgroundWorkCoordinator.run("document") {
                    try {BackgroundWorkRunner(this@BackgroundTaskService,currentBackgroundOwner(this@BackgroundTaskService)?:return@run).runDocument(intent,id)}
                    finally {BackgroundTaskRuntime.document(false,id)}
                }}
                finally {if(documentId==id){documentJob=null;documentId=null};stopIfIdle()}
            }
        }
        dispatchingTask=false;stopIfIdle()
        return START_REDELIVER_INTENT
    }
    private fun stopIfIdle(){
        if(dispatchingTask)return
        if(audioJob?.isActive!=true&&documentJob?.isActive!=true&&pendingCancelRequests==0){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        else showActiveNotification()
    }
    private fun notification(title:String,body:String,ongoing:Boolean=false):Notification {
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setOngoing(ongoing).setOnlyAlertOnce(ongoing).setAutoCancel(!ongoing)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(PendingIntent.getActivity(this,8611,Intent(this,MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RECORDING,BackgroundTaskRuntime.audioRunning).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if(ongoing&&BackgroundTaskRuntime.audioRunning){BackgroundTaskResults.pendingAudio(this)?.let {pending->
            builder.addAction(0,taskCopy("暂停传输","Pause transfer"),PendingIntent.getService(this,8616,
                Intent(this,BackgroundTaskService::class.java).setAction(ACTION_PAUSE_AUDIO).putExtra("requestId",pending.requestId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }}
        return builder.build()
    }
    private fun showActiveNotification(){
        val messages=listOfNotNull(BackgroundTaskRuntime.audioStatus.takeIf {BackgroundTaskRuntime.audioRunning},
            BackgroundTaskRuntime.documentStatus.takeIf {BackgroundTaskRuntime.documentRunning})
        runCatching {getSystemService(NotificationManager::class.java).notify(ACTIVE,notification(taskCopy("课间后台任务","Kejian background task"),messages.joinToString("\n"),true))}
    }
    private fun finish(kind:String,title:String,body:String,error:Boolean=false,requestId:String?=null){
        runCatching {getSystemService(NotificationManager::class.java).notify(if(kind=="audio")8612 else 8613,notification(title,body))}
        if(error)BackgroundTaskResults.saveError(this,kind,body,requestId)
        BackgroundTaskRuntime.done(body)
    }
    override fun onTimeout(startId:Int,fgsType:Int){
        timedOut=true
        val message=taskCopy("系统后台处理时限已到，进度已保存；回到课间后继续。","Android's background time limit was reached. Progress is saved; open Kejian to continue.")
        BackgroundTaskRuntime.audioFinished(message,AudioWorkStage.RECONNECTING)
        scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
        // Continue only through a separate, bounded JobScheduler execution budget.
        AudioBackgroundRecovery.schedule(this,30_000)
    }
    override fun onDestroy(){
        scope.cancel()
        if(!timedOut&&(BackgroundTaskResults.pendingAudio(this)?.paused==false||BackgroundTaskResults.pendingDocument(this)!=null))AudioBackgroundRecovery.schedule(this,60_000)
        super.onDestroy()
    }
}

object LocalDocumentRenderer {
    private const val MAX_SIDE=8192
    fun render(context:Context,uri:Uri,name:String,mime:String):ByteArray{
        val lower=name.lowercase();return when {mime=="application/pdf"||lower.endsWith(".pdf")->renderPdf(context,uri);lower.endsWith(".xlsx")||mime.contains("spreadsheetml")->renderXlsx(context,uri);lower.endsWith(".xls")->error("旧版 .xls 暂不支持，请另存为 .xlsx 后重试");else->error("目前文件识别支持 PDF 与 XLSX")}
    }
    private fun renderPdf(context:Context,uri:Uri):ByteArray{val fd=context.contentResolver.openFileDescriptor(uri,"r")?:error("PDF 无法读取");fd.use {descriptor->PdfRenderer(descriptor).use {renderer->require(renderer.pageCount in 1..20){"PDF 应为 1–20 页"};val dimensions=(0 until renderer.pageCount).map {index->renderer.openPage(index).use {page->val width=min(1800,page.width*2);width to (width.toFloat()/page.width*page.height).roundToInt()}};val maxW=dimensions.maxOf {it.first};val totalH=dimensions.sumOf {it.second};val scale=min(1f,min(MAX_SIDE.toFloat()/maxW,MAX_SIDE.toFloat()/totalH));val out=Bitmap.createBitmap(max(2,(maxW*scale).roundToInt()),max(2,(totalH*scale).roundToInt()),Bitmap.Config.ARGB_8888);val canvas=Canvas(out);canvas.drawColor(Color.WHITE);var y=0f;for(i in dimensions.indices){renderer.openPage(i).use {page->val (naturalW,naturalH)=dimensions[i];val width=max(2,(naturalW*scale).roundToInt());val height=max(2,(naturalH*scale).roundToInt());val pageBitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);pageBitmap.eraseColor(Color.WHITE);page.render(pageBitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);canvas.drawBitmap(pageBitmap,0f,y,null);y+=height;pageBitmap.recycle()}};return encode(out)}}}
    private fun renderXlsx(context:Context,uri:Uri):ByteArray{val temp=File.createTempFile("kejian-sheet-",".xlsx",context.cacheDir);try{context.contentResolver.openInputStream(uri)?.use {input->temp.outputStream().use {input.copyTo(it)}}?:error("表格无法读取");ZipFile(temp).use {zip->
            val shared=zip.getEntry("xl/sharedStrings.xml")?.let {entry->parse(zip.getInputStream(entry)).getElementsByTagName("t").let {nodes->(0 until nodes.length).map {nodes.item(it).textContent}}}.orEmpty()
            val sheets=zip.entries().toList().filter {it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))};require(sheets.isNotEmpty()){"XLSX 中没有工作表"}
            val chosen=sheets.maxBy {entry->zip.getInputStream(entry).use {it.readBytes().size}}
            val doc=parse(zip.getInputStream(chosen));val cells=doc.getElementsByTagName("c");data class Cell(val row:Int,val col:Int,val text:String);val values=mutableListOf<Cell>();var maxRow=0;var maxCol=0
            for(i in 0 until cells.length){val e=cells.item(i) as Element;val ref=e.getAttribute("r");val row=ref.dropWhile {it.isLetter()}.toIntOrNull()?.minus(1)?:continue;val col=columnIndex(ref.takeWhile {it.isLetter()});val type=e.getAttribute("t");val raw=(e.getElementsByTagName(if(type=="inlineStr")"t" else "v").item(0)?.textContent).orEmpty();val text=if(type=="s")shared.getOrNull(raw.toIntOrNull()?:-1).orEmpty() else raw;if(text.isNotBlank()){values+=Cell(row,col,text.take(120));maxRow=max(maxRow,row);maxCol=max(maxCol,col)}}
            require(values.isNotEmpty()){"工作表没有可识别内容"};maxRow=min(maxRow,199);maxCol=min(maxCol,29);val cellW=220;val cellH=74;val naturalW=(maxCol+1)*cellW+2;val naturalH=(maxRow+1)*cellH+2;val scale=min(1f,MAX_SIDE.toFloat()/max(naturalW,naturalH));val bmp=Bitmap.createBitmap(max(2,(naturalW*scale).roundToInt()),max(2,(naturalH*scale).roundToInt()),Bitmap.Config.ARGB_8888);val canvas=Canvas(bmp);canvas.drawColor(Color.WHITE);canvas.scale(scale,scale);val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(195,205,201);style=Paint.Style.STROKE;strokeWidth=1.5f};val textPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(20,45,37);textSize=27f};for(r in 0..maxRow)for(c in 0..maxCol)canvas.drawRect(c*cellW.toFloat(),r*cellH.toFloat(),(c+1)*cellW.toFloat(),(r+1)*cellH.toFloat(),grid);values.filter {it.row<=maxRow&&it.col<=maxCol}.forEach {cell->val clipped=cell.text.take(14);canvas.drawText(clipped,cell.col*cellW+10f,cell.row*cellH+44f,textPaint)};return encode(bmp)
        }}finally{temp.delete()}}
    private fun parse(input:InputStream)=input.use {DocumentBuilderFactory.newInstance().apply {isNamespaceAware=false;setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}.newDocumentBuilder().parse(it)}
    private fun columnIndex(value:String):Int {var n=0;value.uppercase().forEach {if(it in 'A'..'Z')n=n*26+(it-'A'+1)};return max(0,n-1)}
    private fun encode(bitmap:Bitmap):ByteArray=ByteArrayOutputStream().use {out->bitmap.compress(Bitmap.CompressFormat.JPEG,92,out);bitmap.recycle();require(out.size()<=24*1024*1024){"渲染后的图片超过 24 MB"};out.toByteArray()}
}
