package app.kejian.mobile

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun backgroundCopy(zh:String,en:String)=if(AppLanguage.english)en else zh

enum class BackgroundChannelState { ENABLED, BLOCKED, NOT_CREATED, UNKNOWN }
data class BackgroundNotificationChannel(val id:String,val state:BackgroundChannelState)

/** Unknown is deliberately not represented as allowed: some compatibility layers hide these APIs. */
data class BackgroundExecutionState(
    val batteryExempt:Boolean?=null,
    val backgroundRestricted:Boolean?=null,
    val powerSaver:Boolean?=null,
    val notificationsAllowed:Boolean?=null,
    val backgroundDataRestricted:Boolean?=null,
    val channels:List<BackgroundNotificationChannel> = emptyList(),
    val directExemptionAvailable:Boolean=false,
)

enum class BackgroundSettingsAction { NOTIFICATIONS, BATTERY_LIST, APP_DETAILS, BACKGROUND_DATA, REQUEST_EXEMPTION }
data class BackgroundSettingsRequest(val action:BackgroundSettingsAction,val coreWorkflowInterrupted:Boolean=false)

/** Direct exemption is an exceptional, explicitly confirmed remedy, never a prerequisite for tasks. */
internal fun canRequestDirectBatteryExemption(state:BackgroundExecutionState,confirmedImpact:Boolean)=
    confirmedImpact&&state.batteryExempt==false&&state.directExemptionAvailable

object BackgroundExecutionSettings {
    private val channelIds=listOf("kejian_background_tasks","kejian_ai_work","kejian_recording")

    fun read(context:Context):BackgroundExecutionState {
        val power=context.getSystemService(PowerManager::class.java)
        val notifications=context.getSystemService(NotificationManager::class.java)
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        val exempt=runCatching {power?.isIgnoringBatteryOptimizations(context.packageName)}.getOrNull()
        val notificationAllowed=runCatching {NotificationManagerCompat.from(context).areNotificationsEnabled()}.getOrNull()
        val channels=channelIds.map {id->BackgroundNotificationChannel(id,runCatching {
            val channel=notifications?.getNotificationChannel(id)
            when {
                notifications==null->BackgroundChannelState.UNKNOWN
                channel==null->BackgroundChannelState.NOT_CREATED
                channel.importance==NotificationManager.IMPORTANCE_NONE->BackgroundChannelState.BLOCKED
                Build.VERSION.SDK_INT>=28&&channel.group!=null&&notifications.getNotificationChannelGroup(channel.group)?.isBlocked==true->BackgroundChannelState.BLOCKED
                else->BackgroundChannelState.ENABLED
            }
        }.getOrDefault(BackgroundChannelState.UNKNOWN))}
        val declared=ContextCompat.checkSelfPermission(context,Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)==PackageManager.PERMISSION_GRANTED
        val direct=declared&&runCatching {requestIntent(context,BackgroundSettingsAction.REQUEST_EXEMPTION).resolveActivity(context.packageManager)!=null}.getOrDefault(false)
        return BackgroundExecutionState(exempt,
            if(Build.VERSION.SDK_INT>=28)runCatching {context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted}.getOrNull() else null,
            runCatching {power?.isPowerSaveMode}.getOrNull(),notificationAllowed,
            runCatching {when(connectivity?.restrictBackgroundStatus){ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED->true;ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED->false;else->null}}.getOrNull(),channels,direct)
    }

    private fun requestIntent(context:Context,action:BackgroundSettingsAction):Intent=when(action){
        BackgroundSettingsAction.NOTIFICATIONS->Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE,context.packageName)
        BackgroundSettingsAction.BATTERY_LIST->Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        BackgroundSettingsAction.APP_DETAILS->Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))
        BackgroundSettingsAction.BACKGROUND_DATA->Intent(SystemSettings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,Uri.parse("package:${context.packageName}"))
        BackgroundSettingsAction.REQUEST_EXEMPTION->Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:${context.packageName}"))
    }

    /** Must be called only from a visible user action. Returns a local, bilingual error on failure. */
    fun open(context:Context,request:BackgroundSettingsRequest):String? {
        if(request.action==BackgroundSettingsAction.REQUEST_EXEMPTION&&!canRequestDirectBatteryExemption(read(context),request.coreWorkflowInterrupted))
            return backgroundCopy("当前不适用直接申请。你仍可从系统电池设置自行调整。","A direct request is not applicable now. You can still review the system battery settings.")
        val primary=requestIntent(context,request.action)
        val fallback=requestIntent(context,BackgroundSettingsAction.APP_DETAILS)
        // The fallback is the same app's public settings, never an undocumented vendor activity.
        for(intent in if(request.action==BackgroundSettingsAction.REQUEST_EXEMPTION)listOf(primary)else listOf(primary,fallback)){
            try {context.startActivity(intent.apply {if(context !is Activity)addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)});return null}
            catch(_:android.content.ActivityNotFoundException){}catch(_:SecurityException){}
        }
        return backgroundCopy("系统没有提供这个入口。请手动打开手机设置，查找课间的通知、电池或后台活动选项。","This system does not provide that shortcut. Open your phone's Settings and find Kejian's notifications, battery or background activity options.")
    }
}

@Composable fun rememberBackgroundExecutionState():State<BackgroundExecutionState> {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val state=remember(context){mutableStateOf(BackgroundExecutionSettings.read(context))}
    DisposableEffect(context,lifecycle){
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_RESUME)state.value=BackgroundExecutionSettings.read(context)}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer)}
    }
    return state
}

internal fun backgroundExecutionSummary(state:BackgroundExecutionState):String=when {
    state.backgroundRestricted==true->backgroundCopy("系统限制后台活动","Background activity restricted")
    state.notificationsAllowed==false||state.channels.any {it.state==BackgroundChannelState.BLOCKED}->backgroundCopy("检查通知设置","Check notifications")
    state.backgroundDataRestricted==true->backgroundCopy("后台移动数据受限","Background mobile data restricted")
    state.powerSaver==true->backgroundCopy("省电模式已开启","Battery Saver is on")
    else->backgroundCopy("通知、电池与系统限制","Notifications, battery & limits")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BackgroundExecutionSettingsDialog(onDismiss:()->Unit) {
    val context=LocalContext.current
    val observed by rememberBackgroundExecutionState()
    var refreshed by remember {mutableStateOf<BackgroundExecutionState?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(observed){refreshed=null}
    GlassModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),
        containerColor=SurfaceColor,contentColor=Ink,shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp)) {
        BackgroundExecutionSettingsPanel(refreshed?:observed,onAction={request->error=BackgroundExecutionSettings.open(context,request)},
            onRefresh={refreshed=BackgroundExecutionSettings.read(context);error=null},onDismiss=onDismiss,error=error)
    }
}

/** Pure presentation is reusable in a settings sheet or a task help page; it launches nothing itself. */
@Composable fun BackgroundExecutionSettingsPanel(state:BackgroundExecutionState,onAction:(BackgroundSettingsRequest)->Unit,onRefresh:()->Unit,onDismiss:()->Unit,error:String?=null) {
    var explainExemption by rememberSaveable {mutableStateOf(false)}
    var confirmedImpact by rememberSaveable {mutableStateOf(false)}
    val unknown=backgroundCopy("系统未提供状态","Status unavailable")
    fun flag(value:Boolean?,yes:String,no:String)=when(value){true->yes;false->no;null->unknown}
    Column(Modifier.fillMaxWidth().fillMaxHeight(.92f).testTag("background_settings_panel").verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(backgroundCopy("后台运行","Background activity"),style=AppTextStyles.sectionTitle,color=Ink)
            Text(backgroundCopy("开始任务后可以离开页面。这里检查可能影响上传、AI 处理和录音的系统设置；没有“永不停止”的权限。","You can leave the page after starting a task. Check settings that can affect uploads, AI work and recording. No permission can make an app impossible to stop."),fontSize=14.sp,lineHeight=21.sp,color=Muted)
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.testTag("background_settings_error"))}
        BackgroundSettingsCard {
            BackgroundStatusTitle(backgroundCopy("任务通知","Task notifications"),flag(state.notificationsAllowed,backgroundCopy("应用通知已允许","App notifications allowed"),backgroundCopy("应用通知已关闭","App notifications blocked")),"background_notifications_state")
            state.channels.forEach {channel->
                val title=when(channel.id){"kejian_background_tasks"->backgroundCopy("文件与转写","Files & transcription");"kejian_ai_work"->backgroundCopy("AI 任务","AI tasks");else->backgroundCopy("录音控制","Recording controls")}
                val status=when(channel.state){BackgroundChannelState.ENABLED->backgroundCopy("已开启","On");BackgroundChannelState.BLOCKED->backgroundCopy("已关闭","Blocked");BackgroundChannelState.NOT_CREATED->backgroundCopy("首次使用时创建","Created on first use");BackgroundChannelState.UNKNOWN->unknown}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(title,Modifier.weight(1f),fontSize=13.sp,color=Muted)
                    Text(status,Modifier.weight(1f),fontSize=13.sp,color=Muted)
                }
            }
            Text(backgroundCopy("允许通知才能在通知栏看到进度、完成提醒和录音控制。通知关闭不等于任务一定未运行。","Allow notifications to see progress, completion alerts and recording controls. Hidden notifications do not necessarily mean a task has stopped."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
            BackgroundSettingsButton(backgroundCopy("管理通知","Manage notifications"),"background_notifications"){onAction(BackgroundSettingsRequest(BackgroundSettingsAction.NOTIFICATIONS))}
        }
        BackgroundSettingsCard {
            BackgroundStatusTitle(backgroundCopy("电池优化","Battery optimization"),flag(state.batteryExempt,backgroundCopy("已忽略系统电池优化","Exempt from system optimization"),backgroundCopy("采用系统默认优化","Using system optimization")),"background_battery_state")
            Text(backgroundCopy("默认优化不代表发生故障。若锁屏后任务经常中断，可在系统电池设置中查看“无限制”或“允许后台活动”。关闭优化可能增加耗电。","Optimization being on does not mean something is broken. If tasks repeatedly stop with the screen off, review Unrestricted or Allow background activity in system settings. Exemption can increase battery use."),fontSize=13.sp,lineHeight=19.sp,color=Muted)
            BackgroundSettingsButton(backgroundCopy("打开电池设置","Open battery settings"),"background_battery_settings"){onAction(BackgroundSettingsRequest(BackgroundSettingsAction.BATTERY_LIST))}
            if(state.batteryExempt==false&&state.directExemptionAvailable)TextButton(onClick={confirmedImpact=false;explainExemption=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("background_exemption_explain")){
                Text(backgroundCopy("锁屏后仍反复中断？","Still interrupted with the screen off?"))
            }
        }
        BackgroundSettingsCard {
            BackgroundStatusTitle(backgroundCopy("系统限制","System restrictions"),flag(state.backgroundRestricted,backgroundCopy("后台活动受限","Background activity restricted"),backgroundCopy("未报告后台活动限制","No background activity restriction reported")),"background_restricted_state")
            Text(backgroundCopy("省电模式：","Battery Saver: ")+flag(state.powerSaver,backgroundCopy("开启","On"),backgroundCopy("关闭","Off")),fontSize=13.sp,color=Muted)
            Text(backgroundCopy("后台移动数据：","Background mobile data: ")+flag(state.backgroundDataRestricted,backgroundCopy("受到流量节省限制","Restricted by Data Saver"),backgroundCopy("未报告流量节省限制","No Data Saver restriction reported")),fontSize=13.sp,lineHeight=19.sp,color=Muted)
            BackgroundSettingsButton(backgroundCopy("查看应用系统设置","Open app system settings"),"background_app_settings"){onAction(BackgroundSettingsRequest(BackgroundSettingsAction.APP_DETAILS))}
            if(state.backgroundDataRestricted==true)BackgroundSettingsButton(backgroundCopy("查看后台数据设置","Review background data"),"background_data_settings"){onAction(BackgroundSettingsRequest(BackgroundSettingsAction.BACKGROUND_DATA))}
        }
        BackgroundSettingsCard {
            Text(backgroundCopy("手机厂商与兼容环境","Phone vendors & compatibility environments"),fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=22.sp,color=Ink)
            Text(backgroundCopy("部分手机另有“启动管理”“允许后台活动”或最近任务锁定。名称因系统而异，请按需要手动调整；课间无法读取所有厂商开关。","Some phones have separate startup management, background activity or recent-task pinning controls. Names vary; review them manually if needed. Kejian cannot read every vendor-specific switch."),fontSize=13.sp,lineHeight=20.sp,color=Muted)
            Text(backgroundCopy("若在鸿蒙的卓易通等兼容环境中运行，宿主应用本身也可能受省电或后台限制。这里显示的是兼容环境返回的状态，不能保证宿主不会被停止。","If you use ZhuoyiTong or another compatibility environment on HarmonyOS, its host app may also face battery or background limits. These values come from that environment and cannot guarantee that the host will remain running."),fontSize=13.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.testTag("background_compatibility_notice"))
            Text(backgroundCopy("强行停止、断网和系统后台时限仍可中断任务。Android 15+ 对同类数据同步前台服务共享约 6 小时/24 小时后台配额；电池优化豁免不会取消该限制。","Force-stop, lost connectivity and system time limits can still interrupt tasks. Android 15+ shares a six-hour background allowance per 24 hours across data-sync foreground services. Battery exemption does not remove that limit."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            GlassOutlinedButton(onClick=onRefresh,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("background_refresh")){Text(backgroundCopy("刷新状态","Refresh"))}
            GlassButton(onClick=onDismiss,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("background_done")){Text(backgroundCopy("完成","Done"))}
        }
    }
    if(explainExemption)GlassAlertDialog(onDismissRequest={explainExemption=false},title={Text(backgroundCopy("申请忽略电池优化？","Request a battery exemption?"))},text={
        Column(Modifier.heightIn(max=380.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(backgroundCopy("仅当锁屏导致录音或上传反复中断、影响核心功能时申请。它可能增加耗电，也无法绕过强停、系统时限或兼容环境限制。你可以先使用普通电池设置；拒绝申请不影响继续使用课间。","Use this only if screen-off interruptions repeatedly prevent core recording or upload functions from working. It may use more battery and cannot bypass force-stop, time limits or compatibility restrictions. You can use ordinary battery settings first. Declining does not block Kejian."),fontSize=14.sp,lineHeight=21.sp)
            Row(verticalAlignment=Alignment.CenterVertically){
                Checkbox(checked=confirmedImpact,onCheckedChange={confirmedImpact=it},modifier=Modifier.testTag("background_confirm_impact"))
                Text(backgroundCopy("我确实遇到上述问题，理解耗电影响。","I have this problem and understand the battery impact."),fontSize=13.sp,lineHeight=19.sp)
            }
        }
    },confirmButton={TextButton(onClick={explainExemption=false;onAction(BackgroundSettingsRequest(BackgroundSettingsAction.REQUEST_EXEMPTION,true))},enabled=canRequestDirectBatteryExemption(state,confirmedImpact),modifier=Modifier.testTag("background_request_exemption")){Text(backgroundCopy("向系统申请","Request from Android"))}},dismissButton={TextButton(onClick={explainExemption=false}){Text(backgroundCopy("暂不申请","Not now"))}})
}

@Composable private fun BackgroundSettingsCard(content:@Composable ColumnScope.()->Unit){
    GlassSurface(color=Bg,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable private fun BackgroundStatusTitle(title:String,status:String,tag:String){
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(title,color=Ink,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
        Text(status,color=Muted,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.testTag(tag))
    }
}
@Composable private fun BackgroundSettingsButton(title:String,tag:String,onClick:()->Unit){
    GlassOutlinedButton(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag),shape=RoundedCornerShape(14.dp)){Text(title)}
}
