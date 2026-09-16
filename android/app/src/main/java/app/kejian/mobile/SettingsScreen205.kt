package app.kejian.mobile

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as SettingsText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.launch

private fun settingsCopy(zh: String, en: String) = if (AppLanguage.english) en else zh

/** Server / platform messages are translated separately from account and event data. */
private fun settingsStatus(value: String, englishFallback: String): String {
    if (!AppLanguage.english) return value
    val translated = localized(value, true)
    return if (translated.any { it in '\u3400'..'\u9fff' }) englishFallback else translated
}

private fun settingsTermLabel(value: String) =
    if (AppLanguage.english && value == "示例学期") "Example term" else value

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun SettingsScreen(data:AppData,refreshToken:Int,loadError:String?,session:CloudSession?=null,remainingCredits:Int?=0,supporter:Boolean=false,avatarRevision:Int=0,testStatus:String?=null,update:AppUpdateInfo?=null,onUpdate:()->Unit={},onChange:(Settings)->Unit,onReminders:(Boolean)->Unit,onExact:()->Unit,onTest:()->Unit,onBanners:()->Unit,onBannerSettings:()->Unit,onBackup:()->Unit,onImport:()->Unit,onClear:()->Unit,onTutorial:()->Unit={},onAccount:()->Unit={}) {
    val context = LocalContext.current
    var verifiedAdmin by remember(session?.userId,session?.accessToken) {mutableStateOf(false)}
    LaunchedEffect(session?.userId,session?.accessToken,refreshToken){
        verifiedAdmin=false
        val userId=session?.userId ?: return@LaunchedEffect
        try {verifiedAdmin=false}
        catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(_:Exception){verifiedAdmin=false}
    }
    val appVersion = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val allowed = remember(refreshToken, data) { ReminderScheduler.notificationAllowed(context) }
    val exact = remember(refreshToken) { ReminderScheduler.exactAllowed(context) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val guide=LocalOnboardingTargets.current
    LaunchedEffect(guide?.scene,guide?.beat){
        if(guide?.activeKey!=null&&guide.scene==16){val phase=(guide.beat/2).coerceIn(0,3);TutorialAppearance.skin=appSkins[phase].id;TutorialAppearance.mode=if(phase%2==0)"light"else "dark"}
    }
    DisposableEffect(Unit){onDispose{TutorialAppearance.skin=null;TutorialAppearance.mode=null}}
    var backgroundSettings by rememberSaveable { mutableStateOf(false) }
    val backgroundState by rememberBackgroundExecutionState()
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    fun closeSettingsSheet() { sheetScope.launch { settingsSheetState.hide(); if (!settingsSheetState.isVisible) sheet = null } }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    var termName by rememberSaveable(data.settings.termName, data.settings.language) { mutableStateOf(settingsTermLabel(data.settings.termName)) }
    var termStart by rememberSaveable(data.settings.termStart.toString()) { mutableStateOf(data.settings.termStart.toString()) }
    var reminderMinutes by remember(data.settings.reminderMinutes) { mutableFloatStateOf(data.settings.reminderMinutes.toFloat()) }
    var termError by remember { mutableStateOf(false) }
    var notificationError by remember { mutableStateOf(false) }
    val profile = session?.profile
    val tier = when (profile?.role) {
        "developer" -> settingsCopy("开发者", "Developer")
        "pro" -> "Pro"
        "plus", "supporter" -> "Plus"
        else -> settingsCopy("普通用户", "Standard")
    }
    val themeName = when (data.settings.themeMode) {
        "light" -> settingsCopy("浅色", "Light")
        "dark" -> settingsCopy("深色", "Dark")
        else -> settingsCopy("跟随系统", "System")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=LocalPageTopInset.current).padding(horizontal = 20.dp).padding(bottom=LocalDockInset.current),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsText(settingsCopy("设置", "Settings"), color = Ink, style = AppTextStyles.pageTitle)
            SettingsText(settingsCopy("让课间更合你的心意。", "Make Kejian yours."), color = Muted, style = AppTextStyles.pageSubtitle)
        }

        if(guide?.activeKey!=null&&guide.scene==16){
            GlassSurface(Modifier.fillMaxWidth().onboardingTarget("settings.skin.demo"),shape=RoundedCornerShape(24.dp)){
                Column(Modifier.padding(18.dp)){SkinChoices(data.settings.copy(skin=TutorialAppearance.skin?:data.settings.skin),{})}
            }
        }
        if (loadError != null) {
            SettingsPanel {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                    SettingsText(settingsCopy("本地数据读取失败", "Local data could not be loaded"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                SettingsText(settingsCopy("为保护数据，已暂停写入。请先导出备份，再尝试恢复。", "Writing is paused to protect your data. Export a backup before trying to restore it."), color = Muted, fontSize = 14.sp)
                SettingsText(settingsStatus(loadError, "Diagnostic details are stored on this device."), color = Muted, fontSize = 12.sp)
                GlassOutlinedButton(onClick = onBackup, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("导出原始数据", "Export original data")) }
            }
        }

        if (update != null) {
            SettingsPanel {
                SettingsText(settingsCopy("发现新版本 ${update.version}", "Update available · ${update.version}"), fontWeight = FontWeight.SemiBold, color = Ink)
                SettingsText(settingsCopy("前往官网下载安装，应用内不再下载安装包。", "Download and install from the official website. Kejian no longer downloads APKs in the app."),color = Muted,fontSize = 13.sp)
                GlassButton(onClick = onUpdate, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    SettingsText(settingsCopy("前往官网更新", "Update on website"))
                }
            }
        }

        SettingsGroup(settingsCopy("AI 服务","AI SERVICES")) {
            SettingsRow(Icons.Outlined.Key,settingsCopy("个人 API Key","Personal API Key"),if(session==null)settingsCopy("未配置","Not configured")else settingsCopy("已配置","Configured"),"settings_api_key",onAccount)
        }

        SettingsGroup(settingsCopy("偏好设置", "PREFERENCES")) {
            SettingsRow(Icons.Outlined.Palette,settingsCopy("皮肤与材质","Skins & materials"),if(data.settings.customTheme)settingsCopy("自定义","Custom")else if(AppLanguage.english)appSkin(data.settings.skin).en else appSkin(data.settings.skin).zh,"settings_skin"){sheet="skin"}
            SettingsRow(Icons.Outlined.Tune, settingsCopy("外观", "Appearance"), themeName, "settings_theme") { sheet = "theme" }
            SettingsRow(Icons.Outlined.GridView, settingsCopy("语言", "Language"), if (data.settings.language == "en") "English" else settingsCopy("中文", "Chinese"), "settings_language") { sheet = "language" }
        }
        SettingsGroup(settingsCopy("日程设置", "YOUR SCHEDULE")) {
            SettingsRow(Icons.Outlined.Schedule,settingsCopy("课表显示","Timetable display"),"${timeText(data.settings.visibleStart)}–${timeText(data.settings.visibleEnd)}","settings_schedule_display"){sheet="schedule_display"}
            SettingsRow(Icons.Outlined.CalendarMonth, settingsCopy("学期设置", "Term settings"), settingsTermLabel(data.settings.termName), "settings_term") { termError = false; sheet = "term" }
            SettingsRow(Icons.Outlined.Schedule, settingsCopy("提醒", "Reminders"), when {
                !allowed -> settingsCopy("需要通知权限", "Permission needed")
                !data.settings.reminders -> settingsCopy("已关闭", "Off")
                else -> settingsCopy("提前 ${data.settings.reminderMinutes} 分钟", "${data.settings.reminderMinutes} min before")
            }, "settings_reminders") { sheet = "reminders" }
        }

        SettingsGroup(settingsCopy("更多", "MORE")) {
            SettingsRow(Icons.Outlined.BatterySaver, settingsCopy("后台运行", "Background activity"), backgroundExecutionSummary(backgroundState), "settings_background") { backgroundSettings = true }
            SettingsRow(Icons.Outlined.AutoAwesome, settingsCopy("交互引导", "Interactive guide"), tag = "settings_guide", onClick = onTutorial)
            SettingsRow(Icons.Outlined.Description, settingsCopy("应用版本", "App version"), appVersion, "settings_version") { sheet = "version" }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (backgroundSettings) BackgroundExecutionSettingsDialog(onDismiss = { backgroundSettings = false })
    if (sheet != null) {
        val activeSheet = sheet!!
        GlassModalBottomSheet(
            onDismissRequest = { sheet = null }, sheetState = settingsSheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), containerColor = SurfaceColor, contentColor = Ink,
            scrimColor = Color.Black.copy(alpha = .35f)
        ) {
            Column(
                Modifier.fillMaxWidth().testTag("settings_${activeSheet}_sheet").imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when (activeSheet) {
                    "schedule_display" -> {
                        var start by remember {mutableStateOf(timeText(data.settings.visibleStart))}
                        var end by remember {mutableStateOf(timeText(data.settings.visibleEnd))}
                        var invalid by remember {mutableStateOf(false)}
                        SettingsSheetTitle(settingsCopy("课表显示","Timetable display"),settingsCopy("设置每天显示的时间范围。范围外有课时自动扩展，避免漏课。","Choose daily hours. Events outside this range remain visible."))
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            SettingsText(settingsCopy("周末无课时自动隐藏","Hide empty weekends"),Modifier.weight(1f))
                            Switch(data.settings.hideEmptyWeekends,{onChange(data.settings.copy(hideEmptyWeekends=it))})
                        }
                        OutlinedTextField(start,{start=it;invalid=false},label={SettingsText(settingsCopy("开始时间（如 08:00）","Start (08:00)"))},singleLine=true)
                        OutlinedTextField(end,{end=it;invalid=false},label={SettingsText(settingsCopy("结束时间（如 20:00）","End (20:00)"))},singleLine=true)
                        if(invalid)SettingsText(settingsCopy("请输入有效时间，结束至少晚于开始 30 分钟。","Enter valid times at least 30 minutes apart."),color=MaterialTheme.colorScheme.error)
                        SettingsDone {val a=parseTime(start);val b=parseTime(end);if(a==null||b==null||a !in 0..1410||b-a<30)invalid=true else {onChange(data.settings.copy(visibleStart=a,visibleEnd=b));closeSettingsSheet()}}
                    }
                    "skin" -> {SkinChoices(data.settings,onChange);SettingsDone {closeSettingsSheet()}}
                    "theme" -> {
                        SettingsSheetTitle(settingsCopy("外观", "Appearance"), settingsCopy("页面、小组件与提醒同步适配。", "A consistent look for the app, widgets and reminders."))
                        listOf(Triple("system", settingsCopy("跟随系统", "System"), Icons.Outlined.SettingsBrightness), Triple("light", settingsCopy("浅色", "Light"), Icons.Outlined.LightMode), Triple("dark", settingsCopy("深色", "Dark"), Icons.Outlined.DarkMode)).forEach { (code, title, icon) ->
                            SettingsChoice(title, data.settings.themeMode == code, icon, "theme_$code") { onChange(data.settings.copy(themeMode = code)) }
                        }
                        SettingsDone { closeSettingsSheet() }
                    }
                    "language" -> {
                        SettingsSheetTitle(settingsCopy("语言", "Language"), settingsCopy("界面、小组件、提醒与引导同步切换；你自己的日程名称保持原样。", "Applies to the app, widgets, reminders and guide. Your own event names stay unchanged."))
                        SettingsChoice(settingsCopy("中文", "Chinese"), data.settings.language == "zh", Icons.Outlined.Language, "language_zh") { onChange(data.settings.copy(language = "zh")) }
                        SettingsChoice("English", data.settings.language == "en", Icons.Outlined.Language, "language_en") { onChange(data.settings.copy(language = "en")) }
                        SettingsDone { closeSettingsSheet() }
                    }
                    "term" -> {
                        SettingsSheetTitle(settingsCopy("学期设置", "Term settings"), settingsCopy("设置重复日程的起点。", "Choose the starting point for recurring events."))
                        OutlinedTextField(termName, { termName = it.take(80); termError = false }, label = { SettingsText(settingsCopy("学期名称", "Term name")) }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(termStart, { termStart = it.take(10); termError = false }, label = { SettingsText(settingsCopy("第一周的周一", "Monday of week 1")) }, placeholder = { SettingsText("YYYY-MM-DD") }, singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
                        SettingsText(settingsCopy("改变学期起点会重新计算重复日程；已单独调整的日程保留原日期。", "Changing the term start recalculates recurring dates. Individually adjusted events keep their dates."), color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
                        AnimatedVisibility(termError) { SettingsText(settingsCopy("请填写学期名称，以及有效的周一日期。", "Enter a term name and a valid Monday date."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        GlassButton(onClick = {
                            val date = runCatching { LocalDate.parse(termStart) }.getOrNull()
                            if (date == null || date.dayOfWeek != DayOfWeek.MONDAY || termName.isBlank()) termError = true
                            else { termError = false; onChange(data.settings.copy(termName = termName.trim(), termStart = date)); closeSettingsSheet() }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { SettingsText(settingsCopy("保存学期", "Save term")) }
                    }
                    "reminders" -> {
                        SettingsSheetTitle(settingsCopy("日程提醒", "Event reminders"), settingsCopy("把提醒留在刚刚好的时候。", "A gentle reminder at just the right time."))
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SettingsText(settingsCopy("课前提醒", "Event reminders"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                SettingsText(if (allowed) settingsCopy("通知权限已允许", "Notifications allowed") else settingsCopy("需要允许系统通知", "Notifications required"), color = Muted, fontSize = 12.sp)
                            }
                            GlassSwitch(data.settings.reminders && allowed, onReminders)
                        }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Bg).padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SettingsText(settingsCopy("提前多久发送", "Notify before"), fontSize = 14.sp, color = Ink)
                                SettingsText(settingsCopy("${reminderMinutes.toInt()} 分钟", "${reminderMinutes.toInt()} min"), fontSize = 14.sp, color = Brand, fontWeight = FontWeight.SemiBold)
                            }
                            GlassSlider(value = reminderMinutes, onValueChange = { reminderMinutes = it }, valueRange = 1f..120f, steps = 118, onValueChangeFinished = { onChange(data.settings.copy(reminderMinutes = reminderMinutes.toInt().coerceIn(1, 120))) })
                            SettingsText(settingsCopy("提前 1–120 分钟。日程本身的提醒开关也需开启。", "Choose 1–120 minutes. Reminders must also be enabled for the event."), fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
                        }
                        Column(Modifier.fillMaxWidth().animateContentSize(tween(280)).clip(RoundedCornerShape(20.dp)).background(Bg).padding(8.dp)) {
                            SettingsRow(Icons.Outlined.NotificationsNone, settingsCopy("通知诊断", "Notification diagnostics"), if (diagnostics) settingsCopy("收起", "Hide") else settingsCopy("查看", "View"), "settings_notification_diagnostics") { diagnostics = !diagnostics }
                            AnimatedVisibility(diagnostics) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SettingsText(if (exact) settingsCopy("准时提醒权限已允许。", "Exact reminders are allowed.") else settingsCopy("尚未允许准时提醒，系统可能延后通知。", "Exact reminders are not allowed. Android may delay notifications."), color = Muted, fontSize = 13.sp)
                                    if (!exact) GlassOutlinedButton(onClick = onExact, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("允许准时提醒", "Allow exact reminders")) }
                                    GlassOutlinedButton(onClick = {
                                        notificationError = runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }.isFailure
                                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("打开系统通知设置", "Open notification settings")) }
                                    if (notificationError) SettingsText(settingsCopy("请在手机设置中找到课间 → 通知。", "Open Kejian → Notifications in your phone's Settings."), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                                    SettingsText(settingsStatus(ReminderScheduler.bannerStatus(context), "Check notification channel settings for banner availability."), color = Muted, fontSize = 13.sp)
                                    GlassFilledTonalButton(onClick = onBanners, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("启用弹窗提醒", "Enable banners")) }
                                    GlassOutlinedButton(onClick = onBannerSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("横幅渠道设置", "Banner settings")) }
                                    GlassButton(onClick = onTest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("发送测试通知", "Send test notification")) }
                                    testStatus?.let { SettingsText(settingsStatus(it, "Check the notification test result and your system notification settings."), color = Brand, fontSize = 13.sp) }
                                    SettingsText(settingsCopy("首次安装默认关闭示例日程提醒。省电、强行停止及厂商后台限制可能影响送达；需要时允许课间自启动。", "Example reminders start disabled. Battery saving, force stop and device background restrictions may affect delivery. Allow Kejian to start automatically if needed."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                        SettingsDone { closeSettingsSheet() }
                    }
                    "version" -> {
                        Text(settingsCopy("内置风景摄影：Tom Fisk、Elle Hughes、Andy Dufresne / Pexels。","Landscape photos: Tom Fisk, Elle Hughes, Andy Dufresne / Pexels."),color=Muted,style=MaterialTheme.typography.bodySmall)

                        SettingsSheetTitle(settingsCopy("应用版本", "App version"), "Kejian $appVersion")
                        if(update != null) {
                            SettingsText(settingsCopy("发现新版本 ${update.version}", "Update available · ${update.version}"), fontWeight = FontWeight.SemiBold)
                            SettingsText(settingsStatus(update.notes, "This update includes improvements and fixes for Kejian."), color = Muted, fontSize = 14.sp, lineHeight = 21.sp)
                        }
                        SettingsText(settingsCopy("更新请前往官网下载安装。", "Visit the official website to download and install updates."), color = Muted, fontSize = 14.sp)
                        GlassButton(onClick = onUpdate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { SettingsText(settingsCopy("前往官网更新", "Update on website")) }
                        SettingsDone { closeSettingsSheet() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPanel(spacing: Int = 12, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(shape = RoundedCornerShape(24.dp), color = SurfaceColor, modifier = Modifier.fillMaxWidth().animateContentSize(tween(280))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(spacing.dp), content = content)
    }
}

@Composable
private fun SettingsQuota(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsText(label, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        SettingsText(value, color = Brand, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp)
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsText(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
        GlassSurface(shape = RoundedCornerShape(24.dp), color = SurfaceColor) { Column(Modifier.fillMaxWidth().padding(4.dp), content = content) }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, value: String = "", tag: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).then(when(tag){"settings_language"->Modifier.onboardingTarget("settings.language");"settings_background"->Modifier.onboardingTarget("settings.background");else->Modifier}).clip(RoundedCornerShape(16.dp)).settingsPress(onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = Muted, modifier = Modifier.size(20.dp))
        SettingsText(title, color = Ink, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) SettingsText(value, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 128.dp))
        Icon(Icons.Outlined.ChevronRight, null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsSheetTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsText(title, color = Ink, style = AppTextStyles.sectionTitle)
        SettingsText(subtitle, color = Muted, style = AppTextStyles.pageSubtitle)
    }
}

@Composable
private fun SettingsChoice(title: String, selected: Boolean, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Mint else Bg, tween(180), label = "settingsChoiceColor")
    Row(
        Modifier.fillMaxWidth().testTag(tag).semantics { this.selected = selected }.clip(RoundedCornerShape(16.dp)).then(if(LocalLiquidEnabled.current)Modifier.liveGlass(RoundedCornerShape(16.dp),background,LocalGlassLayers.current.page,window=true).background(background.copy(alpha=.55f))else Modifier.background(background)).settingsPress(onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) Brand else Muted, modifier = Modifier.size(22.dp))
        SettingsText(title, color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (selected) Icon(Icons.Outlined.CheckCircleOutline, null, tint = Brand, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SettingsDone(onClick: () -> Unit) {
    GlassFilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp)) { SettingsText(settingsCopy("完成", "Done")) }
}

@Composable
private fun Modifier.settingsPress(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .975f else 1f, spring(dampingRatio = .85f, stiffness = 500f), label = "settingsPress")
    return graphicsLayer { scaleX = scale; scaleY = scale }.clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
}


@Composable private fun AdminDeviceLoginCard(){
    val context=LocalContext.current;val scope=rememberCoroutineScope();var code by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    SettingsPanel {
        SettingsText(settingsCopy("管理后台登录码","Admin sign-in code"),style=MaterialTheme.typography.titleMedium)
        SettingsText(settingsCopy("在已登录的设备上验证身份，无需重新输入账号密码。","Verify with this signed-in device without entering your account password again."),color=Muted)
        GlassButton(onClick={busy=true;error=null;scope.launch{runCatching{CloudAccountClient(context).adminLoginCode()}.onSuccess{code=it}.onFailure{error=settingsStatus(it.message.orEmpty(),"Unable to create a code. Please try again.")};busy=false}},enabled=!busy){SettingsText(if(busy)settingsCopy("正在生成…","Creating…")else settingsCopy("生成一次性登录码","Create one-time code"))}
        code?.let {value->
            androidx.compose.foundation.text.selection.SelectionContainer {SettingsText(value.chunked(4).joinToString(" "),fontSize=22.sp,fontWeight=FontWeight.SemiBold,color=Brand)}
            SettingsText(settingsCopy("5 分钟有效，仅使用一次。请勿分享给他人。","Valid for 5 minutes, one use. Do not share this code."),color=Muted)
            Row{TextButton(onClick={context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Admin login",value))}){SettingsText(settingsCopy("复制","Copy"))};TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://kejian.im/admin")))}){SettingsText(settingsCopy("打开管理后台","Open admin"))}}
        }
        error?.let {SettingsText(it,color=MaterialTheme.colorScheme.error)}
    }
}
