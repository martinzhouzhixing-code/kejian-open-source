package app.kejian.mobile

import androidx.compose.ui.draw.clip

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.*
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.util.UUID

class MainActivity:ComponentActivity(){
    companion object { const val EXTRA_OPEN_RECORDING="app.kejian.mobile.OPEN_RECORDING" }
    private lateinit var model:KejianViewModel
    private lateinit var startupHealth:StartupHealth
    private var entryReady by mutableStateOf(true)
    private var recoveringLaunch=false
    private var launchToken by mutableIntStateOf(0)
    private var entryToken by mutableIntStateOf(0)
    private var hasStarted=false
    private var firstResume=true
    override fun onStart(){super.onStart();if(hasStarted&&::model.isInitialized&&!model.isEditing){entryToken++;if(!recoveringLaunch)model.checkForUpdate()};hasStarted=true}
    private var refreshToken by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();startupHealth=StartupHealth(this);recoveringLaunch=startupHealth.begin();if(recoveringLaunch)startupHealth.repairAuxiliaryState()
        val created=try {ViewModelProvider(this)[KejianViewModel::class.java]}catch(error:Throwable){startupHealth.recordStartupFailure(error);setContent {CompatibilityRecoveryScreen(error.message){finishAndRemoveTask()}};return}
        model=created;if(recoveringLaunch)model.message=startupHealth.recoveryMessage()
        // Let Android remove its own splash normally. Some vendor ROMs become unstable when
        // an exit listener holds the splash while preferences and widgets are restored.
        setContent { LaunchedEffect(Unit){repeat(3){withFrameNanos { }};delay(5_000);startupHealth.stable();recoveringLaunch=false;model.checkForUpdate()};KejianTheme(if(model.tutorialPending)TutorialAppearance.mode?:model.data.settings.themeMode else model.data.settings.themeMode,if(model.tutorialPending)TutorialAppearance.skin?:model.data.settings.skin else model.data.settings.skin,model.data.settings.glassBackground,model.data.settings.liquidGlass,model.data.settings) { KejianApp(model,intent.getStringExtra("courseId"),intent.getStringExtra("date"),launchToken,refreshToken,entryToken,entryReady,openRecording=intent.getBooleanExtra(EXTRA_OPEN_RECORDING,false),openAiBackground=intent.getBooleanExtra("openAiBackground",false),aiWorkOwner=intent.getStringExtra("aiWorkOwner"),aiWorkNoteId=intent.getStringExtra("aiWorkNoteId")) } }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);launchToken++}
    override fun onResume(){super.onResume();if(::model.isInitialized){model.consumeBackgroundResults();model.resumeAiBackgroundWork();model.consumeAiBackgroundResults();refreshToken++;val appContext=applicationContext;runCatching {BackgroundTaskService.resumeAudio(appContext)};runCatching {BackgroundTaskService.resumeDocument(appContext)};val cold=firstResume;firstResume=false;lifecycleScope.launch {delay(if(cold||recoveringLaunch)5_500 else 900);model.consumeBackgroundResults();withContext(Dispatchers.Default){runCatching {ReminderScheduler.reschedule(appContext,model.savedData);KejianWidgets.refreshAll(appContext)}};if(!recoveringLaunch)model.syncCloudIfStale()}}}
}

@Composable private fun CompatibilityRecoveryScreen(message:String?,onClose:()->Unit){
    MaterialTheme {GlassSurface(Modifier.fillMaxSize(),color=Color(0xFF0B1D17)){Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally){Image(painterResource(R.drawable.ic_launcher),"课间",Modifier.size(72.dp));Spacer(Modifier.height(22.dp));Text("课间已阻止本次启动闪退",color=Color.White,style=MaterialTheme.typography.headlineSmall);Spacer(Modifier.height(12.dp));Text("本地课表和登录凭据没有被清除。请关闭后重新打开；诊断信息已保存在本机。",color=Color(0xFFBCD0C7));if(!message.isNullOrBlank())Text(message.take(180),Modifier.padding(top=10.dp),color=Color(0xFF91A89F),style=MaterialTheme.typography.bodySmall);GlassButton(onClick=onClose,modifier=Modifier.padding(top=24.dp)){Text("关闭应用")}}}}
}

data class PendingCommit(val data:AppData,val description:String,val after:()->Unit,val restore:Boolean=false)
data class ImportPreview(val courses:List<Course>,val errors:List<String>,val backup:AppData?=null,val jobId:String?=null,val aiTask:Boolean=false,val deadlines:List<Deadline> = emptyList())

// Insets are applied by each page; content deliberately extends behind the progressive edge blur.
@android.annotation.SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable fun KejianApp(model:KejianViewModel,openId:String?,openDate:String?,launchToken:Int,refreshToken:Int,entryToken:Int=0,entryReady:Boolean=true,openRecording:Boolean=false,openAiBackground:Boolean=false,aiWorkOwner:String?=null,aiWorkNoteId:String?=null){
    AppLanguage.code=model.data.settings.language
    LaunchedEffect(AiBackgroundRuntime.revision){model.consumeAiBackgroundResults()}
    val context=androidx.compose.ui.platform.LocalContext.current
    val launch=remember {Animatable(0f)}
    var logoBounds by remember {mutableStateOf<Rect?>(null)}
    var leaveTarget by remember {mutableStateOf<String?>(null)}
    var details by remember {mutableStateOf<Occurrence?>(null)}
    var deadlineDetails by remember {mutableStateOf<List<Deadline>?>(null)}
    var deadlineEditing by remember {mutableStateOf<Deadline?>(null)}
    var createChoice by remember {mutableStateOf(false)}
    var screen by rememberSaveable { mutableStateOf("home") }
    var selectedNoteId by rememberSaveable { mutableStateOf("") }
    var noteReturn by rememberSaveable { mutableStateOf("record") }
    val guideTargets = remember { OnboardingTargets() }
    val guideProgress = remember { OnboardingProgress(context) }
    var guideIndex by remember { mutableIntStateOf(guideProgress.read()) }
    val guideDemo = remember(AppLanguage.code) { onboardingRecordingSample() }
    val guidePreview = remember(AppLanguage.code) { onboardingPreviewSample() }
    LaunchedEffect(model.tutorialPending, guideIndex) {
        guideTargets.scene=guideIndex
        guideTargets.activeKey = if (model.tutorialPending) onboardingSteps[guideIndex].target else null
        if (model.tutorialPending) screen = onboardingSteps[guideIndex].route
    }
    fun openNote(id: String, returnTo: String) { selectedNoteId = id; noteReturn = returnTo; screen = "record_detail" }
    fun finishGuide() { TutorialAppearance.skin=null;TutorialAppearance.mode=null; guideProgress.reset(); guideTargets.activeKey = null; model.finishTutorial(); screen = "home" }

    LaunchedEffect(entryToken,entryReady){
        if(!entryReady){launch.snapTo(0f);return@LaunchedEffect}
        if(screen=="home"){
            launch.snapTo(0f)
            // Wait for the app's first unobscured frame; otherwise the system splash hides the logo.
            withFrameNanos {};withFrameNanos {}
            if(android.animation.ValueAnimator.areAnimatorsEnabled())launch.animateTo(1f,tween(1250,easing=LinearEasing))else launch.snapTo(1f)
        }else launch.snapTo(1f)
    }
    LaunchedEffect(model.isEditing){if(!model.isEditing&&screen in listOf("editor","batch","import","preview"))screen="home"}
    var pending by remember { mutableStateOf<PendingCommit?>(null) }
    var imported by remember { mutableStateOf<ImportPreview?>(null) }
    var previewReturn by rememberSaveable {mutableStateOf("import")}
    var deleteAll by remember { mutableStateOf(false) }
    var notificationTest by rememberSaveable { mutableStateOf(false) }
    var testFeedback by remember { mutableStateOf<NotificationReport?>(null) }
    var testStatus by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val data=model.data
    fun commit(candidate:AppData,description:String,after:()->Unit={screen="home"},restore:Boolean=false){
        if(conflictPairs(candidate.courses,candidate.settings.termStart).any {pair->conflictPairs(data.courses,data.settings.termStart).none {old->setOf(old.first.id,old.second.id)==setOf(pair.first.id,pair.second.id)} })pending=PendingCommit(candidate,description,after,restore)
        else if(model.commit(candidate,description,restore=restore))after()
    }
    fun edit(course:Course,date:LocalDate){if(!model.isEditing){details=Occurrence(course,date);return};model.editor=course;model.editingDate=date;screen="editor"}
    fun createCourse(){if(!model.isEditing){model.message="请先点编辑";return};val now=LocalTime.now();val start=snapStart(maxOf(420,now.hour*60+now.minute).toFloat(),60);model.editor=Course(day=LocalDate.now().dayOfWeek.value,start=start,end=start+60,color=nextCourseColor(model.data.courses));model.editingDate=model.shownWeek.plusDays((LocalDate.now().dayOfWeek.value-1).toLong());screen="editor"}
    fun create(){if(!model.isEditing){model.message="请先点编辑";return};createChoice=true}
    fun openDeadline(deadlines:List<Deadline>){if(deadlines.size==1&&model.isEditing){deadlineEditing=deadlines.single();screen="deadline_editor"}else deadlineDetails=deadlines}
    fun readImport(text:String){
        previewReturn="import"
        if(text.trimStart().startsWith("{"))runCatching { DataJson.decode(text) }.onSuccess { imported=ImportPreview(it.courses,emptyList(),it);screen="preview" }.onFailure { model.message="备份无法读取：${it.message}" }
        else {val result=CourseTable.parse(text);imported=ImportPreview(result.courses,result.errors);screen="preview"}
    }
    fun feedback(report:NotificationReport){testFeedback=report;testStatus=report.title}
    fun openNotificationSettings(target:String?){runCatching {context.startActivity(if(target=="channel")Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,context.packageName).putExtra(AndroidSettings.EXTRA_CHANNEL_ID,ReminderScheduler.activeChannel(context)) else Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE,context.packageName))}.onFailure {model.message="无法打开设置，请在手机设置中找到课间 → 通知"}}
    fun toggleReminders(enabled:Boolean){val blocked=if(enabled)ReminderScheduler.blockedReason(context)else null;if(blocked!=null){feedback(blocked);return};model.commit(model.data.copy(settings=model.data.settings.copy(reminders=enabled)),if(enabled)"已开启提醒" else "已关闭提醒",false)}
    fun sendTest(){notificationTest=false;feedback(ReminderScheduler.notify(context,model.data.courses.firstOrNull()?:Course(address="东校区 · 博学楼",room="A201"),minutes=model.data.settings.reminderMinutes,test=true))}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted){if(notificationTest)sendTest()else toggleReminders(true)}
        else feedback(ReminderScheduler.blockedReason(context)?:NotificationReport(false,"需要通知权限","请在系统通知设置中允许课间发送通知后重新测试。","app"))
        notificationTest=false
    }
    fun requestNotification(test:Boolean){notificationTest=test;if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)else if(test)sendTest()else toggleReminders(true)}
    val chooseFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri -> if(uri!=null)runCatching {
        val bytes=context.contentResolver.openInputStream(uri)?.use { input -> val buffer=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);var total=0;while(true){val count=input.read(chunk);if(count<0)break;total+=count;require(total<=2_000_000){"文件超过 2 MB，请分批导入"};buffer.write(chunk,0,count)};buffer.toByteArray() }?:error("文件无法读取")
        val charset=when { bytes.size>=2&&bytes[0]==0xFF.toByte()&&bytes[1]==0xFE.toByte()->Charsets.UTF_16LE;bytes.size>=2&&bytes[0]==0xFE.toByte()&&bytes[1]==0xFF.toByte()->Charsets.UTF_16BE;else->Charsets.UTF_8 }
        readImport(bytes.toString(charset).removePrefix("\uFEFF"))
    }.onFailure { model.message="导入失败：${it.message}" } }
    val recognitionImage=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)model.recognizeImage(uri)}
    val recognitionFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)model.recognizeDocument(uri)}
    val widgetBackgroundPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)model.saveWidgetBackground(uri)}
    val avatarPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->if(uri!=null)model.saveAvatar(uri)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)runCatching {
        val source=if(model.loadError!=null)context.getSharedPreferences("kejian_data",Context.MODE_PRIVATE).getString("data",null)?:error("没有备份数据") else DataJson.encode(model.savedData)
        context.contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use { it.write(source) }?:error("无法创建文件")
        model.message="完整备份已保存（含地址和单次调整）"
    }.onFailure { model.message="备份失败：${it.message}" }}
    LaunchedEffect(openId,launchToken){if(openId!=null){val c=model.data.courses.find { it.id==openId };if(c!=null){val date=runCatching { LocalDate.parse(openDate) }.getOrNull()?:upcoming(model.data).firstOrNull { it.course.id==c.id }?.date?:model.shownWeek.plusDays((c.day-1).toLong());model.shownWeek=monday(date);edit(c,date)}}}
    LaunchedEffect(model.recognitionPreview){model.consumeRecognitionPreview()?.let {preview->imported=preview;previewReturn="recognize";if(!model.tutorialPending)screen="preview"}}
    LaunchedEffect(Unit){snapshotFlow {model.message}.filterNotNull().collect { message -> model.message=null;notice=message }}
    LaunchedEffect(launchToken){if((context as? android.app.Activity)?.intent?.getBooleanExtra("openWidgets",false)==true&&!model.tutorialPending)screen="widgets"}
    fun navigate(route:String){if(model.isEditing&&route in listOf("widgets","recognize","import_hub","record","settings"))leaveTarget=route else screen=route}
    LaunchedEffect(openRecording,launchToken){
        if(openRecording&&!model.tutorialPending){navigate("record");launch.snapTo(1f)}
    }
    LaunchedEffect(openAiBackground,launchToken){
        if(openAiBackground&&!model.tutorialPending&&aiWorkOwner!=null&&aiWorkOwner==model.cloudSession?.userId){
            if(!aiWorkNoteId.isNullOrBlank()&&model.savedData.notes.any {it.id==aiWorkNoteId}){
                if(model.isEditing)navigate("record")else openNote(aiWorkNoteId,"record")
            }else navigate("recognize")
            launch.snapTo(1f)
        }
    }
    BackHandler(!model.tutorialPending&&(screen!="home"||model.isEditing)){when(screen){"schools","recognize"->screen="import_hub";"record_mindmap"->screen="record_detail";"record_detail"->screen=noteReturn;"ai_history"->screen="recognize";"account"->screen="settings";else->if(screen!="home")screen="home"else if(model.hasDraftChanges)leaveTarget="home"else model.discardEditing()}}
    val dockVisible=!model.tutorialPending&&screen in listOf("home","widgets","recognize","import_hub","record","settings")
    val dockInset=if(dockVisible)150.dp else 0.dp
    val safeTop=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalOnboardingTargets provides guideTargets,LocalDockInset provides dockInset,LocalPageTopInset provides safeTop) {
    Box(Modifier.fillMaxSize()){
    GlassAppBackground(if(model.tutorialPending)TutorialAppearance.skin?:data.settings.skin else data.settings.skin,data.settings.glassBackground,data.settings,secondary=!dockVisible)
    Scaffold(modifier=Modifier.graphicsLayer {alpha=((launch.value-.32f)/.46f).coerceIn(0f,1f)}.then(if(model.tutorialPending)Modifier.clearAndSetSemantics {} else Modifier),containerColor=Bg,bottomBar={
        if(dockVisible)FloatingDock(screen,::navigate)
    }){_ ->Box(Modifier.fillMaxSize().padding(bottom=if(model.tutorialPending)guidePanelHeight()else 0.dp).glassCapture(LocalGlassLayers.current.page)){
        AnimatedContent(targetState=screen,modifier=Modifier.fillMaxSize(),transitionSpec={ (fadeIn(tween(200))+slideInHorizontally(tween(240)){it/16}) togetherWith (fadeOut(tween(130))+slideOutHorizontally(tween(200)){-it/24}) },label="pageTransition"){route->when(route){
            "home"->HomeScreen(model,demo=if(model.tutorialPending)guideTargets else null,onEdit={c,d->edit(c,d)},onDeadline={openDeadline(it)},onCreate={create()},onBatch={screen="batch"},onList={screen="list"},onSettings={navigate("settings")},onDrop={c,oldDate,newDate,start,fresh->
                val moved=c.copy(day=newDate.dayOfWeek.value,start=start,end=start+c.duration)
                if(fresh){val week=weekNumber(newDate,data.settings.termStart);val created=if(week in 1..30)moved.copy(weeks=(week..maxOf(week,16)).toSet())else moved.copy(date=newDate);commit(data.copy(courses=data.courses+created),"新课程已放好，点模块 → 自定义完善课堂地址")}
                else if(oldDate!=newDate||c.start!=start)commit(changeCourse(data,c,moved,oldDate,newDate),if(data.settings.editAllWeeks)"已移动所有周次"else "已移动本次课程")
            },onDeleteDrop={c,date->commit(changeCourse(data,c,null,date,date),if(data.settings.editAllWeeks)"已删除所有周次 · 可撤销"else "已删除本次课程 · 可撤销")},onCopy={c,date->
                if(model.isEditing){val copied=duplicateCourse(data,c,date);if(copied==null)model.message="当天没有足够的空闲时间，请用自定义新建到其他日期"else commit(data.copy(courses=data.courses+copied),"已复制课程")}
            },onLogoBounds={logoBounds=it},logoVisible=launch.value>=1f,onOpenNote={openNote(it,"home")})
            "editor"->{val original=model.editor?:Course();key(original.id){CourseEditor(original,data.courses.any {it.id==original.id},onBack={screen="home"},onCopy={c->context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("课间课程 KJ1",CourseExchange.encodeCourse(c)));model.message="已复制严格 KJ1 课程记录"},onDelete={
                val date=model.editingDate?:model.shownWeek.plusDays((original.day-1).toLong())
                commit(changeCourse(data,original,null,date,date),if(data.settings.editAllWeeks)"已删除所有周次"else "已删除本次课程")
            },onSave={edited->
                val date=model.editingDate?:model.shownWeek.plusDays((original.day-1).toLong())
                if(data.courses.none {it.id==edited.id})commit(data.copy(courses=data.courses+edited),"课程已创建")
                else if(edited==original)screen="home"
                else commit(changeCourse(data,original,edited,date,monday(date).plusDays((edited.day-1).toLong())),if(data.settings.editAllWeeks)"已保存所有周次"else "已保存本次课程")
            })}}
            "deadline_editor"->{val original=deadlineEditing?:Deadline(color=nextCourseColor(data.courses),dueDate=LocalDate.now().plusDays(1));key(original.id){DeadlineEditor(original,data.deadlines.any {it.id==original.id},data.courses,onBack={screen="home"},onDelete={commit(data.copy(deadlines=data.deadlines.filterNot {it.id==original.id}),"截止日已删除 · 可撤销")},onSave={edited->val next=if(data.deadlines.any {it.id==edited.id})data.deadlines.map {if(it.id==edited.id)edited else it}else data.deadlines+edited;commit(data.copy(deadlines=next),if(data.deadlines.any {it.id==edited.id})"截止日已保存" else "截止日已创建")})}}

            "batch"->BatchScreen(data.courses,onBack={screen="home"},onImport={screen="import"},onPaste={text->readImport(text)},onSave={commit(data.copy(courses=it),"表格更改已保存")},onMessage={model.message=it})
            "list"->CourseListScreen(data,model.shownWeek,onBack={screen="home"},onEdit={c,d->edit(c,d)},onCreate={create()},editable=model.isEditing)
            "widgets"->WidgetsScreen(if(model.tutorialPending)guideWidgetData(model.savedData,guideTargets,context)else model.savedData,refreshToken,onStyle={style->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetStyle=style)),"组件外观已保存",false)},onOpacity={opacity->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetOpacity=opacity)),"组件透明度已保存",false)},onBackgroundMode={mode->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetBackgroundMode=mode)),"组件背景已保存",false)},onFrosted={enabled->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetFrosted=enabled)),recordingCopy("磨砂效果已保存","Frosted effect saved"),false)},onTextMode={mode->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetTextMode=mode)),recordingCopy("文字颜色已保存","Text color saved"),false)},onBackgroundTone={tone->model.commit(model.savedData.copy(settings=model.savedData.settings.copy(widgetBackgroundTone=tone)),"组件背景明暗已保存",false)},onChooseBackground={widgetBackgroundPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},onPin={index->model.message=KejianWidgetInstall.request(context,index)})
            "import_hub"->ImportHubScreen(data,onAi={screen="recognize"},onSchools={screen="schools"},onLegacy={model.beginEditing();screen="import"},onPreview={imported=it;previewReturn="import_hub";screen="preview"})
            "schools"->UniversityImportScreen(onBack={screen="import_hub"},onAiImport={text->if(model.cloudSession==null){model.message="请先配置 API Key，再返回学校页面导入";screen="account"}else{screen="recognize";model.submitAiCommand(text)}})
            "recognize"->RecognitionScreen(loggedIn=model.cloudSession!=null,profile=model.cloudSession?.profile,messages=if(model.tutorialPending)guideAiMessages(guideTargets)else model.aiMessages,busy=if(model.tutorialPending)guideTargets.scene==3&&guideTargets.beat>=2 else model.recognitionBusy,canStop=model.recognitionCanStop,onStop={model.stopRecognition()},onHistory={screen="ai_history"},onAccount={screen="account"},onChooseImage={if(model.cloudSession==null)screen="account" else recognitionImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},onChooseFile={recognitionFile.launch(arrayOf("application/pdf","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.ms-excel"))},onSubmit={command->if(model.cloudSession==null)screen="account" else model.submitAiCommand(command)})
            "ai_history"->AiHistoryScreen(model,onBack={screen="recognize"})
            "record"->RecordingScreen(model,onAccount={screen="account"},onOpenNote={openNote(it,"record")},demoNote=if(model.tutorialPending)guideDemo else null)
            "record_detail"->RecordingDetailScreen(model,if(model.tutorialPending)"tutorial-recording"else selectedNoteId,onBack={screen=noteReturn},onAccount={screen="account"},onOpenMindMap={selectedNoteId=it;screen="record_mindmap"},demoNote=if(model.tutorialPending)if(onboardingSteps[guideIndex].target=="record.detail.actions")guideDemo.copy(summary="",keyPoints=emptyList(),actionItems=emptyList(),transcript="")else guideDemo else null)
            "record_mindmap"->RecordingMindMapScreen(model,if(model.tutorialPending)"tutorial-recording"else selectedNoteId,onBack={screen="record_detail"},demoNote=if(model.tutorialPending)guideDemo else null)
            "settings"->SettingsScreen(data,refreshToken,model.loadError,session=model.cloudSession,remainingCredits=model.recognitionCredits,supporter=model.supporter,avatarRevision=model.avatarRevision,testStatus=testStatus,update=model.availableUpdate,onUpdate={model.openUpdateWebsite()},onChange={settings->model.commit(data.copy(settings=settings),"设置已保存",settings.termStart!=data.settings.termStart||settings.termName!=data.settings.termName)},onReminders={enabled->if(enabled)requestNotification(false)else toggleReminders(false)},onExact={if(Build.VERSION.SDK_INT>=31)runCatching { context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}"))) }.onFailure { model.message="请在系统设置中允许“闹钟和提醒”" }},onTest={requestNotification(true)},onBanners={feedback(ReminderScheduler.enableBanners(context))},onBannerSettings={openNotificationSettings("channel")},onBackup={export.launch("课间备份-${LocalDate.now()}.json")},onImport={model.beginEditing();screen="import"},onClear={deleteAll=true},onTutorial={guideProgress.reset();guideIndex=0;screen="home";model.replayTutorial()},onAccount={screen="account"})
            "account"->PersonalAiSettings(model,onBack={screen="settings"})
            "import"->ImportScreen(onBack={screen="home"},onChoose={chooseFile.launch(arrayOf("text/*","application/json","application/octet-stream","application/vnd.ms-excel"))},onPaste={text->readImport(text)})
            "preview"->if(model.tutorialPending)TimetablePreviewScreen(guidePreview.second,guidePreview.first,onBack={},onConfirm={},readOnly=true)else PreviewScreen(imported?:ImportPreview(emptyList(),emptyList()),data,onBack={screen=previewReturn},onConfirm={val i=imported?:return@PreviewScreen;val candidate=i.backup?:data.copy(courses=data.courses+i.courses,deadlines=data.deadlines+i.deadlines);if(i.aiTask)model.confirmAiPreview(i.jobId){val applied=model.commit(candidate,if(i.backup!=null)"AI 任务已应用" else "AI 已导入 ${i.courses.size} 节课程、${i.deadlines.size} 个截止日",restore=false);if(applied)screen="recognize";applied}else commit(candidate,if(i.backup!=null)"完整备份已恢复" else "导入已完成",restore=i.backup!=null)})
        }
    }}}
    EdgeBlur(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(safeTop+24.dp),top=true)
    PersonalAiStatus.error?.let {error->GlassAlertDialog(onDismissRequest={PersonalAiStatus.error=null},title={Text("API Key 无法使用")},text={Text(error)},confirmButton={TextButton(onClick={PersonalAiStatus.error=null;screen="account"}){Text("检查配置")}},dismissButton={TextButton(onClick={PersonalAiStatus.error=null}){Text("稍后")}})}
    notice?.let { GlassAlertDialog(onDismissRequest={notice=null},title={Text("课间提示")},text={Text(it)},confirmButton={GlassFilledTonalButton(onClick={notice=null}){Text("知道了")}}) }
    model.aiClarification?.takeIf {it.first==model.cloudSession?.userId}?.let {(_,question)->
        GlassAlertDialog(onDismissRequest={model.dismissAiClarification()},
            title={Text(if(AppLanguage.english)"A little more detail"else"请补充一点信息")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(question)
                Text(if(AppLanguage.english)"Your schedule is unchanged. Add the missing details and resend the complete request."else"原日程未改变。请补充所缺信息，重新发送完整安排。",style=MaterialTheme.typography.bodySmall)
            }},confirmButton={TextButton(onClick={model.dismissAiClarification();screen="recognize"}){Text(if(AppLanguage.english)"Edit request"else"返回补充")}})
    }
    testFeedback?.let {report->GlassAlertDialog(onDismissRequest={testFeedback=null},title={Text(report.title)},text={Text(report.message)},confirmButton={TextButton(onClick={testFeedback=null}){Text("知道了")}},dismissButton={TextButton(onClick={testFeedback=null;openNotificationSettings(report.settingsTarget)}){Text("打开通知设置")}})}
    pending?.let { change->val pairs=conflictPairs(change.data.courses,change.data.settings.termStart);GlassAlertDialog(onDismissRequest={pending=null},title={Text("发现时间冲突")},text={Text(pairs.take(3).joinToString("\n"){"${it.first.name} / ${it.second.name}"}+"\n共 ${pairs.size} 组重叠。保留后将在课表中并排显示，不会覆盖原课程。")},confirmButton={TextButton(onClick={pending=null;if(model.commit(change.data,change.description,restore=change.restore))change.after()}){Text("仍然保留")}},dismissButton={TextButton(onClick={pending=null}){Text("返回修改")}}) }
    if(deleteAll)GlassAlertDialog(onDismissRequest={deleteAll=false},title={Text("清空课表？")},text={Text("将删除所有课程及单次调整。建议先备份；清空会先进入草稿；点保存后才生效，可在编辑页撤销。")},confirmButton={TextButton(onClick={deleteAll=false;model.beginEditing();model.commit(data.copy(courses=emptyList()),"课表已清空");screen="home"}){Text("确认清空")}},dismissButton={TextButton(onClick={deleteAll=false}){Text("取消")}})
    details?.let {o->GlassAlertDialog(onDismissRequest={details=null},title={Text(o.course.name)},text={Text("${o.date} · ${timeText(o.course.start)}–${timeText(o.course.end)}\n课堂地址：${o.course.place}\n\n当前为浏览模式。点右下角编辑后，可复制、自定义或拖动课程。")},confirmButton={TextButton(onClick={details=null}){Text("知道了")}})}
    deadlineDetails?.let {items->GlassAlertDialog(onDismissRequest={deadlineDetails=null},title={Text(if(items.size==1)items.single().title else "${items.first().dueDate} · ${items.size} 个截止日")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){items.forEach {d->GlassSurface(color=SurfaceColor,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().clickable(enabled=model.isEditing){deadlineDetails=null;deadlineEditing=d;screen="deadline_editor"}){Column(Modifier.padding(12.dp)){Text(d.title,style=MaterialTheme.typography.titleMedium);Text("${d.dueDate} · ${d.timeLabel}",color=Brand);if(d.details.isNotBlank())Text(d.details,color=Muted,style=MaterialTheme.typography.bodySmall)}}}}},confirmButton={TextButton(onClick={deadlineDetails=null}){Text(if(model.isEditing&&items.size>1)"点选一项编辑" else "知道了")}})}
    if(createChoice)GlassAlertDialog(onDismissRequest={createChoice=false},title={Text("添加到课间")},text={Text("课程会占用时间段；截止日只标记日期整列，不会画在凌晨。")},confirmButton={GlassButton(onClick={createChoice=false;createCourse()}){Text("新建课程")}},dismissButton={GlassFilledTonalButton(onClick={createChoice=false;deadlineEditing=Deadline(dueDate=LocalDate.now().plusDays(1),color=nextCourseColor(data.courses));screen="deadline_editor"}){Text("新建截止日")}})
    if(model.accountPromptPending&&!model.tutorialPending)GlassAlertDialog(onDismissRequest={model.dismissAccountPrompt()},title={Text("把课表安全留在云端？")},text={Text("账号不是使用课间的前提。你可以继续只保存在本机；也可以使用邮箱登录，在换手机时恢复课表。")},confirmButton={GlassButton(onClick={model.dismissAccountPrompt();screen="account"}){Text("登录或创建账号")}},dismissButton={TextButton(onClick={model.dismissAccountPrompt()}){Text("暂时不用")}})
    if(model.releaseNotesPending&&!model.tutorialPending&&!model.accountPromptPending)GlassAlertDialog(onDismissRequest={model.dismissReleaseNotes()},title={Text("课间 ${CurrentReleaseNotes.version} 更新内容")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){CurrentReleaseNotes.items.forEach {Text("• $it")}}},confirmButton={GlassButton(onClick={model.dismissReleaseNotes()}){Text("开始使用")}})
    model.availableUpdate?.takeIf {model.updateDialogVisible&&!model.tutorialPending&&!model.accountPromptPending&&!model.releaseNotesPending}?.let {info->GlassAlertDialog(onDismissRequest={model.dismissUpdate()},title={Text(if(AppLanguage.english)"Kejian ${info.version} is available" else "课间 ${info.version} 可以更新")},text={Text(info.notes)},confirmButton={GlassButton(onClick={model.openUpdateWebsite()}){Text(if(AppLanguage.english)"Update on website" else "前往官网更新")}},dismissButton={TextButton(onClick={model.dismissUpdate()}){Text(if(AppLanguage.english)"Later" else "稍后")}})}
    leaveTarget?.let {target->GlassAlertDialog(onDismissRequest={leaveTarget=null},title={Text("离开编辑模式？")},text={Text(if(model.hasDraftChanges)"还有未保存的课程改动。保存后才会更新通知和桌面组件。"else "退出编辑后将恢复浏览模式。")},confirmButton={TextButton(onClick={if(model.saveEditing()){leaveTarget=null;screen=target}}){Text("保存并离开")}},dismissButton={Row{TextButton(onClick={leaveTarget=null}){Text("继续编辑")};TextButton(onClick={model.discardEditing();leaveTarget=null;screen=target}){Text("放弃改动")}}})}
    if(launch.value<1f)LaunchOverlay(launch.value,logoBounds)
    else if(model.tutorialPending)PageOnboardingOverlay(guideIndex,guideTargets,onStep={guideIndex=it.coerceIn(0,onboardingSteps.lastIndex);guideProgress.save(guideIndex)},onDone={finishGuide()})
    }
    }
}

@Composable fun ScreenHeading(title:String,onBack:()->Unit,action:(@Composable ()->Unit)?=null){Row(Modifier.fillMaxWidth().padding(top=LocalPageTopInset.current).heightIn(min=64.dp).padding(end=16.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Filled.ArrowBack,"返回")};Text(title,style=AppTextStyles.sectionTitle,modifier=Modifier.weight(1f));action?.invoke()}}
@Composable fun PrimaryButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){GlassButton(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(20.dp)){Text(text)}}
@Composable fun WhiteCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Column(modifier.fillMaxWidth().liveGlass(RoundedCornerShape(20.dp),SurfaceColor).glassHighlight(RoundedCornerShape(20.dp)).background(if(LocalGlassEnabled.current)Color.Transparent else SurfaceColor,RoundedCornerShape(20.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
