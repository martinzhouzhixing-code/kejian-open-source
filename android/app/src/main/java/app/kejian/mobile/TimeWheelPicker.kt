package app.kejian.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

private val WheelRowHeight = 42.dp

@Composable
private fun NumberWheel(
    values: IntRange,
    initial: Int,
    suffix: String,
    description: String,
    modifier: Modifier = Modifier,
    onChanged: (Int) -> Unit
) {
    val items = remember(values.first, values.last) { values.toList() }
    val initialIndex = items.indexOf(initial).coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val haptics = LocalHapticFeedback.current
    val selectedIndex by remember { derivedStateOf { state.firstVisibleItemIndex.coerceIn(items.indices) } }

    LaunchedEffect(state, items) {
        snapshotFlow { state.firstVisibleItemIndex.coerceIn(items.indices) }
            .distinctUntilChanged()
            .collect { index ->
                onChanged(items[index])
                if (state.isScrollInProgress) {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                }
            }
    }

    Box(
        modifier
            .height(WheelRowHeight * 5)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = WheelRowHeight * 2),
            flingBehavior = rememberSnapFlingBehavior(state),
            horizontalAlignment = Alignment.CenterHorizontally,
            overscrollEffect = rememberOverscrollEffect()
        ) {
            items(items.size) { index ->
                val distance = kotlin.math.abs(index - selectedIndex)
                val targetAlpha = when (distance) { 0 -> 1f; 1 -> .48f; else -> .18f }
                val targetScale = when (distance) { 0 -> 1f; 1 -> .86f; else -> .76f }
                val alpha by animateFloatAsState(targetAlpha, spring(stiffness = 520f), label = "wheelAlpha")
                val scale by animateFloatAsState(targetScale, spring(stiffness = 520f), label = "wheelScale")
                Box(Modifier.height(WheelRowHeight).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "%02d%s".format(items[index], suffix),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = if (distance == 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.alpha(alpha).scale(scale)
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelSelectionBand(modifier: Modifier = Modifier) {
    Box(modifier.height(WheelRowHeight).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .68f), RoundedCornerShape(14.dp)))
}

@Composable
fun TimeRangeValueField(start: Int, end: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(MaterialTheme.colorScheme.outline.copy(alpha = .7f), label = "timeFieldBorder")
    GlassOutlinedButton(
        onClick = onClick,
        modifier = modifier.height(62.dp),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        contentPadding = PaddingValues(horizontal = 14.dp)
    ) {
        Icon(Icons.Outlined.AccessTime, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("课程时间", style = MaterialTheme.typography.labelMedium, color = Muted)
            Text("${timeText(start)}  —  ${timeText(end)}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun SingleTimeValueField(value: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GlassOutlinedButton(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Icon(Icons.Outlined.AccessTime, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Text(value.ifBlank { "不修改" }, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeWheelSheet(initialStart: Int, initialEnd: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var startHour by remember { mutableIntStateOf((initialStart / 60).coerceIn(0, 23)) }
    var startMinute by remember { mutableIntStateOf((initialStart % 60).coerceIn(0, 59)) }
    var endHour by remember { mutableIntStateOf(if (initialEnd >= 1440) 24 else (initialEnd / 60).coerceIn(0, 23)) }
    var endMinute by remember { mutableIntStateOf(if (initialEnd >= 1440) 0 else (initialEnd % 60).coerceIn(0, 59)) }
    val start = startHour * 60 + startMinute
    val end = endHour * 60 + endMinute
    val valid = end > start && end <= 1440 && (endHour < 24 || endMinute == 0)

    GlassModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("课程时间", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (valid) "共 ${end - start} 分钟" else "结束时间需要晚于开始时间", color = if (valid) Muted else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WheelSelectionBand(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    NumberWheel(0..23, startHour, "时", "开始小时", Modifier.width(74.dp)) { startHour = it }
                    NumberWheel(0..59, startMinute, "分", "开始分钟", Modifier.width(74.dp)) { startMinute = it }
                    Text("—", style = MaterialTheme.typography.titleLarge, color = Muted, modifier = Modifier.padding(horizontal = 2.dp))
                    NumberWheel(0..24, endHour, "时", "结束小时", Modifier.width(74.dp)) { endHour = it; if (it == 24) endMinute = 0 }
                    NumberWheel(0..59, endMinute, "分", "结束分钟", Modifier.width(74.dp)) { endMinute = if (endHour == 24) 0 else it }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("确定", { onConfirm(start, end) }, enabled = valid)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleTimeWheelSheet(initial: Int, title: String, allowClear: Boolean = false, onDismiss: () -> Unit, onClear: () -> Unit = {}, onConfirm: (Int) -> Unit) {
    var hour by remember { mutableIntStateOf((initial / 60).coerceIn(0, 23)) }
    var minute by remember { mutableIntStateOf((initial % 60).coerceIn(0, 59)) }
    GlassModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(220.dp), contentAlignment = Alignment.Center) {
                WheelSelectionBand(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberWheel(0..23, hour, "时", "$title 小时", Modifier.width(96.dp)) { hour = it }
                    NumberWheel(0..59, minute, "分", "$title 分钟", Modifier.width(96.dp)) { minute = it }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("确定", { onConfirm(hour * 60 + minute) })
            if (allowClear) TextButton(onClick = onClear) { Text("清空，保持原时间") }
        }
    }
}
