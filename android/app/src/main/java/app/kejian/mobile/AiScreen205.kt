package app.kejian.mobile

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as PlainText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun aiCopy205(zh: String, en: String) = if (AppLanguage.english) en else zh

/** These two application receipts share the outgoing bubble style with user text. */
private fun aiOutgoingText205(value: String): String {
    if (!AppLanguage.english) return value
    if (value == "已上传一张课表图片") return "Uploaded a schedule image"
    if (value == "已选择文件") return "Selected file"
    if (value.startsWith("已选择文件 ")) return "Selected file " + value.removePrefix("已选择文件 ")
    return value
}

private val aiIntroductions205 = setOf(
    "可以发送课表截图，或用一句话新增、替换、删除课程与截止日。所有改动都会先预览。",
    "可以发送课表截图，或用一句话新增、替换、删除课程。所有改动都会先预览。",
    "Send a schedule screenshot or add, replace or delete events and deadlines in one sentence. Every change is previewed first."
)

/** The AI remains an operation generator; this surface never applies or submits an example. */
@Composable
fun RecognitionScreen(
    loggedIn: Boolean,
    profile: AccountProfile?,
    messages: List<AiChatMessage>,
    busy: Boolean = false,
    canStop: Boolean = false,
    onStop: () -> Unit = {},
    onHistory: () -> Unit = {},
    onAccount: () -> Unit,
    onChooseImage: () -> Unit,
    onChooseFile: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val guide=LocalOnboardingTargets.current
    val guideActive=guide?.activeKey?.startsWith("ai.")==true
    var pageTop by remember { mutableFloatStateOf(0f) }
    val density=LocalDensity.current
    LaunchedEffect(guide?.scene,guide?.beat){if(guide?.scene==4){val sample=if(AppLanguage.english)"Move the seminar to 10:15 on Wednesday"else "把周三的研讨课改到 10:15";val count=(sample.length*(guide.beat/3f)).toInt().coerceIn(0,sample.length);draft=TextFieldValue(if(guide.beat<4)sample.take(count)else "")}}
    var membership by rememberSaveable { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    val motion = ValueAnimator.areAnimatorsEnabled()
    val taskBusy = busy || BackgroundTaskRuntime.documentRunning
    val enterProgress by animateFloatAsState(if (entered) 1f else 0f, tween(if (motion) 260 else 0), label = "aiEntrance205")
    val listState = rememberLazyListState()
    // Stable message identity lets pending content become its result without two overlapping layers.
    // The introduction is hidden only in the presentation; the message log is unchanged.
    val visibleMessages = messages.withIndex().filterNot { (index, item) ->
        index == 0 && !item.fromUser && !item.pending && !item.error && item.text in aiIntroductions205
    }
    val showWelcome = visibleMessages.isEmpty() && !taskBusy
    val hasPendingMessage = visibleMessages.any { it.value.pending }
    val extraWorkingItem = taskBusy && !hasPendingMessage
    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(visibleMessages.size, visibleMessages.lastOrNull()?.value, extraWorkingItem) {
        if (!showWelcome) {
            withFrameNanos { }
            val last = (visibleMessages.size + if (extraWorkingItem) 1 else 0) - 1
            if (last >= 0) {
                if (motion) listState.animateScrollToItem(last) else listState.scrollToItem(last)
            }
        }
    }
    fun fillExample(zh: String, en: String) {
        if (taskBusy) return
        val example = aiCopy205(zh, en)
        draft = TextFieldValue(example, TextRange(example.length))
    }
    fun submit() {
        if (taskBusy || draft.text.isBlank()) return
        if (!loggedIn) { onAccount(); return }
        val value = draft.text.trim()
        draft = TextFieldValue()
        onSubmit(value)
    }
    Column(Modifier.fillMaxSize().padding(top=LocalPageTopInset.current).padding(bottom=LocalDockInset.current).imePadding().onGloballyPositioned { pageTop=it.boundsInRoot().top }.graphicsLayer {
        alpha = enterProgress
        translationY = (1f - enterProgress) * 8.dp.toPx()
    }) {
        if(guideActive) Spacer(Modifier.height(with(density) { (guide!!.panelBottomPx-pageTop).coerceAtLeast(0f).toDp() }).testTag("ai-guide-clearance"))
        if(!guideActive) Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PlainText(aiCopy205("课间 AI", "Kejian AI"), color = Ink, style = AppTextStyles.pageTitle)
                PlainText(aiCopy205("先预览，再由你决定。", "Preview first. You stay in control."), color = Muted, style = AppTextStyles.pageSubtitle)
            }
            IconButton(onClick=onHistory,modifier=Modifier.onboardingTarget("ai.history").testTag("ai_history")){
                Icon(Icons.Outlined.History,aiCopy205("本机任务历史","Local task history"),tint=Brand)
            }
            val quota = when {
                !loggedIn -> aiCopy205("配置 API Key", "Set API Key")
                profile?.isDeveloper == true -> "API Key"
                profile?.isMember == true -> "${if (profile.role == "pro") "Pro" else "Plus"} · ${profile.aiPercentRemaining}%"
                else -> aiCopy205("会员与额度", "Membership")
            }
            GlassSurface(
                onClick = onAccount,
                shape = RoundedCornerShape(16.dp), color = Mint,
                modifier = Modifier.widthIn(max = 136.dp).heightIn(min = 40.dp)
            ) {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    PlainText(quota, color = Brand, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("ai_messages"), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showWelcome) item(key = "welcome") {
                AiWelcome205(
                    onChooseImage = { if (!taskBusy) onChooseImage() },
                    onReschedule = { fillExample("把周五的会议改到 14:30", "Move Friday's meeting to 14:30") },
                    onDeadline = { fillExample("项目下周五截止", "Set the project deadline for next Friday") },
                    motion = motion
                )
            }
            items(visibleMessages, key = { "message:${it.value.id}" }) { indexed ->
                AiMessage205(indexed.value, Modifier.animateItem(
                    fadeInSpec = tween(if (motion) 240 else 0),
                    placementSpec = null,
                    fadeOutSpec = null
                ), motion)
            }
            if (extraWorkingItem) item(key = "background-working") {
                AiMessage205(AiChatMessage(false,
                    if (BackgroundTaskRuntime.documentRunning)
                        aiCopy205("正在准备文件，完成后自动上传。你可以离开此页面。", "Preparing your file for upload. You may leave this page.")
                    else aiCopy205("正在核对你的安排…", "Reviewing your schedule…"), pending = true), motion = motion)
            }
        }
        GlassSurface(color = SurfaceColor) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSurface(shape = RoundedCornerShape(22.dp), color = Bg, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { value -> draft = if (value.text.length <= 1000) value else value.copy(text = value.text.take(1000), selection = TextRange(1000)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp).testTag("ai_composer").onboardingTarget("ai.composer"),
                            maxLines = 4,
                            textStyle = TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 22.sp),
                            cursorBrush = SolidColor(Brand),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth().padding(vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                                    if (draft.text.isEmpty()) PlainText(aiCopy205("描述你的日程任务…", "Describe a schedule task…"), color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
                                    inner()
                                }
                            }
                        )
                        val sendInteraction = remember { MutableInteractionSource() }
                        val sendPressed by sendInteraction.collectIsPressedAsState()
                        val sendScale by animateFloatAsState(if (sendPressed && motion) .97f else 1f, spring(dampingRatio = .85f, stiffness = 500f), label = "sendPress205")
                        AnimatedContent(
                            targetState = when { taskBusy && canStop -> "stop"; taskBusy -> "working"; else -> "send" },
                            transitionSpec = { fadeIn(tween(if (motion) 180 else 0)) togetherWith fadeOut(tween(if (motion) 120 else 0)) },
                            modifier = Modifier.size(48.dp), label = "aiAction205"
                        ) { action ->
                            when (action) {
                                "stop" -> FilledIconButton(
                                    onClick = onStop, modifier = Modifier.size(48.dp).testTag("ai_stop").onboardingTarget("ai.stop").graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                                    interactionSource = sendInteraction, shape = RoundedCornerShape(16.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Brand)
                                ) { Icon(Icons.Outlined.Stop, aiCopy205("停止识别", "Stop recognition"), Modifier.size(24.dp)) }
                                "working" -> GlassSurface(Modifier.size(48.dp), color = Mint.copy(alpha = .45f), shape = RoundedCornerShape(16.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (motion) CircularProgressIndicator(Modifier.size(20.dp), color = Brand, strokeWidth = 2.dp)
                                        else Icon(Icons.Outlined.Schedule, aiCopy205("正在完成操作", "Finishing the operation"), Modifier.size(22.dp), tint = Brand)
                                    }
                                }
                                else -> FilledIconButton(
                                    onClick = ::submit, enabled = draft.text.isNotBlank(),
                                    modifier = Modifier.size(48.dp).testTag("ai_send").graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                                    interactionSource = sendInteraction, shape = RoundedCornerShape(16.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Brand, disabledContainerColor = Mint.copy(alpha = .45f), disabledContentColor = Muted.copy(alpha = .45f))
                                ) { Icon(Icons.AutoMirrored.Outlined.Send, aiCopy205("发送", "Send"), Modifier.size(23.dp)) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AiAttachment205(aiCopy205("上传图片", "Add image"), Icons.Outlined.Image, !taskBusy, { if (!taskBusy) onChooseImage() }, Modifier.weight(1f).fillMaxHeight().onboardingTarget("ai.upload"), motion)
                    AiAttachment205(aiCopy205("选择文件", "Choose file"), Icons.Outlined.InsertDriveFile, !taskBusy, { if (!taskBusy) onChooseFile() }, Modifier.weight(1f).fillMaxHeight(), motion)
                }
            }
        }
    }
    if (membership) AiMembership205(profile) { membership = false }
}

@Composable private fun AiWelcome205(onChooseImage: () -> Unit, onReschedule: () -> Unit, onDeadline: () -> Unit, motion: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassSurface(Modifier.size(48.dp), shape = RoundedCornerShape(16.dp), color = Mint) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(24.dp), tint = Brand) }
        }
        PlainText(aiCopy205("安排放好，\n时间留给自己。", "Make room\nfor your day."), fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        PlainText(aiCopy205("导入课表，或说出想改的安排。\n每个结果都会先预览，再应用。", "Import a schedule or describe a change.\nReview every result before it is applied."), fontSize = 14.sp, lineHeight = 21.sp, color = Muted)
        Spacer(Modifier.height(4.dp))
        AiExample205(aiCopy205("从一张课表开始", "Start with a schedule"), aiCopy205("添加截图或日程文件", "Add a screenshot or document"), Icons.Outlined.Image, onChooseImage, motion = motion)
        AiExample205(aiCopy205("调整一个安排", "Move something around"), aiCopy205("把周五的会议改到 14:30", "Move Friday's meeting to 14:30"), Icons.Outlined.Event, onReschedule, Modifier.testTag("ai_sample_reschedule"), motion)
        AiExample205(aiCopy205("记住重要的截止日", "Never miss a deadline"), aiCopy205("项目下周五截止", "Project due next Friday"), Icons.Outlined.Schedule, onDeadline, Modifier.testTag("ai_sample_deadline"), motion)
    }
}

@Composable private fun AiExample205(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, motion: Boolean) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && motion) .98f else 1f, spring(dampingRatio = .85f, stiffness = 500f), label = "aiExamplePress205")
    GlassSurface(onClick = onClick, interactionSource = interactions, shape = RoundedCornerShape(18.dp), color = SurfaceColor,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(21.dp), tint = Brand)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PlainText(title, fontSize = 14.sp, lineHeight = 19.sp, color = Ink, fontWeight = FontWeight.Medium)
                PlainText(subtitle, fontSize = 12.sp, lineHeight = 17.sp, color = Muted)
            }
        }
    }
}

@Composable private fun AiAttachment205(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, motion: Boolean) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && motion) .97f else 1f, spring(dampingRatio = .85f, stiffness = 500f), label = "aiAttachmentPress205")
    GlassSurface(onClick = onClick, enabled = enabled, interactionSource = interactions, shape = RoundedCornerShape(16.dp), color = Bg,
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(19.dp), tint = if (enabled) Brand else Muted.copy(alpha = .5f))
            PlainText(label, fontSize = 13.sp, lineHeight = 18.sp, color = if (enabled) Brand else Muted.copy(alpha = .5f), fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun AiMessage205(item: AiChatMessage, modifier: Modifier = Modifier, motion: Boolean) {
    val error = MaterialTheme.colorScheme.error
    val surface = when { item.fromUser -> Brand; item.error -> error.copy(alpha = .055f); else -> SurfaceColor }
    Row(modifier.fillMaxWidth().animateContentSize(tween(if (motion) 240 else 0)).clipToBounds().testTag("ai-message-${item.id}"), horizontalArrangement = if (item.fromUser) Arrangement.End else Arrangement.Start) {
        GlassSurface(
            color = surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = if (item.fromUser) 6.dp else 20.dp, bottomStart = if (item.fromUser) 20.dp else 6.dp),
            border = if (item.error) BorderStroke(1.dp, error.copy(alpha = .20f)) else null,
            modifier = Modifier.widthIn(max = 326.dp)
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                if (!item.fromUser) {
                    Box(Modifier.size(20.dp).padding(top = 2.dp), contentAlignment = Alignment.Center) {
                        if (item.pending && motion) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.8.dp, color = Brand)
                        else Icon(when { item.pending -> Icons.Outlined.Schedule; item.error -> Icons.Outlined.ErrorOutline; item.text.contains("任务已完成") || item.text.contains("Task complete", true) -> Icons.Outlined.CheckCircleOutline; else -> Icons.Outlined.AutoAwesome }, null, Modifier.size(18.dp), tint = if (item.error) error else Brand)
                    }
                }
                PlainText(
                    // User-written text remains verbatim, even after changing the interface language.
                    text = if (item.fromUser) aiOutgoingText205(item.text) else localized(item.text),
                    color = if (item.fromUser) OnBrand else Ink,
                    fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

@Composable private fun AiMembership205(profile: AccountProfile?, onDismiss: () -> Unit) {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { PlainText(aiCopy205("会员与额度", "Membership & usage"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (profile?.isDeveloper == true) PlainText(aiCopy205("开发者账号 · 全部权益与额度无限", "Developer account · Unlimited access and usage"), color = Brand, fontSize = 14.sp)
                else if (profile?.isMember == true) PlainText(aiCopy205("本月 AI 剩余额度 ${profile.aiPercentRemaining}%", "${profile.aiPercentRemaining}% of this month's AI allowance remaining"), color = Brand, fontSize = 14.sp)
                PlainText(aiCopy205("Plus · ¥5.99 / 月", "Plus · ¥5.99 / month"), color = Brand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                PlainText(aiCopy205("每月 150 万 AI token、120 分钟录音转写总结、3 个云存档与完整小组件外观。", "1.5 million AI tokens and 120 minutes of transcription and summaries each month, 3 cloud saves, and full widget customization."), fontSize = 14.sp, lineHeight = 21.sp)
                HorizontalDivider()
                PlainText(aiCopy205("Pro · ¥29.9 / 月", "Pro · ¥29.9 / month"), color = Brand, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                PlainText(aiCopy205("包含 Plus 全部功能，每月 AI 额度提升至 400 万 token，录音转写总结时长提升至 1120 分钟。", "All Plus features, with 4 million AI tokens and 1,0120 minutes of transcription and summaries each month."), fontSize = 14.sp, lineHeight = 21.sp)
                PlainText(aiCopy205("Pro 可另购：¥10 / 240 分钟。基础录音和课程关联始终免费。", "Pro top-up: ¥10 for 240 minutes. Basic recording and event attachment are always free."), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                if (profile?.isDeveloper != true) PlainText(aiCopy205("支付接入完成后开放购买。", "Purchases will open when payment integration is ready."), color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { PlainText(aiCopy205("知道了", "Got it")) } }
    )
}
