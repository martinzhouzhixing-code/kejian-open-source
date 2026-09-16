package app.kejian.mobile

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.sin

internal fun recordingCopy(zh:String,en:String)=if(AppLanguage.english)en else zh

private fun recordingNotificationsAvailable(context:Context):Boolean{
    if(!NotificationManagerCompat.from(context).areNotificationsEnabled())return false
    if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return false
    val channel=context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(RecordingService.CHANNEL)
    return channel?.importance!=NotificationManager.IMPORTANCE_NONE
}

@Composable fun RecordingScreen(model:KejianViewModel,onAccount:()->Unit,onOpenNote:(String)->Unit={},demoNote:LessonNote?=null){
    val context=LocalContext.current
    var elapsed by remember {mutableIntStateOf(0)}
    var recoveryChecked by remember {mutableStateOf(false)}
    var captureNotice by remember {mutableStateOf<String?>(null)}
    val active=demoNote==null&&RecordingRuntime.recording
    val paused=active&&RecordingRuntime.paused
    val captureGuide=demoNote!=null&&LocalOnboardingTargets.current?.activeKey=="record.capture"
    var demoPhase by remember {mutableIntStateOf(0)}
    var demoElapsed by remember {mutableIntStateOf(0)}
    val animationsEnabled=ValueAnimator.areAnimatorsEnabled()
    val filmBeat=LocalOnboardingTargets.current?.beat?:0
    // Tutorial state is deliberately independent of the real microphone, service and local files.
    LaunchedEffect(captureGuide,filmBeat){
        demoPhase=when {filmBeat<2->0;filmBeat<5->1;filmBeat<7->2;filmBeat<9->3;else->0}
        demoElapsed=when {filmBeat<2->0;filmBeat<5->10+filmBeat-2;filmBeat<7->12;else->12+(filmBeat-7).coerceIn(0,2)}
    }
    val displayActive=active||(captureGuide&&demoPhase>0)
    val displayPaused=paused||(captureGuide&&demoPhase==2)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var notificationsAvailable by remember(context){mutableStateOf(recordingNotificationsAvailable(context))}
    var requestingStart by remember {mutableStateOf(false)}
    var pendingStart by remember {mutableStateOf(false)}
    val profile=model.cloudSession?.profile
    val libraryRevision=RecordingLibrary.revision
    DisposableEffect(lifecycle,context){
        val observer=LifecycleEventObserver {_,event->
            resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(event==Lifecycle.Event.ON_RESUME)notificationsAvailable=recordingNotificationsAvailable(context)
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(active,paused){
        while(active&&RecordingRuntime.recording){elapsed=RecordingRuntime.elapsedSeconds();kotlinx.coroutines.delay(250)}
        elapsed=0
    }
    LaunchedEffect(RecordingRuntime.completedFile?.absolutePath,libraryRevision,demoNote){
        if(demoNote!=null)return@LaunchedEffect
        model.refreshRecordingLibrary()
        RecordingRuntime.completedFile?.let {file->
            if(!active&&file.isFile&&RecordingRuntime.completedSeconds>=10){
                model.captureLocalRecording(file,RecordingRuntime.completedSeconds)
                if(model.savedData.notes.any {it.audioPath==file.absolutePath}){
                    RecordingDraftStore.clear(context,file.absolutePath);RecordingRuntime.clear();captureNotice=null
                }else captureNotice=model.audioMessage
            }
        }
    }
    LaunchedEffect(Unit){
        if(demoNote==null&&!recoveryChecked&&!active){
            recoveryChecked=true
            val savedPaths=model.savedData.notes.mapNotNull {it.audioPath}.toSet()
            val restored=try {RecordingDraftStore.recover(context,savedPaths)}catch(c:CancellationException){throw c}catch(_:Exception){null}
            restored?.let {model.captureLocalRecording(it.file,it.seconds);RecordingDraftStore.clear(context,it.file.absolutePath)}
        }
    }
    LaunchedEffect(BackgroundTaskRuntime.status){if(demoNote==null)model.consumeBackgroundResults()}
    // Permission callbacks can arrive before ON_RESUME. Only start the microphone service
    // once its visible activity has resumed; declining notifications must not block recording.
    LaunchedEffect(pendingStart,resumed,demoNote){
        if(!pendingStart||!resumed||demoNote!=null)return@LaunchedEffect
        pendingStart=false
        try {
            if(!RecordingRuntime.recording)ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(RecordingService.START))
        }catch(_:SecurityException){captureNotice=recordingCopy("无法开始录音，请检查麦克风权限，然后保持此页面打开再试一次。","Could not start recording. Check microphone access, then try again while this page is open.")}
        catch(_:Exception){captureNotice=recordingCopy("系统暂时无法启动录音。请保持应用在前台，然后重试。","The system could not start recording. Keep the app open and try again.")}
        finally {requestingStart=false;notificationsAvailable=recordingNotificationsAvailable(context)}
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
        if(demoNote==null){notificationsAvailable=recordingNotificationsAvailable(context);pendingStart=true}
    }
    val requestNotificationsAndStart:()->Unit={
        val preferences=context.getSharedPreferences("kejian_recording_ui",Context.MODE_PRIVATE)
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!preferences.getBoolean("notification_permission_asked",false)){
            preferences.edit().putBoolean("notification_permission_asked",true).apply()
            try {notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}
            catch(_:Exception){pendingStart=true}
        }else pendingStart=true
    }
    val microphonePermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->
        if(demoNote==null){
            if(granted)requestNotificationsAndStart()
            else {requestingStart=false;captureNotice=recordingCopy("需要麦克风权限才能录音。请在系统应用权限设置中允许使用麦克风。","Microphone access is required. Allow it in the app's system permission settings.")}
        }
    }
    val sendCaptureAction:(String)->Unit={action->
        captureNotice=null
        if(action==RecordingService.STOP&&RecordingRuntime.elapsedMillis()<10_000L){
            captureNotice=recordingCopy("录音时间过短，至少需要实际录制 10 秒。暂停时间不计入，请继续录音。","Record at least 10 seconds before stopping. Paused time does not count; resume recording to continue.")
        }else try {context.startService(Intent(context,RecordingService::class.java).setAction(action))}
        catch(_:Exception){captureNotice=recordingCopy("暂时无法控制录音，请重试，或使用系统通知中的录音按钮。","Could not control the recording. Try again, or use the controls in the recording notification.")}
    }
    val quota=when {
        profile?.isDeveloper==true->recordingCopy("不限时长","Unlimited")
        profile?.audioSummary==true->recordingCopy("剩余 ${(profile.audioSecondsRemaining?:0)/60} 分钟","${(profile.audioSecondsRemaining?:0)/60} min left")
        model.cloudSession==null->recordingCopy("配置 API Key","Set API Key")
        else->recordingCopy("本地录音","Local recording")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=LocalPageTopInset.current).padding(horizontal=20.dp).padding(bottom=LocalDockInset.current),verticalArrangement=Arrangement.spacedBy(20.dp)){
        Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(recordingCopy("录音","Record"),style=AppTextStyles.pageTitle)
                Text(recordingCopy("专注当下，留下重要的事。","Be present. Keep what matters."),style=AppTextStyles.pageSubtitle,color=Muted)
            }
            GlassSurface(onClick=onAccount,shape=RoundedCornerShape(14.dp),color=Mint){Text(quota,Modifier.padding(horizontal=11.dp,vertical=12.dp),fontSize=12.sp,fontWeight=FontWeight.Medium,color=Brand)}
        }
        GlassSurface(Modifier.fillMaxWidth().onboardingTarget("record.capture").animateContentSize(tween(280,easing=FastOutSlowInEasing)),shape=RoundedCornerShape(24.dp),color=SurfaceColor){
            Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)){
                AnimatedContent(targetState=if(displayPaused)"paused"else if(displayActive)"recording"else "idle",transitionSpec={fadeIn(tween(200)) togetherWith fadeOut(tween(160))},label="recordState"){state->
                    val recording=state=="recording"
                    GlassSurface(shape=RoundedCornerShape(14.dp),color=if(recording)MaterialTheme.colorScheme.error.copy(alpha=.09f)else Mint){
                        Row(Modifier.padding(horizontal=11.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                            if(recording)Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error,CircleShape))
                            if(state=="paused")Icon(Icons.Filled.Pause,null,Modifier.size(14.dp),tint=Brand)
                            Text(when(state){"recording"->recordingCopy("正在录音","RECORDING");"paused"->recordingCopy("录音已暂停","RECORDING PAUSED");else->recordingCopy("准备就绪","READY TO RECORD")},Modifier.testTag("record_state"),fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,color=if(recording)MaterialTheme.colorScheme.error else Brand)
                        }
                    }
                }
                Text(recordingTimer(if(captureGuide)demoElapsed else elapsed),Modifier.testTag("record_timer"),fontSize=52.sp,lineHeight=70.sp,fontWeight=FontWeight.Normal,style=MaterialTheme.typography.displayMedium.copy(fontFeatureSettings="tnum"),maxLines=1)
                RecordingWaveform(displayActive&&!displayPaused,Modifier.fillMaxWidth().height(48.dp).testTag("record_waveform"))
                if(displayActive)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    RecordingPrimaryAction(if(displayPaused)recordingCopy("继续","Resume")else recordingCopy("暂停","Pause"),enabled=demoNote==null,recording=false,
                        modifier=Modifier.weight(1f),tag="record_pause_resume",paused=displayPaused,onClick={sendCaptureAction(if(paused)RecordingService.RESUME else RecordingService.PAUSE)})
                    RecordingPrimaryAction(recordingCopy("结束","Stop"),enabled=demoNote==null,recording=true,
                        modifier=Modifier.weight(1f),tag="record_stop",onClick={sendCaptureAction(RecordingService.STOP)})
                }else RecordingPrimaryAction(if(requestingStart)recordingCopy("正在准备…","Preparing…")else recordingCopy("开始录音","Start recording"),enabled=demoNote==null&&!requestingStart,recording=false,onClick={
                    if(demoNote!=null)return@RecordingPrimaryAction
                    captureNotice=null;requestingStart=true
                    if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)requestNotificationsAndStart()
                    else try {microphonePermission.launch(Manifest.permission.RECORD_AUDIO)}
                    catch(_:Exception){requestingStart=false;captureNotice=recordingCopy("无法请求麦克风权限，请保持应用打开并重试。","Could not request microphone access. Keep the app open and try again.")}
                })
                Text(when{captureGuide->recordingCopy("安全演示 · 不会打开麦克风或创建文件","Safe demo · no microphone or files are used");paused->recordingCopy("暂停期间不计时，继续后录入同一份文件","Paused time is excluded. Resume into the same file.");else->recordingCopy("结束后自动保存到下方录音列表","Saved automatically to your library when you stop")},fontSize=12.sp,lineHeight=17.sp,color=Muted,textAlign=TextAlign.Center)
            }
        }
        if(demoNote==null)RecordingRuntime.error?.let {RecordingMessage(localized(it),true)}
        if(demoNote==null)captureNotice?.let {RecordingMessage(it,true)}
        Column(Modifier.fillMaxWidth().testTag("record_background_info"),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Icon(Icons.Filled.ScreenLockPortrait,null,Modifier.size(15.dp),tint=Brand)
                Text(recordingCopy("开始后可切换应用或锁屏","Keep recording while away"),fontSize=12.sp,fontWeight=FontWeight.Medium,color=Brand)
            }
            Text(recordingCopy("请先在此页面开始录音，再切换应用或锁屏。允许通知后，可在通知栏暂停、继续或结束。系统强制停止、兼容环境或省电限制仍可能中断录音。","Start recording on this page before switching apps or locking the screen. Allow notifications for pause, resume and stop controls. A force stop, compatibility layer or system power restrictions may still interrupt recording."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
            if(demoNote==null&&!notificationsAvailable)GlassSurface(Modifier.fillMaxWidth().testTag("record_notification_notice"),shape=RoundedCornerShape(14.dp),color=Mint){
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(recordingCopy("未开启通知，仍可正常录音","Recording works without notifications"),fontSize=12.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,color=Brand)
                    Text(recordingCopy("开启通知后，才会显示通知栏录音入口，方便在后台暂停、继续和结束。","Enable notifications to see the recording controls in your notification shade while the app is in the background."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    TextButton(onClick={
                        try {context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}
                        catch(_:Exception){captureNotice=recordingCopy("无法打开通知设置，请在系统设置中找到“课间 → 通知”。","Could not open notification settings. In system settings, open Kejian → Notifications.")}
                    },modifier=Modifier.testTag("record_notification_settings")){Text(recordingCopy("打开通知设置","Open notification settings"),fontSize=12.sp)}
                }
            }
        }
        Column(Modifier.fillMaxWidth().onboardingTarget("record.library"),verticalArrangement=Arrangement.spacedBy(14.dp)){
            val notes=demoNote?.let {listOf(it)}?:model.savedData.notes.sortedByDescending {it.createdAt}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(recordingCopy("我的录音","My recordings"),Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
                Text(recordingCopy("${notes.size} 份","${notes.size} files"),fontSize=12.sp,color=Muted)
            }
            if(notes.isEmpty())GlassSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=SurfaceColor){
                Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Icon(Icons.Filled.LibraryMusic,null,tint=Brand)
                    Text(recordingCopy("留一个位置，给你的想法","A place for your ideas"),fontSize=15.sp,fontWeight=FontWeight.Medium)
                    Text(recordingCopy("课堂与会议录音都会出现在这里。点开文件，即可转写、查看总结或关联课程。","Your classes and meetings live here. Open a file to transcribe, read its notes, or link an event."),fontSize=13.sp,lineHeight=20.sp,color=Muted)
                }
            }
            notes.forEach {note->key(note.id){RecordingLibraryRow(note,onClick={onOpenNote(note.id)},onDelete=if(demoNote==null){{model.deleteRecording(note.id)}}else null)}}
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable internal fun RecordingLibraryRow(note:LessonNote,onClick:()->Unit,onDelete:(()->Boolean)?=null){
    val context=LocalContext.current
    val state=recordingFileState(context,note)
    var menu by remember(note.id){mutableStateOf(false)}
    var confirm by remember(note.id){mutableStateOf(false)}
    Box {
    GlassSurface(modifier=Modifier.fillMaxWidth().testTag("recording_row_${note.id}").animateContentSize()
        .combinedClickable(onClick=onClick,onLongClick=onDelete?.let {{menu=true}},onLongClickLabel=recordingCopy("删除录音","Delete recording")),shape=RoundedCornerShape(20.dp),color=SurfaceColor){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(13.dp)){
                GlassSurface(shape=RoundedCornerShape(14.dp),color=Mint){Icon(if(note.summary.isNotBlank())Icons.Filled.Article else Icons.Filled.GraphicEq,null,Modifier.padding(12.dp).size(22.dp),tint=Brand)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(note.title,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("${note.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()} · ${recordingTimer(note.durationSeconds)}",fontSize=12.sp,color=Muted)
                }
                Icon(Icons.Filled.ChevronRight,null,Modifier.size(20.dp),tint=Muted)
            }
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                if(state.busy)CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp,color=Brand)
                else Icon(if(state.stage in setOf("failed","paused"))Icons.Filled.Info else if(note.summary.isNotBlank())Icons.Filled.CheckCircle else Icons.Filled.PhoneAndroid,null,Modifier.size(13.dp),tint=Brand)
                Text(recordingStateLabel(state,note),Modifier.weight(1f),fontSize=12.sp,lineHeight=18.sp,color=if(state.stage=="failed")MaterialTheme.colorScheme.error else Brand)
                if(note.courseId!=null)Icon(Icons.Filled.Link,recordingCopy("已关联课程","Event linked"),Modifier.size(16.dp),tint=Muted)
            }
            if(state.stage=="uploading")LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth().height(3.dp),color=Brand)
        }
    }
    DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
        DropdownMenuItem(text={Text(recordingCopy("删除录音","Delete recording"),color=MaterialTheme.colorScheme.error)},
            leadingIcon={Icon(Icons.Filled.Delete,null,tint=MaterialTheme.colorScheme.error)},modifier=Modifier.testTag("recording_delete_action"),
            onClick={menu=false;confirm=true})
    }
    }
    if(confirm)GlassAlertDialog(onDismissRequest={confirm=false},title={Text(recordingCopy("删除这份录音？","Delete this recording?"))},
        text={Text(recordingCopy("将删除本机的录音、笔记、思维导图和关联记录，无法撤销。已导出到其他文件夹的音频不受影响。","This removes the local recording, notes, mind map and event link. It cannot be undone. Audio exported to other folders is kept."))},
        confirmButton={TextButton(onClick={confirm=false;onDelete?.invoke()},modifier=Modifier.testTag("recording_delete_confirm")){Text(recordingCopy("删除","Delete"),color=MaterialTheme.colorScheme.error)}},
        dismissButton={TextButton(onClick={confirm=false},modifier=Modifier.testTag("recording_delete_cancel")){Text(recordingCopy("取消","Cancel"))}})
}

@Composable internal fun recordingFileState(context:Context,note:LessonNote):RecordingLibrary.FileState{
    val revision=RecordingLibrary.revision
    val runtimeStage=BackgroundTaskRuntime.audioStage
    val pending=BackgroundTaskResults.pendingAudio(context)
    val persisted=remember(note.audioPath,revision){RecordingLibrary.fileState(context,note.audioPath)}
    return if(note.audioPath!=null&&pending?.path==note.audioPath&&BackgroundTaskRuntime.audioRunning)
        RecordingLibrary.FileState(runtimeStage.name.lowercase(),BackgroundTaskRuntime.audioStatus.orEmpty(),BackgroundTaskRuntime.audioPercent())
    else if(persisted.busy&&!BackgroundTaskRuntime.audioRunning)persisted.copy(stage=if(pending?.path==note.audioPath)"paused"else"local")
    else persisted
}

internal fun recordingStateLabel(state:RecordingLibrary.FileState,note:LessonNote):String=when(state.stage){
    "preparing"->recordingCopy("准备上传","Preparing upload")
    "uploading"->recordingCopy("上传中 · ${state.progress}%","Uploading · ${state.progress}%")
    "queued"->if(BackgroundTaskRuntime.audioQueuePosition>0)recordingCopy("排队中 · 第 ${BackgroundTaskRuntime.audioQueuePosition} 位","Queued · position ${BackgroundTaskRuntime.audioQueuePosition}")else recordingCopy("等待转写","Queued")
    "transcribing"->recordingCopy("正在转写并整理课堂笔记","Transcribing and organizing notes")
    "summarizing"->recordingCopy("正在整理详细总结","Writing detailed notes")
    "reconnecting"->recordingCopy("正在重连，进度已保留","Reconnecting · progress saved")
    "paused"->recordingCopy("已暂停 · 点开继续","Paused · open to continue")
    "save_pending"->recordingCopy("结果已保留 · 等待本地保存","Result kept · waiting for local storage")
    "failed"->recordingCopy("处理未完成 · 点开重试","Incomplete · open to retry")
    else->if(note.summary.isNotBlank())recordingCopy("转写与总结已完成","Transcript and notes ready")else recordingCopy("仅保存在本机","Saved on this device")
}

internal fun recordingTimer(seconds:Int):String=if(seconds>=3600)"%d:%02d:%02d".format(Locale.ROOT,seconds/3600,(seconds%3600)/60,seconds%60)
    else "%02d:%02d".format(Locale.ROOT,seconds/60,seconds%60)

@Composable private fun RecordingPrimaryAction(label:String,enabled:Boolean,recording:Boolean,modifier:Modifier=Modifier,tag:String="record_toggle",paused:Boolean?=null,onClick:()->Unit){
    val interactions=remember {MutableInteractionSource()}
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed).97f else 1f,spring(dampingRatio=.85f,stiffness=500f),label="recordPress")
    GlassButton(onClick=onClick,enabled=enabled,interactionSource=interactions,modifier=modifier.fillMaxWidth().heightIn(min=52.dp).scale(scale).testTag(tag),
        shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.buttonColors(containerColor=if(recording)MaterialTheme.colorScheme.error else Brand,
            contentColor=if(recording)MaterialTheme.colorScheme.onError else OnBrand)){
        if(recording){Icon(Icons.Filled.Stop,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp))}
        else if(paused!=null){Icon(if(paused)Icons.Filled.PlayArrow else Icons.Filled.Pause,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp))}
        Text(label,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center)
    }
}

/** Pausing cancels the phase animation in place; height and tint settle without jumping to a new wave. */
@Composable private fun RecordingWaveform(active:Boolean,modifier:Modifier=Modifier){
    val wavePhase=remember {Animatable(0f)}
    val animationsEnabled=ValueAnimator.areAnimatorsEnabled()
    val filmBeat=LocalOnboardingTargets.current?.beat?:0
    LaunchedEffect(active,animationsEnabled){
        if(active&&animationsEnabled)wavePhase.animateTo(wavePhase.value+(2*Math.PI).toFloat(),infiniteRepeatable(tween(1800,easing=LinearEasing),RepeatMode.Restart))
    }
    val intensity by animateFloatAsState(if(active)1f else 0f,tween(320,easing=FastOutSlowInEasing),label="recordingWaveActivity")
    val color=Brand.copy(alpha=.25f+.5f*intensity)
    Canvas(modifier){
        val count=25
        val step=minOf(9.dp.toPx(),size.width/(count+2))
        val origin=(size.width-(count-1)*step)/2
        repeat(count){index->
            val wave=.5f+.5f*sin(wavePhase.value+index*.86f)
            val height=(5+13*wave+intensity*(3+19*wave)).dp.toPx()
            drawLine(color,Offset(origin+index*step,size.height/2-height/2),Offset(origin+index*step,size.height/2+height/2),strokeWidth=4.dp.toPx(),cap=StrokeCap.Round)
        }
    }
}

@Composable internal fun RecordingMessage(message:String,error:Boolean){
    GlassSurface(Modifier.fillMaxWidth().testTag(if(error)"record_error" else "record_message"),shape=RoundedCornerShape(16.dp),
        color=if(error)MaterialTheme.colorScheme.error.copy(alpha=.07f)else SurfaceColor){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(9.dp)){
            Icon(if(error)Icons.Filled.Info else Icons.Filled.CheckCircle,null,Modifier.size(18.dp),tint=if(error)MaterialTheme.colorScheme.error else Brand)
            Text(message,Modifier.weight(1f),fontSize=13.sp,lineHeight=20.sp,color=if(error)MaterialTheme.colorScheme.error else Muted)
        }
    }
}

/** Uses the same native timetable renderer as Home, so event cards keep their exact geometry and colors. */
@Composable internal fun CoursePickerDialog(data:AppData,initial:Occurrence?,onDismiss:()->Unit,onSelect:(Occurrence?)->Unit){
    var week by remember(initial?.date){mutableStateOf(monday(initial?.date?:LocalDate.now()))}
    val palette=LocalAppPalette.current
    val weekIndex=weekNumber(week,data.settings.termStart)
    GlassDialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        GlassSurface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.82f),shape=RoundedCornerShape(28.dp),color=Bg,shadowElevation=18.dp){
            Column(Modifier.fillMaxSize()){
                Row(Modifier.fillMaxWidth().padding(start=18.dp,end=8.dp,top=12.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text(recordingCopy("选择关联课程","Link to a course"),style=MaterialTheme.typography.titleLarge);Text(recordingCopy("在课表中点选一个课程模块","Tap a course in your timetable"),style=MaterialTheme.typography.bodySmall,color=Muted)}
                    IconButton(onClick=onDismiss){Icon(Icons.Filled.Close,recordingCopy("取消","Cancel"))}
                }
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
                    IconButton(onClick={week=week.minusWeeks(1)}){Icon(Icons.Filled.ChevronLeft,recordingCopy("上一周","Previous week"))}
                    TextButton(onClick={week=monday(LocalDate.now())}){Text(if(weekIndex in 1..30)recordingCopy("第 $weekIndex 周","Week $weekIndex")else "${week.monthValue}/${week.dayOfMonth}")}
                    IconButton(onClick={week=week.plusWeeks(1)}){Icon(Icons.Filled.ChevronRight,recordingCopy("下一周","Next week"))}
                }
                GlassSurface(Modifier.fillMaxWidth().weight(1f).padding(horizontal=10.dp),shape=RoundedCornerShape(20.dp),color=SurfaceColor){
                    AndroidView(factory={context->TimetableView(context)},update={view->
                        view.appData=data;view.palette=palette;view.week=week;view.nextOccurrence=initial;view.editingEnabled=false;view.batchDeleteEnabled=false;view.hideSelectedCourse=false
                        view.onCreate={};view.onDeadline={};view.onSelection={course,date,_->if(course!=null&&date!=null)onSelect(Occurrence(course,date))}
                    },modifier=Modifier.fillMaxSize())
                }
                Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    GlassOutlinedButton(onClick={onSelect(null)},modifier=Modifier.weight(1f)){Text(recordingCopy("不关联课程","Keep unlinked"))}
                    TextButton(onClick=onDismiss){Text(recordingCopy("取消","Cancel"))}
                }
            }
        }
    }
}
