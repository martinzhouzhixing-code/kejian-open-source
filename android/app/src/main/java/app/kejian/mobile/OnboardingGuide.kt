package app.kejian.mobile

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background

import android.animation.ValueAnimator
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.roundToInt

/** Targets belong to the real screens. The guide never dispatches input to these controls. */
@Stable class OnboardingTargets {
    val bounds = mutableStateMapOf<String, Rect>()
    var panelBottomPx by mutableFloatStateOf(0f)
    var activeKey by mutableStateOf<String?>(null)
    var scene by mutableIntStateOf(0)
    var beat by mutableIntStateOf(0)
    var playing by mutableStateOf(true)
}
val LocalOnboardingTargets = staticCompositionLocalOf<OnboardingTargets?> { null }

fun Modifier.onboardingTarget(key: String): Modifier = composed {
    val targets = LocalOnboardingTargets.current
    if (targets == null) this else {
        val requester = remember { BringIntoViewRequester() }
        DisposableEffect(targets, key) { onDispose { targets.bounds.remove(key) } }
        LaunchedEffect(targets.activeKey) {
            if (targets.activeKey == key) { delay(260); runCatching { requester.bringIntoView() } }
        }
        this.bringIntoViewRequester(requester).onGloballyPositioned { targets.bounds[key] = it.boundsInRoot() }
    }
}

data class OnboardingStep(val route: String, val target: String, val titleZh: String, val titleEn: String, val bodyZh: String, val bodyEn: String, val gesture: String = "tap")

val onboardingSteps = listOf(
    OnboardingStep("home", "home.grid", "日程就在这里", "Your day, in one place", "课程、会议和个人安排都能放进表格。点模块查看详情；双指可以调整行距和列距。", "Classes, meetings and personal plans share this grid. Tap a card for details; pinch to adjust row height and column width.", "pinch"),
    OnboardingStep("home", "home.edit", "先编辑，后保存", "Edit first, save when ready", "点编辑后可从加号创建或拖出模块，长按模块可移动。复制保留原名称；删除、批量选择和撤销都在编辑模式中。保存后才更新提醒和小组件。", "Edit reveals create, drag, duplicate, delete, batch selection and undo. Duplicates keep the original name. Reminders and widgets update after you save."),
    OnboardingStep("home", "home.deadlines", "截止日属于日期", "Deadlines belong to dates", "在编辑模式点加号，选择截止日。它可以独立存在，也能关联课程；标记放在日期栏，点它查看内容，不会挤进凌晨的时间轴。", "In Edit, tap + and choose Deadline. It may stand alone or link to an event. A date-header marker opens its details without occupying a midnight time slot."),
    OnboardingStep("recognize", "ai.upload", "把文件交给 AI", "Bring your schedule to AI", "上传完整课表图片，或选择 PDF、Excel。文档先在本机后台转换，再自动上传，避免一直停留在这里等待。图片尽量保留完整时间轴。", "Upload a complete schedule image, PDF or Excel file. Documents convert locally in the background before uploading. Keep the full time axis in schedule images."),
    OnboardingStep("recognize", "ai.composer", "用一句话改日程", "Make changes in one sentence", "例如：把周三的会议改到 14:15，或给周五添加项目截止日。识别和命令都会先预览；处理中可点停止，不会应用尚未确认的结果。", "Try: Move Wednesday’s meeting to 14:15, or add a project deadline on Friday. Recognition and commands are previewed first. Stop cancels processing without applying unconfirmed changes."),
    OnboardingStep("preview", "preview.grid", "用真实表格核对", "Review the actual grid", "这是安全的示例预览。左右滑动和按周查看，与主页的模块完全相同；切换修改前、修改后，检查新增、替换、删除和截止日。只有确认后才修改真实日程。", "This is a safe sample preview using the same cards as Home. Scroll, change weeks and switch Before / After to inspect events and deadlines. Only confirmation changes your real schedule.", "swipe"),
    OnboardingStep("recognize", "ai.history", "任务历史留在本机", "History stays on this device", "对话、任务状态和结果会留在这台设备上，下次打开还能看到。它们不会作为聊天历史上传服务器；需要时可清空记录。", "Conversations, task status and results stay on this device between visits. They are not uploaded as chat history; clear them whenever you want."),
    OnboardingStep("record", "record.capture", "随时暂停，接着记录", "Pause, then pick up again", "暂停不计时，实际录满 10 秒才能结束。开始后可切换应用或锁屏；允许通知后可在通知中操作。强停或省电限制可能中断录音。结束后只保存本机。此处为演示，不会打开麦克风。", "Pause freezes the clock; record at least 10 seconds excluding pauses. Switch apps or lock the screen after starting. Allow notification controls. Force-stop or battery limits may interrupt capture. Audio saves locally. This demo never opens your microphone."),
    OnboardingStep("record", "record.library", "每份录音，各有进度", "A library with live status", "录音列表会显示每个文件的上传、排队、转录和总结进度。点文件进入详情操作，后台任务运行时仍可浏览其他页面。", "Each recording row shows its own upload, queue, transcription and summary status. Tap a file for actions. Background work can continue while you use other pages."),
    OnboardingStep("record_detail", "record.detail.actions", "在文件详情开始转写", "Start from recording details", "先选总结语言，可填写关注重点等要求。真实文件点击转写后才上传；完整逐字稿一次交给模型总结，完成时通知你。此处为离线演示，服务器不会永久保存音频。", "Choose a summary language and optional instructions first. Transcribe uploads real files; the complete transcript is summarized in one context. You are notified when ready. This demo stays offline; server audio is temporary."),
    OnboardingStep("record_detail", "record.detail.summary", "看完整的课堂笔记", "Read detailed lesson notes", "笔记突出重点，保留必要条件、作业与提醒，原话可查看逐字稿。可调整语言和要求再总结：重新总结使用个人 API Key，费用按服务商规则计算。AI 结果仍需核对。", "Notes prioritize core knowledge, conditions, assignments and reminders. The transcript keeps the original wording. Change language or instructions and regenerate: regeneration uses your personal API Key and provider billing. Verify important details."),
    OnboardingStep("record_detail", "record.detail.insights", "围绕这份笔记追问", "Ask about these notes", "向笔记提问可以解释知识点、整理复习线索，并区分原文引用与联网补充。这是只读示例，不会发送问题。真实提问使用个人 API Key 额度；模型未在资料中找到答案时应明确说明，重要结论请核对。", "Ask your notes can explain concepts and help you review, with note citations and clearly labeled online references. This is a read-only example and sends no questions. Real questions use membership AI tokens. Missing evidence should be stated clearly; verify important conclusions."),
    OnboardingStep("record_detail", "record.detail.link", "随时更改关联课程", "Link it, and change your mind", "从主页同款表格点选课程。选错后可重新关联，也可不关联；课程展开后的入口会打开这份资料的完整页面。音频可只留本地，或自行导出。", "Choose an event from the same timetable as Home. Relink it if needed, or keep it independent. The event card opens this full notes page. Keep audio locally or export it yourself."),
    OnboardingStep("record_detail", "record.detail.mindmap", "把知识整理成思维导图", "Turn notes into a mind map", "转录总结完成后，点生成思维导图，把主题、知识点和关联分支展开。真实生成会消耗个人 API Key 额度，不占录音转写分钟数。", "After transcription and summary finish, generate a mind map of topics, concepts and related branches. Real generation uses the membership token allowance, not transcription minutes."),
    OnboardingStep("record_mindmap", "record.mindmap.canvas", "一张图，串起整堂课", "Connect the whole lesson", "这是示例导图。沿分支拖动、缩放，点节点看细节；收起详情或切换列表后仍保留视角。点全图才会重新适配。导图是复习索引，完整内容仍在笔记和逐字稿中。", "This is a sample map. Drag, zoom and tap nodes for details. Closing details or switching to the list keeps your view. Fit shows the whole map again. The complete information remains in your notes and transcript.", "pinch"),
    OnboardingStep("widgets", "widgets.preview", "把下一项安排放到桌面", "Keep your next event on Home", "这里预览桌面小组件。导入图片不改变原图亮度，本机判断黑字或白字，也可手动切换。背景、透明度、圆角和留白按你的偏好调整；添加仍需桌面系统支持并由你确认，教程不会创建组件。", "Preview your home-screen widget here. Image brightness stays unchanged; text switches to black or white locally, with manual override. Adjust backgrounds, corners and padding. Adding requires launcher support and your confirmation; this guide creates no widgets."),
    OnboardingStep("settings", "settings.skin.demo", "按你的习惯使用", "Make Kejian yours", "在设置里切换中英文、管理个人 API Key、备份、提醒和更新。以后也能从设置重新播放本教程。所有演示都不会修改你的资料。", "Settings contains language, personal API Key and local backups, reminders and updates. Replay this guide here anytime. None of these demonstrations changes your data."),
    OnboardingStep("settings", "settings.background", "离开页面，任务继续", "Leave the page, keep the task", "任务开始后可切换页面或应用。若锁屏时反复中断，到这里检查通知、电池和后台限制。系统设置只会由你主动打开；强停、系统时限和兼容环境仍可能中断任务。", "After starting a task, switch pages or apps. If screen-off interruptions persist, check notifications, battery and background limits here. Settings open only when you choose. Force-stop, system time limits and compatibility environments can still interrupt work.")
)

// Six public steps. Internal shots are timed automatically, never numbered or user-operated.
internal val onboardingChapterStarts=listOf(0,3,7,10,15,16)
internal fun onboardingChapter(index:Int)=onboardingChapterStarts.indexOfLast {it<=index}.coerceAtLeast(0)

/** Independent guide progress: navigating the tour never changes course drafts or user files. */
class OnboardingProgress(context: Context) {
    private val prefs = context.getSharedPreferences("kejian_guide_21", Context.MODE_PRIVATE)
    fun read() = prefs.getInt("step", 0).coerceIn(0, onboardingSteps.lastIndex)
    fun save(step: Int) { prefs.edit().putInt("step", step.coerceIn(0, onboardingSteps.lastIndex)).apply() }
    fun reset() { prefs.edit().putInt("step", 0).apply() }
}

fun onboardingRecordingSample(): LessonNote {
    val en = AppLanguage.english
    return LessonNote(id = "tutorial-recording", title = if (en) "Sample · Design thinking" else "演示 · 设计思维", durationSeconds = 2700,
        summary = if (en) "The lesson moves from understanding a problem to testing a solution.\n\nStart by describing who experiences the problem and what they need. Separate observations from assumptions.\n\nCompare several approaches before choosing one. A small prototype should test a specific assumption, not imitate a finished product.\n\nUse feedback to revise the next experiment, and document what changed and why." else "本节课从理解问题出发，逐步讲到如何验证解决方案。\n\n先明确谁遇到了问题、对方真正需要什么，区分已经观察到的事实与尚未验证的假设。\n\n比较几种方案后再作选择。小型原型要验证一个具体假设，不必模拟完整产品。\n\n根据反馈调整下一次实验，并记录改动了什么、为什么改动。",
        keyPoints = if (en) listOf(
            "Define the problem\nStart with the user, context and unmet need.\n- Identify who experiences the problem.\n- Describe what they need before choosing a solution.",
            "Separate observations and assumptions\nAn observation describes what happened; an assumption explains why and needs testing.\n- Label observed facts separately from possible explanations.\n  - Example: a missed appointment may reflect an unclear reminder rather than a lack of interest.",
            "Test a prototype\nCompare several approaches before choosing one.\n- Use a small experiment to test one specific uncertainty.\n- A prototype does not need to imitate a finished product.",
            "Iterate from feedback\nUse feedback to revise the next experiment.\n- Keep a written record of what changed and why.\n- Retain the evidence behind each design decision."
        ) else listOf(
            "定义问题\n从用户、使用情境和未被满足的需求出发。\n- 先明确是谁遇到了问题。\n- 在选择方案前，描述对方真正需要什么。",
            "区分观察和假设\n观察描述发生了什么；假设解释为什么，还需要验证。\n- 把观察到的事实与可能的解释分别标记。\n  - 例如：错过预约可能是提醒不够清楚，而不一定是对方不感兴趣。",
            "验证原型\n比较几种方案后再作选择。\n- 用小型实验验证一个具体的不确定因素。\n- 原型不必模拟完整产品。",
            "根据反馈迭代\n根据反馈调整下一次实验。\n- 记录改动了什么，以及为什么改动。\n- 保留每次设计决策的证据。"
        ),
        actionItems = if (en) listOf("Before Friday: submit a one-page problem statement and one prototype.", "Reminder: anonymize interview notes and ask permission before recording.") else listOf("周五前提交：一页问题定义，以及一份原型。", "老师提醒：访谈记录应匿名，录音前先征得对方同意。"),
        transcript = if (en) "Today we will distinguish observations from assumptions. An observation describes what happened; an assumption explains why, and needs testing. For example, a missed appointment may reflect a confusing reminder rather than a lack of interest. Your assignment is to write a problem statement and create one prototype before Friday. Remember to anonymize interview notes and ask permission before recording." else "今天我们先区分观察和假设。观察描述发生了什么；假设解释为什么，还需要验证。比如错过预约，也许是提醒不够清楚，而不是对方不感兴趣。作业是在周五前完成问题定义和一个原型。记得把访谈记录匿名处理，录音前先征得对方同意。")
}

/** Isolated immutable example used only by the real preview renderer, never inserted into the store. */
fun onboardingPreviewSample(): Pair<AppData, ImportPreview> {
    val en = AppLanguage.english; val week = monday(LocalDate.now())
    val seminar = Course(id = "tutorial-seminar", name = if (en) "Design seminar" else "设计研讨", day = 3, start = 570, end = 645, color = 0, weeks = setOf(1), room = "A201")
    val meeting = Course(id = "tutorial-meeting", name = if (en) "Project meeting" else "项目会议", day = 4, start = 840, end = 900, color = 2, weeks = setOf(1), room = "B302")
    val original = AppData(courses = listOf(seminar, meeting), settings = Settings(termStart = week, termName = if (en) "Sample week" else "演示周", language = if (en) "en" else "zh"))
    val revised = seminar.copy(start = 615, end = 690)
    val added = Course(id = "tutorial-review", name = if (en) "Project review" else "项目复盘", day = 5, start = 885, end = 945, color = 4, weeks = setOf(1))
    val due = Deadline(id = "tutorial-due", title = if (en) "Prototype submission" else "原型提交", dueDate = week.plusDays(4), details = if (en) "Submit the prototype and a one-page problem statement." else "提交原型及一页问题定义。", color = 4)
    val after = original.copy(courses = listOf(revised, added), deadlines = listOf(due))
    return original to ImportPreview(after.courses, emptyList(), backup = after, aiTask = true, deadlines = after.deadlines)
}

@Composable internal fun guidePanelHeight():Dp=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp*.36f).coerceIn(220.dp,310.dp)

@Composable fun PageOnboardingOverlay(stepIndex: Int, targets: OnboardingTargets, onStep: (Int) -> Unit, onDone: () -> Unit) {
    val step = onboardingSteps[stepIndex]
    val chapter=onboardingChapter(stepIndex)
    val first=onboardingChapterStarts[chapter]
    val end=(onboardingChapterStarts.getOrNull(chapter+1)?:onboardingSteps.size)-1
    var paused by remember {mutableStateOf(false)}
    val english = AppLanguage.english
    var replay by remember { mutableIntStateOf(0) }
    val shotProgress=remember(stepIndex,replay){Animatable(0f)}
    val duration=listOf(9000,9000,6500,5000,5500,7000,3000,11000,4500,7500,6000,6500,6500,3500,6000,14000,7500,4500)[stepIndex]
    val motion = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) { motion.snapTo(.5f); return@LaunchedEffect }
        while (true) { motion.snapTo(0f); motion.animateTo(1f, tween(1700, easing = LinearEasing)); delay(450) }
    }
    LaunchedEffect(stepIndex,replay){targets.scene=stepIndex;targets.beat=0}
    LaunchedEffect(stepIndex,paused,replay){
        targets.playing=!paused
        if(!paused){
            shotProgress.animateTo(1f,tween(((1f-shotProgress.value)*duration*1.15f).toInt(),easing=LinearEasing))
            if(stepIndex<end)onStep(stepIndex+1)
        }
    }
    LaunchedEffect(stepIndex,replay){snapshotFlow{(shotProgress.value*duration/1000).toInt()}.collect {targets.beat=it}}

    BackHandler { if (chapter > 0) onStep(onboardingChapterStarts[chapter-1]) else onDone() }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("page-onboarding").pointerInput(stepIndex) { detectTapGestures { /* Intentional shield: never send tutorial taps to real controls. */ } }) {
        val density = LocalDensity.current
        val maxX = with(density) { maxWidth.toPx() }; val maxY = with(density) { maxHeight.toPx() }
        val raw = targets.bounds[step.target]?.let {if(step.target=="home.deadlines")Rect(it.right-it.width*.18f,it.top,it.right,it.bottom)else it}
        val target = raw?.let { Rect(it.left.coerceIn(8f, maxX - 8f), it.top.coerceIn(8f, maxY - 8f), it.right.coerceIn(8f, maxX - 8f), it.bottom.coerceIn(8f, maxY - 8f)) }?.takeIf { it.width > 4 && it.height > 4 }
        var lastTarget by remember { mutableStateOf(Offset(maxX*.5f,maxY*.45f)) }
        val travel=if(step.gesture=="swipe"&&target!=null) kotlin.math.sin(motion.value*Math.PI*2).toFloat()*minOf(target.width*.25f,50.dp.value*density.density) else 0f
        LaunchedEffect(target,travel){if(target!=null)lastTarget=target.center+Offset(travel,0f)}
        val cursor by animateOffsetAsState(lastTarget,tween(175,easing=FastOutSlowInEasing),label="guideCursor")
        val guideAccent=Brand
        Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply { fillType = PathFillType.EvenOdd; addRect(Rect(Offset.Zero, size)); target?.let { addRoundRect(RoundRect(it.inflate(5.dp.toPx()), CornerRadius(if(step.target=="home.edit")it.height/2 else minOf(18.dp.toPx(),it.height/2)))) } }
            drawPath(path, Color.Black.copy(alpha = .28f))
            target?.let { rect ->
                drawRoundRect(guideAccent.copy(alpha = .22f + .10f * (1f - motion.value)), rect.topLeft, rect.size, CornerRadius(if(step.target=="home.edit")rect.height/2 else minOf(18.dp.toPx(),rect.height/2)), style = Stroke(.8.dp.toPx()))
            }
            // Keep the circle visible at its last position while the next screen lays out.
            run {
                // Gesture movement is part of the continuous animated cursor target.
                val center = cursor
                val radius = (13f + 18f * motion.value).dp.toPx()
                drawCircle(guideAccent.copy(alpha = .35f * (1f - motion.value)), radius, center)
                drawCircle(Color.White.copy(alpha=.32f),17.dp.toPx(),center)
                drawCircle(Color.White.copy(alpha=.75f),17.dp.toPx(),center,style=Stroke(1.4.dp.toPx()))
                drawCircle(Color.White.copy(alpha=.42f),5.dp.toPx(),center)
                if (step.gesture == "pinch") {
                    val gap = (30 + 22 * motion.value).dp.toPx()
                    drawCircle(Color.White.copy(alpha = .82f), 6.dp.toPx(), center + Offset(gap, gap))
                    drawLine(Color.White.copy(alpha = .35f), center, center + Offset(gap, gap), 1.dp.toPx())
                }
            }
        }
        val guideMaxHeight=guidePanelHeight()
        // The real page reserves this same bottom region: captions never cover a demo.
        Box(Modifier.align(Alignment.BottomCenter).height(guideMaxHeight).onGloballyPositioned { targets.panelBottomPx = it.boundsInRoot().bottom }.navigationBarsPadding().padding(12.dp).widthIn(max = 460.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).testTag("guide-card")) {
            Box(Modifier.matchParentSize().liveGlass(RoundedCornerShape(24.dp),SurfaceColor,LocalGlassLayers.current.page,10.dp,tintAlpha=if(LocalAppPalette.current.dark).52f else .40f).background(if(LocalGlassEnabled.current)Color.Transparent else SurfaceColor))
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (english) "QUICK TOUR  ${chapter + 1}/6" else "操作引导  ${chapter + 1}/6", Modifier.weight(1f), color = Brand, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = onDone) { Text(if (english) "Skip" else "跳过") }
                }
                LinearProgressIndicator(progress={((stepIndex-first)+shotProgress.value)/(end-first+1)},modifier=Modifier.fillMaxWidth(),color=Brand,trackColor=Mint)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(if(english)step.titleEn else step.titleZh,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text(guideFilmCaption(stepIndex,targets.beat,english),color=Ink,style=MaterialTheme.typography.bodyLarge)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { replay++;paused=false;onStep(first) }) { Text(if (english) "Replay" else "重播") }
                    TextButton(onClick={paused=!paused}){Text(if(paused){if(english)"Play"else"播放"}else if(english)"Pause"else"暂停")}
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onStep(onboardingChapterStarts[(chapter-1).coerceAtLeast(0)]) }, enabled = chapter > 0) { Text(if (english) "Back" else "上一步") }
                    GlassButton(onClick = { if (chapter == 5) onDone() else onStep(onboardingChapterStarts[chapter+1]) }, modifier = Modifier.testTag("guide-next")) { Text(if (chapter == 5) { if (english) "Done" else "完成" } else if (english) "Next" else "下一步") }
                }
            }
        }
    }
}


internal fun guideFilmCaption(scene:Int,beat:Int,en:Boolean):String {
    val pairs=when(scene){
        0->if(beat<3)"轻点课程模块" to "Tap an event" else if(beat<6)"模块原地展开，查看完整详情" to "The card expands into full details" else "点空白处，模块收回原位" to "Tap outside to return the card"
        1->if(beat<3)"进入编辑，选中模块" to "Enter Edit and select a card" else if(beat<6)"拖动调整时间，准确到分钟" to "Drag to move the event, to the minute" else "保存安排，再继续浏览" to "Save, then return to browsing"
        2->if(beat<2)"截止日放在日期栏，不占用上课时间" to "Deadlines belong in the date header" else "周五出现截止日标记；使用时点它即可查看事项" to "Friday now has a deadline marker; tap it to read the details"
        3->if(beat<2)"选择完整图片、PDF 或 Excel" to "Choose a complete image, PDF or Excel" else "本地转换 → 上传识别；可随时停止" to "Convert locally → recognize; stop whenever needed"
        4->"输入一句话 → 发送 → 生成修改预览" to "Type a request → send → preview the changes"
        5->if(beat<3)"切换修改前后，核对精确时间" to "Compare Before / After and check exact times" else "确认后才应用，不会直接覆盖日程" to "Only confirmation applies the changes"
        6->"任务完成，对话与结果保留在本机" to "Done. Conversations and results stay locally"
        7->if(beat<2)"开始录音" to "Start recording" else if(beat<5)"录音中，波形随声音变化" to "Recording, with a live waveform" else if(beat<7)"暂停，计时停止" to "Pause freezes the timer" else if(beat<9)"继续录音，可切换应用或锁屏" to "Resume; switch apps or lock the screen" else "结束录音，保存到本机列表" to "Stop and save to your local library"
        8->"点击刚保存的录音，进入文件详情" to "Open the saved recording"
        9->if(beat<3)"选择语言，填写总结要求" to "Choose the language and your instructions" else "上传 → 排队 → 转写 → 总结，后台继续" to "Upload → queue → transcribe → summarize, in background"
        10->"重点、知识点、作业分开呈现；逐字稿随时核对" to "Review key concepts, assignments and the original transcript"
        11->"输入问题 → 向笔记提问 → 查看原文与网络引用" to "Ask a question → read the answer → check citations"
        12->"打开关联表格 → 选择课程 → 完成；随时可更换" to "Open the grid → choose an event → linked; change it anytime"
        13->"点生成思维导图，使用个人 API Key" to "Generate a mind map using your API Key"
        14->"导图展开 → 移动缩放 → 点节点看细节" to "Explore the map → pan and zoom → inspect a node"
        15->if(beat<4)"选一个尺寸" to "Choose a widget size" else if(beat<8)"换上图片，自动匹配黑白文字" to "Add an image with automatic text contrast" else if(beat<11)"开启磨砂，文字依旧清晰" to "Switch on frosted glass; keep text sharp" else "交由系统确认，添加到桌面" to "Confirm with your launcher to add the widget"
        16->"选择皮肤或照片 → 调节模糊与明暗 → 可选液态玻璃" to "Choose skin or photo → blur / brightness → optional liquid glass"
        else->"配置个人 API Key；允许通知和后台运行，让任务继续" to "Set your API Key; allow notifications and background work"
    }
    return if(en)pairs.second else pairs.first
}


fun guideAiMessages(guide:OnboardingTargets):List<AiChatMessage>{
    val en=AppLanguage.english
    return when(guide.scene){
        3->if(guide.beat<2)emptyList()else listOf(AiChatMessage(true,if(en)"Uploaded a complete schedule"else"已上传完整课表图片"),AiChatMessage(false,if(en)"Reading days and exact times…"else"正在核对星期与准确时间…",pending=true))
        4->if(guide.beat<4)emptyList()else listOf(AiChatMessage(true,if(en)"Move Wednesday's seminar to 10:15"else"把周三的研讨课改到 10:15"),AiChatMessage(false,if(en)"Preparing a preview…"else"正在生成预览…",pending=true))
        else->listOf(AiChatMessage(true,if(en)"Move Wednesday's seminar to 10:15"else"把周三的研讨课改到 10:15"),AiChatMessage(false,if(en)"Applied after confirmation. Saved on this device."else"确认后已应用，记录保存在本机。"))
    }
}
fun guideWidgetData(data:AppData,guide:OnboardingTargets,context:Context):AppData=data.copy(settings=data.settings.copy(widgetBackgroundMode=if(guide.beat<4)"solid"else"image",widgetBackgroundUri="android.resource://${context.packageName}/${R.raw.skin_ocean}",widgetImageLuminance=.42f,widgetTextMode="auto",widgetFrosted=guide.beat>=8))
