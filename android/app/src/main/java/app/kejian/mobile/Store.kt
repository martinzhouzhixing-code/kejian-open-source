package app.kejian.mobile

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Build
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

object DataJson {
    fun encode(data: AppData,includeLocalMedia:Boolean=true): String = JSONObject().apply {
        put("schemaVersion",4)
        put("weeklyRemarks",JSONObject(data.weeklyRemarks))
        put("settings",JSONObject().apply {
            put("hideEmptyWeekends",data.settings.hideEmptyWeekends);put("visibleStart",data.settings.visibleStart);put("visibleEnd",data.settings.visibleEnd)
            put("termStart",data.settings.termStart.toString());put("termName",data.settings.termName)
            put("reminders",data.settings.reminders);put("reminderMinutes",data.settings.reminderMinutes);put("editAllWeeks",data.settings.editAllWeeks)
            put("gridZoomX",data.settings.gridZoomX.toDouble());put("gridZoomY",data.settings.gridZoomY.toDouble());put("widgetOpacity",data.settings.widgetOpacity.toDouble())
            put("customTheme",data.settings.customTheme);put("customAccent",data.settings.customAccent);put("customSurface",data.settings.customSurface);put("liquidGlass",data.settings.liquidGlass);put("appBackgroundUri",if(includeLocalMedia)data.settings.appBackgroundUri else null);put("appBackgroundBlur",data.settings.appBackgroundBlur.toDouble());put("appBackgroundTone",data.settings.appBackgroundTone.toDouble())
            put("skin",data.settings.skin);put("glassBackground",data.settings.glassBackground);put("widgetFrosted",data.settings.widgetFrosted);put("themeMode",data.settings.themeMode);put("language",data.settings.language);put("gridZoom",data.settings.gridZoom.toDouble())
            put("widgetBackgroundMode",data.settings.widgetBackgroundMode);put("widgetBackgroundUri",data.settings.widgetBackgroundUri);put("widgetBackgroundTone",data.settings.widgetBackgroundTone.toDouble());put("widgetTextMode",data.settings.widgetTextMode);put("widgetImageLuminance",data.settings.widgetImageLuminance?.toDouble())
            put("widgetStyle",JSONObject().apply {
                val style=data.settings.widgetStyle.normalized()
                put("cornerRadius",style.cornerRadius.toDouble());put("top",style.top.toDouble());put("bottom",style.bottom.toDouble());put("left",style.left.toDouble());put("right",style.right.toDouble())
            })
        })
        // Every persisted module uses the same strict record consumed by file recognition and future AI.
        put("courseLines",JSONArray().apply {data.courses.forEach {put(CourseExchange.encodeCourse(it))}})
        put("deadlineLines",JSONArray().apply {data.deadlines.forEach {put(DeadlineExchange.encodeDeadline(it))}})
        put("noteLines",JSONArray().apply {data.notes.forEach {put(NoteExchange.encodeNote(it,includeLocalMedia))}})
    }.toString(2)
    fun decode(text: String): AppData {
        require(text.length<=8_000_000){"备份文件过大"}
        val json=JSONObject(text);val schema=json.getInt("schemaVersion");require(schema in 1..4){"不支持这个备份版本"}
        val s=json.getJSONObject("settings")
        val style=s.optJSONObject("widgetStyle")?:JSONObject()
        val widgetStyle=WidgetStyle(style.optDouble("cornerRadius",24.0).toFloat(),style.optDouble("top",14.0).toFloat(),style.optDouble("bottom",14.0).toFloat(),style.optDouble("left",14.0).toFloat(),style.optDouble("right",14.0).toFloat()).normalized()
        val backgroundMode=s.optString("widgetBackgroundMode","solid").takeIf {it in listOf("solid","translucent","image")}?:"solid"
        val backgroundUri=s.optString("widgetBackgroundUri","").takeIf {it.isNotBlank()}
        val settings=Settings(hideEmptyWeekends=s.optBoolean("hideEmptyWeekends",true),visibleStart=s.optInt("visibleStart",420).coerceIn(0,1410),visibleEnd=s.optInt("visibleEnd",1440).coerceIn(s.optInt("visibleStart",420).coerceIn(0,1410)+30,1440),customTheme=s.optBoolean("customTheme",s.optString("appBackgroundUri","").let {it.isNotBlank()&&it!="null"}),customAccent=validThemeHex(s.optString("customAccent","#245C9B"))?:"#245C9B",customSurface=validThemeHex(s.optString("customSurface","#245C9B"))?:"#245C9B",liquidGlass=s.optBoolean("liquidGlass",true),appBackgroundUri=s.optString("appBackgroundUri","").takeIf {it.isNotBlank()&&it!="null"},appBackgroundBlur=s.optDouble("appBackgroundBlur",.25).takeIf {it.isFinite()}?.toFloat()?.coerceIn(0f,1f)?:.25f,appBackgroundTone=s.optDouble("appBackgroundTone",0.0).takeIf {it.isFinite()}?.toFloat()?.coerceIn(-1f,1f)?:0f,skin=s.optString("skin","forest").takeIf {it in setOf("forest","ocean","dusk","sunset")}?:"forest",glassBackground=s.optBoolean("glassBackground",true),widgetFrosted=s.optBoolean("widgetFrosted",false),termStart=monday(LocalDate.parse(s.getString("termStart"))),termName=s.optString("termName","我的学期").take(80),reminders=s.optBoolean("reminders",false),reminderMinutes=s.optInt("reminderMinutes",10).coerceIn(1,120),themeMode=s.optString("themeMode","system").takeIf {it in listOf("system","light","dark")}?:"system",language=s.optString("language","zh").takeIf {it in listOf("zh","en")}?:"zh",gridZoom=s.optDouble("gridZoom",1.0).toFloat().coerceIn(.65f,1.8f),editAllWeeks=s.optBoolean("editAllWeeks",false),gridZoomX=s.optDouble("gridZoomX",s.optDouble("gridZoom",1.0)).toFloat().coerceIn(.65f,1.8f),gridZoomY=s.optDouble("gridZoomY",s.optDouble("gridZoom",1.0)).toFloat().coerceIn(.65f,1.8f),widgetOpacity=s.optDouble("widgetOpacity",1.0).toFloat().coerceIn(.35f,1f),widgetBackgroundMode=backgroundMode,widgetBackgroundUri=backgroundUri,widgetBackgroundTone=s.optDouble("widgetBackgroundTone",0.0).toFloat().coerceIn(-1f,1f),widgetTextMode=s.optString("widgetTextMode","auto").takeIf {it in setOf("auto","black","white")}?:"auto",widgetImageLuminance=s.optDouble("widgetImageLuminance",Double.NaN).takeIf {it.isFinite()&&it in 0.0..1.0}?.toFloat())
        require(listOf(settings.gridZoom,settings.gridZoomX,settings.gridZoomY,settings.widgetOpacity,settings.widgetBackgroundTone).all {it.isFinite()}) { "缩放比例无效" }
        val courses=if(schema>=2){val a=json.getJSONArray("courseLines");require(a.length()<=1000){"最多支持 1000 节课程"};(0 until a.length()).map {i->val parsed=CourseExchange.parse(a.getString(i));require(parsed.errors.isEmpty()&&parsed.courses.size==1){"第 ${i+1} 节固定格式无效：${parsed.errors.joinToString()}"};parsed.courses.single()}}
        else {val a=json.getJSONArray("courses");require(a.length()<=1000){"最多支持 1000 节课程"};(0 until a.length()).map { i -> val c=a.getJSONObject(i);val w=c.getJSONArray("weeks");val ex=c.optJSONArray("excluded")?:JSONArray()
            Course(id=c.getString("id"),name=c.getString("name"),day=c.getInt("day"),start=c.getInt("start"),end=c.getInt("end"),color=c.optInt("color",0),address=c.optString("address",""),room=c.optString("room",""),teacher=c.optString("teacher",""),weeks=(0 until w.length()).map { w.getInt(it) }.toSet(),reminder=c.optBoolean("reminder",true),date=if(c.isNull("date"))null else LocalDate.parse(c.getString("date")),excluded=(0 until ex.length()).map { LocalDate.parse(ex.getString(it)) }.toSet()).also { require(it.id.isNotBlank()&&it.id.length<=80){"课程 ID 无效"};require(it.error()==null){"第 ${i+1} 节：${it.error()}"} }
        }}
        require(courses.map { it.id }.distinct().size==courses.size){"备份中存在重复课程 ID"}
        val deadlines=if(schema>=3){val a=json.optJSONArray("deadlineLines")?:JSONArray();require(a.length()<=1000){"最多支持 1000 个截止日"};(0 until a.length()).map {i->DeadlineExchange.parseLine(a.getString(i)).getOrElse {error("第 ${i+1} 个截止日格式无效：${it.message}")}}}else emptyList()
        require(deadlines.map {it.id}.distinct().size==deadlines.size){"备份中存在重复截止日 ID"}
        val notes=if(schema>=4){val a=json.optJSONArray("noteLines")?:JSONArray();require(a.length()<=2000){"最多支持 2000 份课堂总结"};(0 until a.length()).map {i->NoteExchange.parseLine(a.getString(i)).getOrElse {error("第 ${i+1} 份课堂总结格式无效：${it.message}")}}}else emptyList()
        require(notes.map {it.id}.distinct().size==notes.size){"备份中存在重复总结 ID"}
        val remarks=json.optJSONObject("weeklyRemarks")?:JSONObject()
        require(remarks.length()<=10000){"课程备注数量过多"}
        val weeklyRemarks=remarks.keys().asSequence().associateWith {key->
            require(key.length<=100){"课程备注标识无效"}
            remarks.getString(key).also {require(it.length<=10000){"单周备注最多 10000 字"}}
        }
        return AppData(courses,settings.copy(widgetStyle=widgetStyle),deadlines,notes,weeklyRemarks)
    }
}

class CourseStore(context: Context) {
    private val prefs=context.getSharedPreferences("kejian_data",Context.MODE_PRIVATE)
    fun load(): AppData {
        val value=prefs.getString("data",null)
        return if(value==null)AppData(demoCourses(),Settings(termName="示例学期",reminders=false)).also { save(it) } else DataJson.decode(value).also {decoded->if(runCatching {JSONObject(value).optInt("schemaVersion",1)}.getOrDefault(1)<3)runCatching {save(decoded)}}
    }
    fun save(data:AppData) {
        require(data.courses.size<=1000){"最多支持 1000 节课程"}
        require(data.deadlines.size<=1000){"最多支持 1000 个截止日"};require(data.notes.size<=2000){"最多支持 2000 份课堂总结"}
        data.courses.forEach { require(it.error()==null){it.error().orEmpty()} }
        data.deadlines.forEach { require(it.error()==null){it.error().orEmpty()} }
        data.notes.forEach { require(it.error()==null){it.error().orEmpty()} }
        val encoded=DataJson.encode(data)
        require(encoded.length<=8_000_000){if(AppLanguage.english)"Your local notebook is too large to save safely. Export existing notes first; no data was overwritten." else "本地资料已超过安全保存上限，请先导出已有资料；原有数据未被覆盖。"}
        check(prefs.edit().putString("data",encoded).commit()){ "保存失败，请检查手机空间" }
    }
}

class KejianViewModel(app: Application): AndroidViewModel(app) {
    private val appContext=app.applicationContext
    private val prefs=app.getSharedPreferences("kejian_experience",Context.MODE_PRIVATE)
    private val existingInstall=app.getSharedPreferences("kejian_data",Context.MODE_PRIVATE).contains("data")
    // Check before load() seeds demo data, so upgrades never masquerade as a first installation.
    var tutorialPending by mutableStateOf(prefs.safeBoolean("tutorialPending",!existingInstall));private set
    var accountPromptPending by mutableStateOf(false);private set
    var releaseNotesPending by mutableStateOf(existingInstall&&prefs.safeLong("lastSeenReleaseCode",0L)<CurrentReleaseNotes.versionCode);private set
    init {
        prefs.edit().putBoolean("tutorialPending",tutorialPending).apply()
        if(!existingInstall)prefs.edit().putLong("lastSeenReleaseCode",CurrentReleaseNotes.versionCode).apply()
        if(!tutorialPending&&!prefs.contains("accountPromptShown"))prefs.edit().putBoolean("accountPromptShown",true).apply()
    }
    fun finishTutorial(){
        tutorialPending=false
        val show=!prefs.safeBoolean("accountPromptShown",false)
        prefs.edit().putBoolean("tutorialPending",false).putBoolean("accountPromptShown",true).apply()
        accountPromptPending=false
    }
    fun configurePersonalAi(gateway:String,key:String,asrKey:String):Boolean {
        return runCatching {cloud.configure(gateway,key,asrKey)}.fold(
            onSuccess={cloudSession=it;true},onFailure={message=it.message;false})
    }
    fun dismissAccountPrompt(){accountPromptPending=false;prefs.edit().putBoolean("accountPromptShown",true).apply()}
    fun replayTutorial(){tutorialPending=true;prefs.edit().putBoolean("tutorialPending",true).apply()}
    fun dismissReleaseNotes(){releaseNotesPending=false;prefs.edit().putLong("lastSeenReleaseCode",CurrentReleaseNotes.versionCode).apply()}
    private val updateChecker=AppUpdateChecker(app)
    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null);private set
    var updateDialogVisible by mutableStateOf(false);private set
    private var lastUpdateCheck=0L
    init { AppUpdateWebsite.retireLegacyUpdater(appContext) }
    fun checkForUpdate(){ return
        @Suppress("UNREACHABLE_CODE")
        val now=System.currentTimeMillis();if(now-lastUpdateCheck<30_000)return;lastUpdateCheck=now
        viewModelScope.launch {runCatching {updateChecker.check()}.getOrNull()?.let {info->
            availableUpdate=info
            updateDialogVisible=prefs.safeLong("dismissedUpdateCode",-1L)!=info.versionCode
        }}
    }
    fun dismissUpdate(){availableUpdate?.let {prefs.edit().putLong("dismissedUpdateCode",it.versionCode).apply()};updateDialogVisible=false}
    fun openUpdateWebsite(){runCatching {AppUpdateWebsite.open(appContext)}.onSuccess {updateDialogVisible=false}.onFailure {
        message=if(AppLanguage.english)"Could not open a browser. Visit https://kejian.im/ to update." else "无法打开浏览器，请访问 https://kejian.im/ 更新。"
    }}
    private val store=CourseStore(app)
    var loadError by mutableStateOf<String?>(null);private set
    var data by mutableStateOf(try { store.load() }catch(e:Exception){loadError=e.message;AppData()});private set
    var savedData by mutableStateOf(data);private set
    private val cloud=CloudAccountClient(app)
    private var cloudSessionState by mutableStateOf(cloud.currentSession)
    var cloudSession:CloudSession?
        get()=cloudSessionState
        private set(value){
            val changed=cloudSessionState?.userId!=value?.userId
            if(changed&&recognitionCanStop)stopRecognition()
            cloudSessionState=value
            if(changed){
                recognitionPreview=null;resummaryBusyNoteId=null;resummaryEnqueuingNoteId=null;resummaryMessage=null;resummaryMessageNoteId=null;mindMapBusyNoteId=null;mindMapEnqueuingNoteId=null;mindMapMessage=null;insightBusyScope=null;insightBusyTurnId=null;insightMessage=null
                loadAiHistory(value?.userId?:"local")
            }
        }
    val recognitionCredits:Int? get()=cloudSession?.profile?.aiPoints
    val supporter:Boolean get()=true
    val cloudSlotCount:Int get()=ProductAccess.cloudSlotCount
    var cloudBusy by mutableStateOf(false);private set
    var cloudMessage by mutableStateOf<String?>(null);private set
    var cloudConflict by mutableStateOf<CloudConflict?>(null);private set
    var cloudSlots by mutableStateOf<List<CloudSchedule?>>(List(3){null});private set
    var recognitionBusy by mutableStateOf(false);private set
    var aiClarification by mutableStateOf<Pair<String,String>?>(null);private set
    fun dismissAiClarification(){aiClarification=null}
    private var recognitionJob:Job?=null
    private var recognitionRequestId by mutableStateOf<String?>(null)
    private var recognitionIsDocument=false
    private val cancelledRecognitionRequests=prefs.getStringSet("cancelledRecognitionRequests",emptySet()).orEmpty().toMutableSet()
    val recognitionCanStop:Boolean get() {
        val requestId=recognitionRequestId?:BackgroundTaskRuntime.documentRequestId.takeIf {BackgroundTaskRuntime.documentRunning}
        return (recognitionBusy||BackgroundTaskRuntime.documentRunning)&&requestId!=null&&requestId !in cancelledRecognitionRequests
    }
    var recognitionPreview by mutableStateOf<ImportPreview?>(null);private set
    var audioBusy by mutableStateOf(false);private set
    var audioNotePreview by mutableStateOf<LessonNote?>(null);private set
    var audioMessage by mutableStateOf<String?>(null)
    var resummaryBusyNoteId by mutableStateOf<String?>(null);private set
    var resummaryMessage by mutableStateOf<String?>(null);private set
    var resummaryMessageNoteId by mutableStateOf<String?>(null);private set
    var resummaryRevision by mutableIntStateOf(0);private set
    private var resummaryEnqueuingNoteId:String?=null
    private var mindMapEnqueuingNoteId:String?=null
    var mindMapBusyNoteId by mutableStateOf<String?>(null);private set
    var mindMapMessage by mutableStateOf<String?>(null);private set
    private val recordingInsights=RecordingInsightsStore(app)
    private val aiWork=AiBackgroundJournal(app)
    private var aiWorkConsumeJob:Job?=null
    private var aiWorkConsumeAgain=false
    var insightBusyScope by mutableStateOf<String?>(null);private set
    var insightBusyTurnId by mutableStateOf<String?>(null);private set
    var insightMessage by mutableStateOf<String?>(null);private set
    private var insightMessageScope:String?=null
    private val aiHistoryStore=AiHistoryStore(app)
    private var aiHistoryOwner=cloud.currentSession?.userId?:"local"
    private val aiHistoryWrites=Channel<suspend ()->Unit>(Channel.UNLIMITED)
    private var aiMessageState by mutableStateOf(runCatching {aiHistoryStore.messages(aiHistoryOwner)}.getOrDefault(emptyList()))
    var aiMessages:List<AiChatMessage>
        get()=aiMessageState
        private set(value){
            aiMessageState=value
            val owner=aiHistoryOwner
            aiHistoryWrites.trySend {aiHistoryStore.saveMessages(owner,value)}
        }
    var aiTaskHistory by mutableStateOf(runCatching {aiHistoryStore.tasks(aiHistoryOwner)}.getOrDefault(emptyList()));private set
    init {
        viewModelScope.launch(Dispatchers.IO){for(write in aiHistoryWrites)try {write()}catch(_:Exception){withContext(Dispatchers.Main){message=if(AppLanguage.english)"Could not save AI history on this device. Check storage space." else "AI 历史记录保存失败，请检查本机存储空间。"}}}
        // A process death is not a successful operation. Completed background documents
        // may update this status again when their durable result is consumed.
        if(!BackgroundTaskRuntime.documentRunning&&aiWork.tasks(aiHistoryOwner).none {it.busy}){
            aiMessages=aiMessages.map {if(it.pending)it.copy(pending=false,text=if(AppLanguage.english)"The previous task was interrupted. Your schedule was not changed." else "上次任务已中断，日程没有改动。")else it}
            aiTaskHistory.filter {it.status=="running"}.forEach {updateAiTask(it.id,"interrupted")}
        }
        consumeAiBackgroundResults()
    }
    private fun loadAiHistory(owner:String){
        aiHistoryOwner=owner
        // FIFO with writes already enqueued for the previous account; no account's
        // private messages are shown while the next account is being loaded.
        aiMessageState=emptyList();aiTaskHistory=emptyList()
        aiHistoryWrites.trySend {
            val loadedMessages=aiHistoryStore.messages(owner);val loadedTasks=aiHistoryStore.tasks(owner)
            withContext(Dispatchers.Main){if(aiHistoryOwner==owner){aiMessageState=loadedMessages;aiTaskHistory=loadedTasks}}
        }
    }
    fun clearAiHistory(){
        if(recognitionBusy||BackgroundTaskRuntime.documentRunning){message=if(AppLanguage.english)"Stop the active task before clearing history." else "请先停止当前任务，再清空历史记录。";return}
        val owner=aiHistoryOwner;aiMessageState=emptyList();aiTaskHistory=emptyList()
        aiHistoryWrites.trySend {aiHistoryStore.clear(owner)}
    }
    private fun updateAiTask(id:String?,status:String,details:String="",preview:ImportPreview?=null){
        val old=aiTaskHistory.firstOrNull {it.id==id||id!=null&&it.jobId==id}?:return
        val snapshot=preview?.let {DataJson.encode(AppData(it.courses,it.backup?.settings?:data.settings,it.deadlines),false)}?:old.snapshot
        val updated=old.copy(status=status,updatedAt=System.currentTimeMillis(),details=details.ifBlank {old.details},jobId=preview?.jobId?:old.jobId,snapshot=snapshot)
        aiTaskHistory=aiTaskHistory.map {if(it.id==old.id)updated else it}
        val owner=aiHistoryOwner;aiHistoryWrites.trySend {aiHistoryStore.saveTask(owner,updated)}
    }
    var captcha by mutableStateOf<CaptchaChallenge?>(null);private set
    var avatarRevision by mutableIntStateOf(0);private set
    private var lastCloudCheck=0L
    init {ProductAccess.initialize(app);ProductAccess.update(cloudSession?.profile)}
    // Cloud I/O is intentionally deferred until the first stable activity frame.
    var isEditing by mutableStateOf(false);private set
    val hasDraftChanges get()=data!=savedData

    fun saveAvatar(uri:Uri){
        val userId=cloudSession?.userId?:run {message="请先登录账号";return}
        viewModelScope.launch {runCatching {withContext(Dispatchers.IO){LocalMediaStore.saveAvatar(getApplication(),userId,uri)}}
            .onSuccess {avatarRevision++;cloudMessage="头像已更新"}
            .onFailure {cloudMessage="头像保存失败：${it.message}"}}
    }

    fun saveWidgetBackground(uri:Uri){
        viewModelScope.launch {runCatching {withContext(Dispatchers.IO){LocalMediaStore.saveWidgetBackground(getApplication(),uri).let {it to WidgetMaterials.imageLuminance(getApplication(),it.toString())}}}
            .onSuccess {(saved,luma)->commit(savedData.copy(settings=savedData.settings.copy(widgetBackgroundMode="image",widgetBackgroundUri=saved.toString(),widgetBackgroundTone=0f,widgetTextMode="auto",widgetImageLuminance=luma)),"小组件背景图片已保存",false)}
            .onFailure {message="背景图片无法保存：${it.message}"}}
    }
    private var draftRestore=false
    private val history=EditHistory()
    var canUndo by mutableStateOf(false);private set
    var canRedo by mutableStateOf(false);private set
    private fun syncHistory(){canUndo=history.canUndo;canRedo=history.canRedo}
    var message by mutableStateOf<String?>(null)
    var editor by mutableStateOf<Course?>(null)
    var editingDate by mutableStateOf<LocalDate?>(null)
    var shownWeek by mutableStateOf(monday(LocalDate.now()))
    fun beginEditing(){if(isEditing)return;data=savedData;history.clear();syncHistory();draftRestore=false;isEditing=true}
    fun discardEditing(){data=savedData;isEditing=false;draftRestore=false;history.clear();syncHistory();editor=null;message=null}
    fun saveEditing():Boolean {
        if(!isEditing)return true
        if(!persist(data,"课表已保存，已退出编辑",draftRestore))return false
        isEditing=false;draftRestore=false;history.clear();syncHistory();editor=null;return true
    }
    // Viewing preferences can persist immediately, without accidentally committing course drafts.
    fun setZoom(zoom:Float)=setZoom(zoom,zoom)
    fun setZoom(x:Float,y:Float){
        if(loadError!=null||!x.isFinite()||!y.isFinite())return
        val xx=x.coerceIn(.65f,1.8f);val yy=y.coerceIn(.65f,1.8f)
        val changed=savedData.copy(settings=savedData.settings.copy(gridZoom=xx,gridZoomX=xx,gridZoomY=yy))
        runCatching {store.save(changed);savedData=changed;data=data.copy(settings=data.settings.copy(gridZoom=xx,gridZoomX=xx,gridZoomY=yy))}.onFailure {message="缩放设置保存失败"}
    }
    fun commit(newData:AppData,description:String,undo:Boolean=true,restore:Boolean=false):Boolean {
        if(loadError!=null&&!restore&&!draftRestore){message="数据读取失败，已阻止覆盖。请先导出原始数据，再确认恢复备份。";return false}
        if(isEditing){
            if(newData.courses.size>1000||newData.courses.any {it.error()!=null}){message="课程数据无效或超过 1000 节";return false}
            if(undo)history.record(data,newData)
            data=newData;draftRestore=draftRestore||restore;syncHistory();message=null;return true
        }
        if(!persist(newData,description,restore))return false
        history.clear();syncHistory();return true
    }
    private fun persist(candidate:AppData,description:String,restore:Boolean=false):Boolean {
        if(loadError!=null&&!restore){message="数据读取失败，已阻止覆盖";return false}
        return try {
            store.save(candidate);loadError=null;savedData=candidate;data=candidate
            val updated=runCatching {ReminderScheduler.reschedule(getApplication(),savedData);KejianWidgets.refreshAll(getApplication())}
            message=if(updated.isFailure)"课程已保存；系统提醒更新失败，请检查提醒权限"else null
            true
        }catch(e:Exception){message=e.message?:"保存失败，草稿仍保留";false}
    }
    fun undo(){if(!isEditing)return;val old=history.undoCandidate(data)?:return;data=old;history.didUndo();syncHistory();message=null}
    fun redo(){if(!isEditing)return;val next=history.redoCandidate(data)?:return;data=next;history.didRedo();syncHistory();message=null}
    fun reload(){if(isEditing)return;runCatching {store.load()}.onSuccess {data=it;savedData=it}.onFailure {message="读取失败：${it.message}"}}

    fun requestCaptcha(purpose:String){
        cloudBusy=true;cloudMessage=null;captcha=null
        viewModelScope.launch {runCatching {cloud.captcha(purpose)}.onSuccess {captcha=it}.onFailure {cloudMessage=it.message?:"验证码加载失败"};cloudBusy=false}
    }
    fun authenticate(register:Boolean,email:String,password:String,answer:String){
        val challenge=captcha
        if(register&&(challenge==null||challenge.purpose!="register")){cloudMessage="请先刷新并填写验证码";return}
        cloudBusy=true;cloudMessage=null
        viewModelScope.launch {
            var authenticated=false;var retryCaptcha=false
            runCatching {if(register)cloud.register(email,password,requireNotNull(challenge),answer)else cloud.login(email,password)}
                .onSuccess {cloudSession=it;captcha=null;cloudMessage="登录成功";authenticated=true}
                .onFailure {cloudMessage=it.message?:"登录失败";captcha=null;retryCaptcha=true}
            cloudBusy=false
            if(authenticated)refreshCloudSlots() else if(retryCaptcha&&register)requestCaptcha("register")
        }
    }
    private suspend fun reconcileCloud(){ return
        @Suppress("UNREACHABLE_CODE")
        if(cloudSession==null||isEditing)return
        cloudBusy=true;cloudMessage="正在同步课表"
        runCatching {cloud.fetchSchedule()}.onSuccess {remote->
            cloudSession=cloud.currentSession
            when {
                remote==null -> runCatching {cloud.uploadSchedule(savedData,null,true)}.onSuccess {cloudSession=it;cloudMessage="本机课表已安全保存到云端"}.onFailure {cloudMessage=it.message?:"云端保存失败"}
                DataJson.encode(remote.data,false)==DataJson.encode(savedData,false) -> {cloudSession=cloud.rememberRevision(remote.revision,remote.updatedAt);cloudMessage="课表已同步"}
                else -> {cloudConflict=CloudConflict(remote);cloudMessage="发现另一份云端课表，请选择要保留的版本"}
            }
        }.onFailure {cloudSession=cloud.currentSession;cloudMessage=it.message?:"暂时无法连接云端，本机课表不受影响"}
        cloudBusy=false
    }
    fun syncNow(){refreshCloudSlots()}
    fun syncCloudIfStale(){val now=System.currentTimeMillis();if(cloudSession!=null&&!isEditing&&now-lastCloudCheck>5*60_000){lastCloudCheck=now;refreshCloudSlots()}}
    fun refreshCloudSlots(){
        if(cloudSession==null||cloudBusy)return
        cloudBusy=true;cloudMessage="正在读取云存档"
        viewModelScope.launch {
            runCatching {cloud.refreshProfile()}.onSuccess {cloudSession=cloud.currentSession}
            val loaded=MutableList<CloudSchedule?>(3){null};var failure:String?=null
            for(slot in 1..cloudSlotCount)runCatching {cloud.fetchSchedule(slot)}.onSuccess {loaded[slot-1]=it}.onFailure {failure=it.message?:"云存档读取失败"}
            cloudSlots=loaded;cloudSession=cloud.currentSession;cloudMessage=failure?:"云存档已刷新";cloudBusy=false
        }
    }
    fun uploadLocalToSlot(slot:Int,overwrite:Boolean){
        if(slot !in 1..cloudSlotCount){cloudMessage="这个云存档槽位尚未解锁";return}
        val existing=cloudSlots.getOrNull(slot-1);if(existing!=null&&!overwrite){cloudMessage="该槽位已有存档，需要确认覆盖";return}
        cloudBusy=true;cloudMessage="正在上传到云存档 $slot"
        viewModelScope.launch {runCatching {cloud.uploadSchedule(savedData,existing?.revision,force=overwrite||existing==null,slot=slot);cloud.fetchSchedule(slot)}.onSuccess {value->cloudSlots=cloudSlots.toMutableList().also {it[slot-1]=value};cloudSession=cloud.currentSession;cloudMessage="本地存档已上传到槽位 $slot"}.onFailure {cloudMessage=it.message?:"上传失败"};cloudBusy=false}
    }
    fun downloadSlotToLocal(slot:Int,overwrite:Boolean){
        if(slot !in 1..cloudSlotCount){cloudMessage="这个云存档槽位尚未解锁";return}
        val remote=cloudSlots.getOrNull(slot-1)?:run {cloudMessage="这个槽位还没有云存档";return}
        if(savedData.courses.isNotEmpty()&&!overwrite){cloudMessage="本地已有课表，需要确认覆盖";return}
        cloudBusy=true;cloudMessage="正在下载云存档 $slot"
        viewModelScope.launch {runCatching {store.save(remote.data);savedData=remote.data;data=remote.data;loadError=null;ReminderScheduler.reschedule(getApplication(),savedData);KejianWidgets.refreshAll(getApplication())}.onSuccess {cloudMessage="槽位 $slot 已下载到本地"}.onFailure {cloudMessage=it.message?:"下载失败"};cloudBusy=false}
    }
    fun resolveCloudConflict(useCloud:Boolean){
        val conflict=cloudConflict?:return;cloudBusy=true;cloudMessage=null
        viewModelScope.launch {
            if(useCloud){runCatching {
                store.save(conflict.cloud.data);savedData=conflict.cloud.data;data=conflict.cloud.data;loadError=null
                ReminderScheduler.reschedule(getApplication(),savedData);KejianWidgets.refreshAll(getApplication())
                cloud.rememberRevision(conflict.cloud.revision,conflict.cloud.updatedAt)
            }.onSuccess {cloudSession=cloud.currentSession;cloudConflict=null;cloudMessage="已使用云端课表"}.onFailure {cloudMessage=it.message?:"云端课表应用失败"}}
            else runCatching {cloud.uploadSchedule(savedData,conflict.cloud.revision,true)}.onSuccess {cloudSession=it;cloudConflict=null;cloudMessage="已用本机课表更新云端"}.onFailure {cloudMessage=it.message?:"云端更新失败"}
            cloudBusy=false
        }
    }
    fun logoutCloud(){cloudBusy=true;viewModelScope.launch {cloud.logout();cloudSession=null;cloudConflict=null;cloudSlots=List(3){null};captcha=null;cloudMessage="已退出登录，本机课表仍然保留";cloudBusy=false}}
    fun deleteCloudAccount(password:String,answer:String){
        val challenge=captcha;if(challenge==null||challenge.purpose!="delete"){cloudMessage="请先完成注销验证码";return}
        cloudBusy=true;viewModelScope.launch {runCatching {cloud.delete(password,challenge,answer)}.onSuccess {cloudSession=null;cloudConflict=null;captcha=null;cloudMessage="账号和云端课表已删除，本机课表仍然保留"}.onFailure {cloudMessage=it.message?:"账号注销失败";captcha=null;requestCaptcha("delete")};cloudBusy=false}
    }
    private fun beginRecognition(document:Boolean=false,title:String="",kind:String=if(document)"document" else "image"):String {
        val requestId=UUID.randomUUID().toString()
        recognitionRequestId=requestId;recognitionIsDocument=document;recognitionBusy=true;recognitionPreview=null;message=null
        val task=AiTaskRecord(requestId,kind,title.ifBlank {if(kind=="document")"文件识别" else "图片识别"})
        aiTaskHistory=listOf(task)+aiTaskHistory
        val owner=aiHistoryOwner;aiHistoryWrites.trySend {aiHistoryStore.saveTask(owner,task)}
        return requestId
    }
    private fun recognitionIsCurrent(requestId:String)=recognitionRequestId==requestId&&requestId !in cancelledRecognitionRequests
    private fun finishRecognition(requestId:String) {
        if(recognitionRequestId==requestId){recognitionBusy=false;recognitionRequestId=null;recognitionIsDocument=false;recognitionJob=null}
    }
    /** Stop only schedule AI work. Audio transcription has its own service job and lifecycle. */
    fun stopRecognition() {
        if(!recognitionCanStop)return
        val owner=aiHistoryOwner
        val requestId=recognitionRequestId?:BackgroundTaskRuntime.documentRequestId?:return
        cancelledRecognitionRequests+=requestId
        if(cancelledRecognitionRequests.size>128)cancelledRecognitionRequests.remove(cancelledRecognitionRequests.first {it!=requestId})
        prefs.edit().putStringSet("cancelledRecognitionRequests",cancelledRecognitionRequests.toSet()).commit()
        val document=recognitionIsDocument||BackgroundTaskRuntime.documentRequestId==requestId
        recognitionRequestId=null;recognitionIsDocument=false;recognitionBusy=false;recognitionPreview=null
        recognitionJob?.cancel(CancellationException("Recognition stopped by user"));recognitionJob=null
        AiBackgroundWork.cancel(appContext,owner,requestId)
        if(document)BackgroundTaskService.cancelDocument(getApplication(),requestId)
        updateAiTask(requestId,"stopped")
        if(document)BackgroundTaskResults.takeDocument(appContext,owner)?.let {json->
            if(runCatching {JSONObject(json).optString("clientRequestId")==requestId}.getOrDefault(false))BackgroundTaskResults.acknowledgeDocument(appContext,requestId,owner)
        }
        aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,if(AppLanguage.english)"Recognition stopped. Your schedule is unchanged." else "已停止识别，日程没有改动。"))
        viewModelScope.launch {
            try {cloud.cancelAiRequest(requestId,owner);if(cloudSession?.userId==owner){cloud.refreshProfile();cloudSession=cloud.currentSession}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){/* A stopped request is never confirmed or applied locally. */}
        }
    }
    fun recognizeImage(uri:Uri){
        val owner=cloudSession?.userId?:run {message=recordingCopy("请先在设置中填写 API Key，再使用 AI 识别","Set your API Key to use AI recognition.");return}
        if(recognitionBusy||BackgroundTaskRuntime.documentRunning)return
        val requestId=beginRecognition()
        aiMessages=aiMessages+AiChatMessage(true,recordingCopy("已上传一张课表图片","Uploaded a schedule image"))+AiChatMessage(false,recordingCopy("图片任务会在后台继续，完成后请核对预览。","This image task continues in the background. Review its preview when ready."),pending=true)
        recognitionJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){
                    val resolver=appContext.contentResolver
                    val mime=resolver.getType(uri)?.lowercase()?:"image/jpeg"
                    val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0)else ""}.orEmpty()
                    val bytes=resolver.openInputStream(uri)?.use {input->
                        val output=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192)
                        while(true){currentCoroutineContext().ensureActive();val count=input.read(chunk);if(count<0)break;require(output.size()+count<=24*1024*1024){"图片超过 24 MB"};output.write(chunk,0,count)}
                        output.toByteArray()
                    }?:error("图片无法读取")
                    currentCoroutineContext().ensureActive()
                    aiWork.enqueue(owner,"image",requestId,JSONObject().put("bytes",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).put("mime",mime).put("name",name).put("colorOffset",nextCourseColor(data.courses)))
                }
                if(recognitionIsCurrent(requestId)&&cloudSession?.userId==owner)AiBackgroundWork.start(appContext)
                else AiBackgroundWork.cancel(appContext,owner,requestId)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){if(!recognitionIsCurrent(requestId))return@launch;updateAiTask(requestId,"failed",error.message.orEmpty());finishRecognition(requestId);message=recordingCopy("图片未能保存到本机任务，请检查存储空间后重试。","Could not save this image task locally. Check device storage and try again.")}
        }
    }
    private fun recognitionPreview(root:JSONObject):ImportPreview {
        val jobId=root.optString("jobId").takeIf {it.isNotBlank()}
        val parsed=ScheduleExchange.parse(root.optString("strictKj1"))
        val warnings=root.optJSONArray("warnings")?.let {array->(0 until array.length()).map {index->"提示：${array.optString(index)}"}}.orEmpty()
        require(parsed.courses.isNotEmpty()||parsed.deadlines.isNotEmpty()){warnings.firstOrNull()?.removePrefix("提示：")?:"没有识别到可以导入的日程"}
        return ImportPreview(distributeCourseColors(data.courses,parsed.courses),parsed.errors+warnings,jobId=jobId,aiTask=true,deadlines=parsed.deadlines)
    }
    fun recognizeDocument(uri:Uri){
        if(cloudSession==null){message="请先在设置中填写 API Key，再使用 AI 识别";return}
        if(recognitionBusy||BackgroundTaskRuntime.documentRunning)return
        if(BackgroundTaskResults.takeDocument(appContext,cloudSession?.userId)!=null){consumeBackgroundResults();message=recordingCopy("上一份文件的结果仍待本机保存，请先核对结果或检查存储空间。","The previous file result is waiting to be saved. Review it or check device storage first.");return}
        val resolver=getApplication<Application>().contentResolver
        try {resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){/* Some providers grant access only for this activity session. */}
        val mime=resolver.getType(uri).orEmpty()
        val name=try {resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->if(cursor.moveToFirst())cursor.getString(0) else null}}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}
        val requestId=beginRecognition(document=true,title=name.orEmpty())
        try {
            val intent=BackgroundTaskService.document(getApplication(),uri,name,mime,nextCourseColor(data.courses),requestId)
            ContextCompat.startForegroundService(getApplication(),intent)
            aiMessages=aiMessages+AiChatMessage(true,if(AppLanguage.english)"Selected file ${name?:""}".trim() else "已选择文件 ${name?:""}".trim())+AiChatMessage(false,if(AppLanguage.english)"Rendering the complete document on this device. It will upload automatically when ready, and you may leave this page." else "正在手机本地转换完整页面，完成后会自动上传；你可以离开此页面。",pending=true)
        }catch(cancelled:CancellationException){finishRecognition(requestId);throw cancelled}
        catch(error:Exception){updateAiTask(requestId,"failed",error.message.orEmpty());finishRecognition(requestId);message=if(AppLanguage.english)"Could not start file recognition: ${error.message}" else "无法开始文件识别：${error.message}"}
    }
    fun submitAiCommand(command:String){
        val text=command.trim();if(text.isBlank()||recognitionBusy||BackgroundTaskRuntime.documentRunning)return
        val owner=cloudSession?.userId?:run {message=recordingCopy("请先登录账号","Sign in first.");return}
        val requestId=beginRecognition(title=text,kind="command")
        val snapshot=AppData(data.courses,data.settings,data.deadlines)
        aiMessages=aiMessages+AiChatMessage(true,text)+AiChatMessage(false,recordingCopy("正在后台处理命令，结果仍需你预览确认。","Processing this command in the background. Changes still need your confirmation."),pending=true)
        recognitionJob=viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){aiWork.enqueue(owner,"command",requestId,JSONObject().put("command",text).put("scheduleLines",org.json.JSONArray(ScheduleExchange.encode(snapshot))).put("snapshot",JSONObject(DataJson.encode(snapshot,false))))}
                if(recognitionIsCurrent(requestId)&&cloudSession?.userId==owner)AiBackgroundWork.start(appContext)
                else AiBackgroundWork.cancel(appContext,owner,requestId)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){if(!recognitionIsCurrent(requestId))return@launch;updateAiTask(requestId,"failed",error.message.orEmpty());finishRecognition(requestId);message=recordingCopy("命令未能保存到本机任务，请检查存储空间后重试。","Could not save this command locally. Check device storage and try again.")}
        }
    }
    fun confirmAiPreview(jobId:String?,onConfirmed:()->Boolean){
        if(recognitionBusy||BackgroundTaskRuntime.documentRunning)return
        if(jobId==null){onConfirmed();return}
        recognitionBusy=true
        val owner=aiHistoryOwner
        viewModelScope.launch {try {cloud.confirmAiJob(jobId);currentCoroutineContext().ensureActive();cloudSession=cloud.currentSession;if(owner==aiHistoryOwner&&onConfirmed()){updateAiTask(jobId,"completed");aiMessages=aiMessages+AiChatMessage(false,"任务已完成！")}}
            catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){message=error.message?:"确认任务失败";cloud.uploadClientError(jobId,"ai_confirm",error.message?:"confirm failed")}
            finally{recognitionBusy=false}}
    }
    fun transcribeRecording(file:java.io.File,durationSeconds:Int,language:String=if(AppLanguage.english)"en"else"zh-CN",requirements:String=""){
        // A completed paid result waiting for local storage is retried locally, never submitted twice.
        if(BackgroundTaskResults.hasAudioForPath(appContext,file.absolutePath)){
            consumeBackgroundResults();return
        }
        val profile=cloudSession?.profile
        if(cloudSession==null){audioMessage=if(AppLanguage.english)"Set your API Key to use AI transcription and summaries." else "请先在设置中填写 API Key，再使用 AI 转写总结";return}
        if(profile?.audioSummary!=true){audioMessage=if(AppLanguage.english)"AI transcription and summaries require Plus or Pro. Standard users can still save and attach recordings." else "AI 转写总结属于 Plus / Pro 权益；普通用户仍可保存和关联原始录音";return}
        if(audioBusy||BackgroundTaskRuntime.audioRunning)return
        val pending=BackgroundTaskResults.pendingAudio(appContext)
        if(pending!=null&&pending.path!=file.absolutePath){audioMessage=if(AppLanguage.english)"Continue the unfinished recording first. Other files remain saved in your library." else "请先继续上一段尚未完成的录音任务，其他录音已安全保存在列表中。";return}
        captureLocalRecording(file,durationSeconds)
        RecordingLibrary.setState(appContext,file.absolutePath,"preparing")
        val estimate=estimatedAudioProcessingSeconds(durationSeconds)
        audioBusy=true;audioMessage=if(AppLanguage.english)"Processing in the background. Historical estimate: ${friendlyDuration(estimate)}; queueing and detailed notes may take longer. You will be notified when ready; server audio is then deleted." else "已转入后台，历史估算约 ${friendlyDuration(estimate)}；排队与详细笔记复核可能耗时更久。完成后会通知你，服务器随后删除音频。"
        try {
            ContextCompat.startForegroundService(getApplication(),BackgroundTaskService.audio(getApplication(),file,durationSeconds,estimate,language,requirements))
        }catch(error:Exception){
            audioBusy=false
            audioMessage=if(AppLanguage.english)"Could not start the background task. Keep the app open and try again. Your recording is still saved locally." else "暂时无法启动后台任务，请保持应用打开后重试。录音仍保留在本机。"
            BackgroundTaskRuntime.audioFinished(audioMessage.orEmpty(),AudioWorkStage.FAILED)
        }
    }
    fun consumeBackgroundResults(){
        val context=getApplication<Application>()
        refreshRecordingLibrary()
        val resultOwner=cloudSession?.userId
        BackgroundTaskResults.takeAudio(context,resultOwner)?.let {(line,path)->
            try {
                val result=NoteExchange.parseLine(line).getOrThrow()
                val existing=savedData.notes.firstOrNull {it.audioPath==path}
                val note=mergeRecordingResult(existing,result,path)
                if(saveLessonNote(note,if(AppLanguage.english)"Recording notes saved" else "录音笔记已保存")){
                    audioMessage=if(AppLanguage.english)"Transcript and detailed notes are saved in your recording library." else "转写与详细总结已保存到对应录音文件。"
                    RecordingLibrary.setState(context,path,"completed")
                    RecordingDraftStore.clear(context,path)
                    BackgroundTaskResults.acknowledgeAudio(context,path,line,resultOwner)
                }else {
                    RecordingLibrary.setState(context,path,"save_pending",if(AppLanguage.english)"The completed notes are kept. Free device storage, then retry saving; no new transcription will be charged." else "已保留完成的笔记。请释放存储空间后重试保存，不会重新转写或扣除时长。")
                }
            }catch(error:Exception){
                audioMessage=if(AppLanguage.english)"The notes could not be saved. The result and original audio are kept for recovery." else "暂时无法保存笔记，结果与原始音频均已保留，可稍后重试。"
                RecordingLibrary.setState(context,path,"save_pending",audioMessage.orEmpty())
                BackgroundTaskRuntime.audioFinished(audioMessage.orEmpty(),AudioWorkStage.FAILED)
            }
            audioBusy=false
            viewModelScope.launch {runCatching {cloud.refreshProfile()}.onSuccess {if(cloudSession?.userId==resultOwner&&cloud.currentSession?.userId==resultOwner)cloudSession=cloud.currentSession}}
        }
        BackgroundTaskResults.takeDocument(context,resultOwner)?.let {json->
            val root=try {JSONObject(json)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){null}
            val requestId=root?.optString("clientRequestId")?.takeIf {it.isNotBlank()}
            if(acceptDocumentResult(requestId)){
                try {
                    require(root!=null){if(AppLanguage.english)"The recognition result is invalid." else "文件识别结果无效"}
                    recognitionPreview=recognitionPreview(root)
                    val preview=recognitionPreview!!
                    val old=aiTaskHistory.firstOrNull {it.id==requestId}?:aiHistoryStore.tasks(aiHistoryOwner).firstOrNull {it.id==requestId}?:error("Missing original task")
                    val record=old.copy(status="preview",updatedAt=System.currentTimeMillis(),jobId=preview.jobId,snapshot=DataJson.encode(AppData(preview.courses,data.settings,preview.deadlines),false))
                    aiTaskHistory=listOf(record)+aiTaskHistory.filterNot {it.id==record.id}
                    val owner=aiHistoryOwner
                    aiHistoryWrites.trySend {
                        aiHistoryStore.saveTask(owner,record)
                        BackgroundTaskResults.acknowledgeDocument(context,record.id,owner)
                    }
                    aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,if(AppLanguage.english)"File processing is complete. Review the recognition preview before applying it." else "文件处理完成，已生成识别预览，请核对后确认。"))
                }catch(cancelled:CancellationException){throw cancelled}
                catch(error:Exception){updateAiTask(requestId,"failed",error.message.orEmpty());aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,error.message?:if(AppLanguage.english)"The recognition result is invalid." else "文件识别结果无效",error=true))}
                finishDocumentResult(requestId)
            }
        }
        BackgroundTaskResults.takeError(context,"audio")?.let {audioMessage=it;audioBusy=false}
        BackgroundTaskResults.takeDocumentError(context)?.let {(requestId,error)->
            if(acceptDocumentResult(requestId)){
                updateAiTask(requestId,"failed",error)
                aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,error,error=true))
                finishDocumentResult(requestId)
            }
        }
    }
    private fun acceptDocumentResult(requestId:String?):Boolean {
        if(requestId!=null&&requestId in cancelledRecognitionRequests)return false
        // A completed background task may outlive logout. Never show another account's result.
        if(requestId==null||aiTaskHistory.none {it.id==requestId}&&!aiHistoryStore.ownsTask(aiHistoryOwner,requestId))return false
        val activeId=recognitionRequestId
        return activeId==null||(recognitionIsDocument&&activeId==requestId)
    }
    private fun finishDocumentResult(requestId:String?) {
        if(recognitionRequestId==null){recognitionBusy=false;recognitionIsDocument=false}
        else if(requestId!=null)finishRecognition(requestId)
    }
    fun captureLocalRecording(file:java.io.File,seconds:Int){
        if(seconds<10||!file.isFile)return
        if(savedData.notes.any {it.audioPath==file.absolutePath}){RecordingLibrary.acknowledge(appContext,file.absolutePath);return}
        runCatching {RecordingLibrary.capture(appContext,file,seconds)}.onSuccess {refreshRecordingLibrary()}.onFailure {
            audioMessage=if(AppLanguage.english)"Your audio is safe, but the library could not be updated. Free some device storage and reopen Record." else "音频仍在本机，但录音列表暂时无法更新，请释放存储空间后重新打开录音页。"
        }
    }
    fun refreshRecordingLibrary(){
        RecordingLibrary.inbox(appContext).forEach {note->
            val path=note.audioPath?:return@forEach
            if(savedData.notes.any {it.audioPath==path}||saveLessonNote(note,if(AppLanguage.english)"Recording saved" else "录音已保存")){
                RecordingLibrary.acknowledge(appContext,path);RecordingDraftStore.clear(appContext,path)
            }
        }
    }
    fun linkRecording(noteId:String,occurrence:Occurrence?):Boolean{
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return false
        return saveLessonNote(note.copy(courseId=occurrence?.course?.id,occurrenceDate=occurrence?.date),if(AppLanguage.english)"Recording link updated" else "录音关联已更新")
    }
    fun deleteRecording(noteId:String):Boolean {
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return false
        val path=note.audioPath
        try {
            check(loadError==null)
            if(aiWork.hasPendingNote(noteId)||resummaryEnqueuingNoteId==noteId||mindMapEnqueuingNoteId==noteId||
                insightBusyScope?.contains(":$noteId:")==true||path!=null&&(BackgroundTaskResults.pendingAudio(appContext)?.path==path||BackgroundTaskResults.hasAnyAudioForPath(appContext,path))){
                message=recordingCopy("这份录音还有待处理任务，请先完成任务再删除。","This recording has pending work. Finish it before deleting.");return false
            }
            val source=path?.let {java.io.File(it).canonicalFile}
            val root=java.io.File(appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),"Recordings").canonicalFile
            val owned=source!=null&&source.parentFile==root&&savedData.notes.none {it.id!=noteId&&it.audioPath==path}
            val previous=savedData
            val candidate=previous.copy(notes=previous.notes.filterNot {it.id==noteId})
            // Save metadata before removing audio; a storage failure must never lose the recording.
            store.save(candidate)
            if(owned&&source!!.exists()&&!source.delete()){
                store.save(previous);message=recordingCopy("音频暂时无法删除，请稍后重试。","Could not delete the audio. Try again later.");return false
            }
            savedData=candidate;data=data.copy(notes=candidate.notes)
            runCatching {RecordingLibrary.forget(appContext,note);recordingInsights.deleteNote(noteId);aiWork.forgetNote(noteId)}
            if(path!=null){RecordingDraftStore.clear(appContext,path);if(RecordingRuntime.completedFile?.absolutePath==path&&!RecordingRuntime.recording)RecordingRuntime.clear()}
            if(audioNotePreview?.id==noteId)audioNotePreview=null
            runCatching {KejianWidgets.refreshAll(getApplication())}
            message=recordingCopy("录音已删除","Recording deleted");return true
        }catch(_:Exception){message=recordingCopy("暂时无法删除录音，请检查存储空间后重试。","Could not delete the recording. Check storage and try again.");return false}
    }
    fun removeRecordingAudio(noteId:String):Boolean{
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return false
        val path=note.audioPath?:return false
        if(BackgroundTaskResults.pendingAudio(appContext)?.path==path){audioMessage=if(AppLanguage.english)"Finish the pending task before deleting its audio." else "请先完成这段录音的待处理任务，再删除音频。";return false}
        if(note.summary.isBlank()&&note.transcript.isBlank())return false
        return try {
            val mediaRoot=java.io.File(appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC),"Recordings").canonicalFile
            val source=java.io.File(path).canonicalFile
            if(source.exists()&&!source.path.startsWith(mediaRoot.path+java.io.File.separator)){
                audioMessage=if(AppLanguage.english)"This audio is outside Kejian's private recording folder. Manage it through Files." else "此音频位于课间录音文件夹外，请通过文件管理器操作。";return false
            }
            // Persist the text-only note first; failed storage writes never delete original media.
            if(!saveLessonNote(note.copy(audioPath=null),if(AppLanguage.english)"Notes kept; local audio removed" else "已保留笔记并移除本地音频"))return false
            if(source.exists()&&!source.delete()){
                saveLessonNote(note,if(AppLanguage.english)"Could not delete the original audio. It remains attached to this recording." else "无法删除原始音频，音频仍保留在此录音中。")
                return false
            }
            true
        }catch(_:Exception){audioMessage=if(AppLanguage.english)"Could not remove the audio. Your text notes remain safe." else "暂时无法移除音频，文字笔记不受影响。";false}
    }
    /** Recovery schedules a system job, never a foreground service from a background callback. */
    fun resumeAiBackgroundWork(){
        // Cache expiry applies to signed-out accounts too, without reading or transmitting their inputs.
        viewModelScope.launch(Dispatchers.IO){runCatching {aiWork.prune()}}
        val owner=cloudSession?.userId?:return
        if(aiWork.tasks(owner).any {it.state in setOf("queued","running","retry")&&it.attempts<3||it.state=="cancelled"&&it.code=="USER_STOPPED"&&aiBackgroundRetrySafe(it.kind,it.createdAt)})AiBackgroundWork.schedule(appContext)
        consumeAiBackgroundResults()
    }
    fun resumeAiTask(requestId:String){
        val owner=cloudSession?.userId?:return
        val task=aiWork.tasks(owner).firstOrNull {it.requestId==requestId}?:return
        try {aiWork.retry(task);AiBackgroundWork.start(appContext);consumeAiBackgroundResults()}
        catch(_:Exception){message=recordingCopy("这个任务的安全恢复窗口已结束，请重新发起；新任务可能使用额度。","This task's safe recovery window has ended. A new request may use your allowance.")}
    }
    fun restoreAiTaskPreview(requestId:String){
        if(recognitionBusy||BackgroundTaskRuntime.documentRunning)return
        val task=aiTaskHistory.firstOrNull {it.id==requestId&&it.status=="preview"}?:return
        if(task.snapshot.isBlank())return
        runCatching {DataJson.decode(task.snapshot)}.onSuccess {snapshot->
            recognitionPreview=ImportPreview(snapshot.courses,emptyList(),backup=if(task.kind=="command")data.copy(courses=snapshot.courses,deadlines=snapshot.deadlines)else null,jobId=task.jobId,aiTask=true,deadlines=snapshot.deadlines)
        }.onFailure {message=recordingCopy("本机预览无法读取，原日程未改变。","Could not read the saved preview. Your schedule is unchanged.")}
    }
    fun consumeAiBackgroundResults(){
        if(aiWorkConsumeJob?.isActive==true){aiWorkConsumeAgain=true;return}
        val owner=cloudSession?.userId?:return
        aiWorkConsumeJob=viewModelScope.launch {
            try {
                val tasks=withContext(Dispatchers.IO){aiWork.tasks(owner)}
                if(cloudSession?.userId!=owner)return@launch
                val schedule=tasks.firstOrNull {it.kind in setOf("image","command")&&it.busy&&it.requestId !in cancelledRecognitionRequests}
                if(schedule!=null){recognitionBusy=true;recognitionRequestId=schedule.requestId;recognitionIsDocument=false}
                val activeMap=tasks.firstOrNull {it.kind=="mindmap"&&it.busy}
                val latestMap=tasks.lastOrNull {it.kind=="mindmap"}
                val latestInsight=tasks.lastOrNull {it.kind=="insight"}
                resummaryBusyNoteId=resummaryEnqueuingNoteId?:tasks.firstOrNull {it.kind=="resummary"&&(it.busy||it.state=="result")}?.noteId
                mindMapBusyNoteId=mindMapEnqueuingNoteId?:activeMap?.noteId
                val insight=tasks.firstOrNull {it.kind=="insight"&&it.busy}
                insightBusyScope=insight?.let {"${it.owner}:${it.noteId}:${it.digest}"}
                insightBusyTurnId=insight?.let {withContext(Dispatchers.IO){runCatching {aiWork.input(it).getString("turnId")}.getOrNull()}}
                for(task in tasks){
                    if(cloudSession?.userId!=owner||CloudAccountClient(appContext).currentSession?.userId!=owner)break
                    if(task.requestId in cancelledRecognitionRequests){if(task.state!="cancelled")withContext(Dispatchers.IO){aiWork.cancel(task)};continue}
                    if(task.state=="result"){
                        val response=withContext(Dispatchers.IO){aiWork.result(task)}
                        when(task.kind){
                            "resummary"->{
                                val note=savedData.notes.firstOrNull {it.id==task.noteId}?:continue
                                val updated=applyRegeneratedSummary(note,response)
                                resummaryMessageNoteId=note.id
                                if(!saveLessonNote(updated,recordingCopy("重点笔记已更新","Study notes updated"))){
                                    resummaryMessage=recordingCopy("总结已生成但暂未保存，请检查存储空间后重试。结果仍在本机，不会重复生成。","Notes were generated but could not be saved. Check storage and retry; the result is kept locally.");continue
                                }
                                val changed=note.summary!=updated.summary||note.keyPoints!=updated.keyPoints||note.actionItems!=updated.actionItems
                                resummaryMessage=if(changed)recordingCopy("新总结已保存，已切换到课堂笔记。原逐字稿与课程关联保持不变。","New notes saved. Showing the updated notes; transcript and event link are unchanged.")else if(response.optBoolean("unchanged"))recordingCopy("本次结果与已有总结相同，未扣 token 或免费次数。可修改上方要求后重新生成。","The result matches the existing notes. No tokens or free attempts were used. Change your instructions to try again.")else recordingCopy("重新总结已完成，内容与当前笔记相同。可修改上方要求后重新生成。","Regeneration completed; the content matches your current notes. Change your instructions to try again.")
                                resummaryRevision++
                            }
                            "image","command"->{
                                val preview=if(task.kind=="image")recognitionPreview(response)else {
                                    val parsed=ScheduleExchange.parse(response.getString("strictKj1"));require(parsed.errors.isEmpty())
                                    val snapshot=DataJson.decode(response.getJSONObject("clientSnapshot").toString())
                                    val changed=snapshot.courses!=data.courses||snapshot.deadlines!=data.deadlines
                                    val warnings=(if(changed)listOf(recordingCopy("任务期间日程已改变，请仔细核对修改前、修改后的表格。","Your schedule changed while this task ran. Check the Before and After grids carefully."))else emptyList())+response.optJSONArray("warnings").let {a->if(a==null)emptyList()else(0 until a.length()).map {a.optString(it)}.filter {it.isNotBlank()}}
                                    ImportPreview(parsed.courses,warnings,data.copy(courses=parsed.courses,deadlines=parsed.deadlines),jobId=response.getString("jobId"),aiTask=true,deadlines=parsed.deadlines)
                                }
                                val old=aiTaskHistory.firstOrNull {it.id==task.requestId}?:AiTaskRecord(task.requestId,task.kind,recordingCopy("已恢复的 AI 任务","Recovered AI task"))
                                val record=old.copy(status="preview",updatedAt=System.currentTimeMillis(),jobId=preview.jobId,snapshot=DataJson.encode(AppData(preview.courses,preview.backup?.settings?:data.settings,preview.deadlines),false))
                                // Flush earlier queued history writes before saving the recovered result; only then discard its journal copy.
                                val barrier=kotlinx.coroutines.CompletableDeferred<Unit>();aiHistoryWrites.send {barrier.complete(Unit)};barrier.await()
                                withContext(Dispatchers.IO){aiHistoryStore.saveTask(owner,record)}
                                if(cloudSession?.userId!=owner)break
                                aiTaskHistory=listOf(record)+aiTaskHistory.filterNot {it.id==record.id}
                                if(recognitionRequestId==null||recognitionRequestId==task.requestId){
                                    recognitionPreview=preview;finishRecognition(task.requestId)
                                    aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,recordingCopy("后台任务已生成预览，请核对后确认。","Your background task is ready. Review its preview before applying changes.")))
                                }
                            }
                            "mindmap"->{
                                val note=savedData.notes.firstOrNull {it.id==task.noteId&&noteContentDigest(it)==task.digest}
                                if(note==null){if(activeMap==null&&latestMap?.id==task.id)mindMapMessage=recordingCopy("原笔记已改变，旧导图结果暂存于本机，不会覆盖新内容。","The source note changed. The old map result is kept locally and will not overwrite it.");continue}
                                RecordingLibrary.saveMap(appContext,note,LessonMindMap.parse(response,task.digest))
                                if(activeMap==null&&latestMap?.id==task.id)mindMapMessage=null
                            }
                            "insight"->{
                                // The original question's owner/digest stays valid even if the user edited or removed the note while waiting.
                                val pending=NoteInsightTurn(response.getString("turnId"),owner,task.noteId,task.digest,task.requestId,"","[]")
                                withContext(Dispatchers.IO){recordingInsights.complete(pending,NoteInsightAnswer.parse(response.getJSONObject("answer")))}
                                if(insight==null&&latestInsight?.id==task.id){insightMessage=null;insightMessageScope=null}
                            }
                        }
                        withContext(Dispatchers.IO){aiWork.consume(task)}
                        val fresh=CloudAccountClient(appContext).currentSession
                        if(cloudSession?.userId==owner&&fresh?.userId==owner)cloudSession=fresh
                    }else if(task.state in setOf("retry","failed")){
                        val retry=task.state=="retry"
                        val notice=when {
                            !retry&&task.details.isNotBlank()&&task.code in setOf("INPUT_REQUIRED","AUTH_REQUIRED","QUOTA_REQUIRED","INPUT_TOO_LARGE","MODEL_UNAVAILABLE","failed")->task.details.take(500)
                            retry&&task.code=="running"->recordingCopy("服务器仍在处理，正在等待结果；不会重复提交。","The server is still working. Waiting for the original result without resubmitting.")
                            retry&&task.code=="RATE_LIMITED"->recordingCopy("AI 服务繁忙，稍后有限重试；原日程不变。","AI is busy. Retrying shortly; your schedule is unchanged.")
                            retry->recordingCopy("暂未收到结果，正在恢复原任务；不会重复添加日程。","Recovering the original request. Events will not be added twice.")
                            else->recordingCopy("任务未完成，已停止自动重试，原日程未改变。请检查网络或稍后重新发送。","The task did not finish. Automatic retries have stopped and your schedule is unchanged. Check your connection or try again later.")
                        }
                        when(task.kind){
                            "resummary"->if(tasks.lastOrNull {it.kind=="resummary"}?.id==task.id){resummaryMessageNoteId=task.noteId;resummaryMessage=if(task.code=="MODEL_UNAVAILABLE")recordingCopy("总结模型暂不可用，原笔记保留，本次未扣费。服务恢复后点击重新总结，会重新发送完整逐字稿。","The summary model is unavailable. Your notes are kept and this attempt was not charged. Regenerate after recovery to send the full transcript again.")else recordingCopy("重新总结未完成，原笔记仍保留。网络重试恢复原任务；任务失败后再次生成会重新发送完整逐字稿。","Regeneration did not finish. Network retries recover the same task; after a failed task, regeneration sends the full transcript again.")}
                            "image","command"->{
                                if(aiTaskHistory.firstOrNull {it.id==task.requestId}?.status!=task.state){
                                    updateAiTask(task.requestId,task.state,notice)
                                    aiMessages=resolveAiPending(aiMessages,AiChatMessage(false,notice,error=!retry))
                                    if(!retry&&task.code=="INPUT_REQUIRED")aiClarification=owner to notice
                                }
                                if(!task.busy)finishRecognition(task.requestId)
                            }
                            "mindmap"->{
                                if(activeMap?.id==task.id||activeMap==null&&latestMap?.id==task.id)mindMapMessage=if(task.code=="LOCAL_CACHE_EXPIRED")recordingCopy("导图恢复窗口已结束。重新生成将使用 AI token。","The map recovery window has ended. Generating a new map uses AI tokens.")else if(task.code=="MODEL_UNAVAILABLE")recordingCopy("导图模型暂不可用，笔记不受影响，本次未扣费。服务恢复后可重新生成。","The mind map model is unavailable. Your notes are unchanged and this attempt was not charged. Generate again after recovery.")else if(!retry)recordingCopy("思维导图生成失败，笔记已保留，本次不扣额度。可以重新生成。","Mind map generation failed. Notes are kept; no allowance was used. You can generate again.")else notice
                                if(latestMap?.id==task.id&&task.code in setOf("MINDMAP_FAILED","MINDMAP_CANCELLED","MODEL_UNAVAILABLE","NOTE_PROVIDER_FAILED","LOCAL_CACHE_EXPIRED"))savedData.notes.firstOrNull {it.id==task.noteId&&noteContentDigest(it)==task.digest}?.let {RecordingLibrary.clearMapRequest(appContext,it,owner)}
                            }
                            "insight"->{
                                val note=savedData.notes.firstOrNull {it.id==task.noteId&&noteContentDigest(it)==task.digest}
                                if(note!=null)withContext(Dispatchers.IO){recordingInsights.turns(owner,note).firstOrNull {it.requestId==task.requestId&&it.answer==null}?.let {recordingInsights.failed(it,task.code)}}
                                if(insight?.id==task.id||insight==null&&latestInsight?.id==task.id){
                                    insightMessageScope="$owner:${task.noteId}:${task.digest}"
                                    insightMessage=if(task.code in setOf("INSIGHTS_EXPIRED","LOCAL_CACHE_EXPIRED"))recordingCopy("在线回答的恢复窗口已结束。重新提问会使用 AI token；已保存在本机的回答不受影响。","The online answer recovery window has ended. Asking again uses AI tokens; locally saved answers remain available.")else if(task.code=="INSIGHTS_FAILED")recordingCopy("回答生成失败或引用未通过核对，本次未扣额度。问题已保留，可点击重新提问。","The answer failed or its sources could not be verified. No tokens were charged. Your question is saved; you can ask again.")else if(!retry)recordingCopy("暂时无法取回回答，问题与请求编号已保存。请重试恢复同一请求。","The answer could not be retrieved. Your question and request ID are saved. Retry to recover the same request.")else recordingCopy("回答暂未取回，已保留问题，正在恢复同一个请求。","Your question is saved. Recovering the same answer request.")
                                }
                            }
                        }
                    }
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){message=recordingCopy("后台结果仍保存在本机，暂时无法读取或保存，请检查存储空间后重新打开应用。","Background results are kept locally. Check device storage and reopen the app to finish saving them.");resummaryBusyNoteId?.let {resummaryMessageNoteId=it;resummaryMessage=message}}
            finally {
                if(cloudSession?.userId==owner){
                    resummaryBusyNoteId=resummaryEnqueuingNoteId?:withContext(Dispatchers.IO){runCatching {aiWork.tasks(owner).firstOrNull {it.kind=="resummary"&&it.busy}?.noteId}.getOrNull()}
                }
                aiWorkConsumeJob=null
                if(aiWorkConsumeAgain){aiWorkConsumeAgain=false;consumeAiBackgroundResults()}
            }
        }
    }
    suspend fun quoteRecordingSummary(note:LessonNote,language:String,requirements:String):JSONObject{
        val owner=cloudSession?.userId?:error("Sign in first")
        return cloud.recordingSummary(summaryRequest(note,language,requirements),owner,true)
    }
    fun regenerateRecordingSummary(noteId:String,language:String,requirements:String,allowCharge:Boolean){
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return
        val owner=cloudSession?.userId?:return
        if(resummaryBusyNoteId!=null)return
        resummaryEnqueuingNoteId=noteId;resummaryBusyNoteId=noteId;resummaryMessageNoteId=noteId;resummaryMessage=null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){
                    val prior=aiWork.tasks(owner).lastOrNull {it.kind=="resummary"&&it.noteId==noteId&&it.state in setOf("queued","running","retry","result")}
                    if(prior==null){val body=summaryRequest(note,language,requirements,allowCharge);aiWork.enqueue(owner,"resummary",body.getString("requestId"),body,note.id,noteContentDigest(note))}
                    else if(prior.state=="retry")aiWork.retry(prior)
                }
                if(cloudSession?.userId==owner)AiBackgroundWork.start(appContext)
            }catch(c:CancellationException){throw c}catch(_:Exception){resummaryBusyNoteId=null;resummaryMessage=recordingCopy("无法保存任务，请检查存储空间。","Could not save the task. Check device storage.")}
            finally {resummaryEnqueuingNoteId=null;consumeAiBackgroundResults()}
        }
    }
    fun generateRecordingMindMap(noteId:String,onReady:()->Unit){
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return
        if(note.summary.isBlank()){mindMapMessage=recordingCopy("请先完成转写与总结。","Complete transcription and notes first.");return}
        RecordingLibrary.loadMap(appContext,note)?.let {onReady();return}
        val owner=cloudSession?.userId?:return
        val prior=aiWork.tasks(owner).firstOrNull {it.kind=="mindmap"&&it.noteId==noteId&&it.digest==noteContentDigest(note)&&it.state in setOf("queued","running","retry","result")}
        if(prior==null&&cloudSession?.profile?.aiAssistant!=true){mindMapMessage=recordingCopy("思维导图使用个人 API Key，按服务商计费，请登录 Plus 或 Pro。","Mind maps use your membership AI tokens. Sign in with Plus or Pro.");return}
        if(mindMapBusyNoteId!=null)return
        mindMapEnqueuingNoteId=noteId;mindMapBusyNoteId=noteId;mindMapMessage=null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){aiWork.enqueue(owner,"mindmap",prior?.requestId?:RecordingLibrary.mapRequestId(appContext,note,owner),JSONObject().put("note",NoteExchange.encodeNote(note,false)),note.id,noteContentDigest(note))}
                if(cloudSession?.userId==owner)AiBackgroundWork.start(appContext)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){mindMapBusyNoteId=null;mindMapMessage=recordingCopy("任务未能保存，请检查本机存储空间。已有请求会保留原编号。","Could not save the task. Check device storage. Existing requests keep their original ID.")}
            finally {mindMapEnqueuingNoteId=null;consumeAiBackgroundResults()}
        }
    }
    fun recordingInsightMessage(owner:String,note:LessonNote):String?=insightMessage.takeIf {"$owner:${note.id}:${noteContentDigest(note)}"==insightMessageScope}
    fun askRecordingInsight(noteId:String,question:String,retryTurnId:String?=null,confirmFreshRequest:Boolean=false){
        val note=savedData.notes.firstOrNull {it.id==noteId}?:return
        val owner=cloudSession?.userId?:return
        if(insightBusyScope!=null)return
        insightMessageScope="$owner:$noteId:${noteContentDigest(note)}";insightMessage=null
        if(note.summary.isBlank()||retryTurnId==null&&cloudSession?.profile?.aiAssistant!=true){
            insightMessage=if(AppLanguage.english)"Complete your notes and sign in with Plus or Pro to ask a question."else"请先完成笔记，并登录 Plus 或 Pro 账号后提问。";return
        }
        val digest=noteContentDigest(note)
        val mayStartNewRequest=cloudSession?.profile?.aiAssistant==true
        insightBusyScope="$owner:$noteId:$digest"
        viewModelScope.launch {
            var turn:NoteInsightTurn?=null
            try {
                val pending=withContext(Dispatchers.IO){if(retryTurnId==null)recordingInsights.create(owner,note,question)else recordingInsights.retry(owner,note,retryTurnId,allowNewRequest=mayStartNewRequest,confirmFreshRequest=confirmFreshRequest)}
                turn=pending;insightBusyTurnId=pending.id
                if(!insightCanApply(owner,cloudSession?.userId,cloud.currentSession?.userId,digest,savedData.notes.firstOrNull {it.id==noteId}))return@launch
                withContext(Dispatchers.IO){aiWork.enqueue(owner,"insight",pending.requestId,JSONObject().put("note",NoteExchange.encodeNote(note,false)).put("turnId",pending.id).put("question",pending.question).put("history",pending.history).put("turnCreatedAt",pending.createdAt).put("webSearch",pending.webSearch),note.id,digest)}
                if(cloudSession?.userId==owner)AiBackgroundWork.start(appContext)
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                val code=(error as? NoteInsightRequestException)?.code.orEmpty()
                turn?.let {pending->withContext(Dispatchers.IO){runCatching {recordingInsights.failed(pending,code)}}}
                if(cloudSession?.userId==owner){
                    insightMessage=when {
                        error is NoteInsightRequestException&&error.status==402->if(AppLanguage.english)"Your AI token allowance is not sufficient. This attempt was not charged."else"会员 AI token 额度不足，本次未扣费。"
                        code=="INSIGHTS_REASK_REQUIRED"->if(AppLanguage.english)"The retry window has ended. Use Ask again only if you want a new request that uses AI tokens."else"安全重试窗口已结束。如果需要再次生成，请主动点击重新提问，新请求会使用 AI token。"
                        turn==null->if(AppLanguage.english)"Could not save this question on the device, so nothing was sent. Check storage space and try again."else"无法在本机保存问题，尚未发送请求。请检查存储空间后重试。"
                        code=="INSIGHTS_RUNNING"->if(AppLanguage.english)"This answer is still being prepared. Retry retrieves the same task without creating another charge."else"回答仍在生成。稍后重试会查询同一任务，不会重新创建扣费任务。"
                        code=="INSIGHTS_EXPIRED"->if(AppLanguage.english)"The server's answer cache has expired. Asking again is a new request and uses AI tokens. Answers already saved on this device remain available."else"服务器中的回答缓存已过期。再次提问是新请求，会使用 AI token；已保存在本机的回答仍可阅读。"
                        code in setOf("INSIGHTS_FAILED","INSIGHTS_CANCELLED")->if(AppLanguage.english)"The previous task has ended. You can explicitly ask again using your AI tokens."else"上次任务已结束，可主动再次提问，使用会员 AI token。"
                        else->if(AppLanguage.english)"The answer could not be retrieved. Your question is saved locally; retry keeps the same request to prevent duplicate charges."else"暂时无法取得回答。问题已保存在本机，重试沿用同一请求，避免重复扣费。"
                    }
                }
            }finally {consumeAiBackgroundResults()}
        }
    }
    fun clearAudioPreview(){audioNotePreview=null}
    fun saveLessonNote(note:LessonNote,description:String="课堂资料已保存"):Boolean {
        if(loadError!=null){audioMessage=if(AppLanguage.english)"Could not read local data. The original recording is kept; export or recover the data before saving." else "本地数据读取异常，录音已保留，请先导出或恢复数据。";return false}
        val candidate=savedData.copy(notes=savedData.notes.filterNot {it.id==note.id}+note)
        return try {
            // Notes persist independently of an unsaved timetable draft.
            store.save(candidate);savedData=candidate;data=data.copy(notes=candidate.notes)
            audioNotePreview=null;audioMessage=description
            runCatching {KejianWidgets.refreshAll(getApplication())}
            true
        }catch(error:Exception){audioMessage=if(AppLanguage.english)"Could not save notes. The original audio and previous data are kept. Check available storage." else "笔记暂时无法保存，原录音和之前的数据均已保留，请检查存储空间。";false}
    }
    fun consumeRecognitionPreview():ImportPreview?=recognitionPreview.also {recognitionPreview=null}
}

/** Historical estimate from the older 56-minute pipeline; v2.1 multi-pass notes are not yet hour-benchmarked. */
fun estimatedAudioProcessingSeconds(audioSeconds:Int):Int=(20+audioSeconds*.09).roundToInt().coerceAtLeast(30)
fun friendlyDuration(seconds:Int):String=when {
    seconds<60->if(AppLanguage.english)"$seconds seconds" else "${seconds} 秒"
    else->if(AppLanguage.english)"${ceil(seconds/60.0).toInt()} minutes" else "${ceil(seconds/60.0).toInt()} 分钟"
}

data class AiChatMessage(val fromUser:Boolean,val text:String,val pending:Boolean=false,val error:Boolean=false,val id:String=UUID.randomUUID().toString(),val createdAt:Long=System.currentTimeMillis(),val taskId:String?=null)

class KejianApplication: Application() {
    override fun onCreate(){
        super.onCreate();ProductAccess.initialize(this);CrashDiagnostics.install(this);runCatching {ReminderScheduler.createChannel(this)}
        // Do not decode backgrounds or rebuild RemoteViews on the process startup thread.
        // Activity/receivers refresh them after the first stable frame instead.
    }
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration){super.onConfigurationChanged(newConfig);runCatching {KejianWidgets.refreshAll(this)}}
}
