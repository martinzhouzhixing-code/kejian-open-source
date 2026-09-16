package app.kejian.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate

data class SchedulePreviewDelta(val added: List<Course>, val changed: List<Pair<Course, Course>>, val removed: List<Course>, val addedDeadlines: List<Deadline>, val changedDeadlines: List<Pair<Deadline, Deadline>>, val removedDeadlines: List<Deadline>) {
    val addedCount get() = added.size + addedDeadlines.size
    val changedCount get() = changed.size + changedDeadlines.size
    val removedCount get() = removed.size + removedDeadlines.size
}

fun schedulePreviewDelta(before: AppData, after: AppData): SchedulePreviewDelta {
    val old = before.courses.associateBy { it.id }; val next = after.courses.associateBy { it.id }
    val oldDue = before.deadlines.associateBy { it.id }; val nextDue = after.deadlines.associateBy { it.id }
    return SchedulePreviewDelta(after.courses.filter { it.id !in old }, after.courses.mapNotNull { c -> old[c.id]?.takeIf { it != c || c.date == null && before.settings.termStart != after.settings.termStart }?.let { it to c } }, before.courses.filter { it.id !in next }, after.deadlines.filter { it.id !in oldDue }, after.deadlines.mapNotNull { d -> oldDue[d.id]?.takeIf { it != d }?.let { it to d } }, before.deadlines.filter { it.id !in nextDue })
}

/** Use occurrence dates, not just day numbers: imports can target a different term or dated exceptions. */
fun schedulePreviewWeeks(before: AppData, after: AppData, delta: SchedulePreviewDelta): List<LocalDate> {
    val affectedDates = delta.added.flatMap { it.dates(after.settings.termStart) } + delta.changed.flatMap { it.second.dates(after.settings.termStart) + it.first.dates(before.settings.termStart) } + delta.removed.flatMap { it.dates(before.settings.termStart) } + delta.addedDeadlines.map { it.dueDate } + delta.changedDeadlines.flatMap { listOf(it.first.dueDate, it.second.dueDate) } + delta.removedDeadlines.map { it.dueDate }
    return affectedDates.map(::monday).distinct().sorted().ifEmpty { listOf(monday(after.settings.termStart)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TimetablePreviewScreen(preview: ImportPreview, data: AppData, onBack: () -> Unit, onConfirm: () -> Unit, readOnly: Boolean = false) {
    val en = AppLanguage.english
    val guideBeat=LocalOnboardingTargets.current?.beat?:0
    val merged = remember(preview, data) { preview.backup ?: data.copy(courses = data.courses + preview.courses, deadlines = data.deadlines + preview.deadlines) }
    val delta = remember(data, merged) { schedulePreviewDelta(data, merged) }
    val weeks = remember(data, merged) { schedulePreviewWeeks(data, merged, delta) }
    var week by remember(preview) { mutableStateOf(weeks.first()) }
    var before by remember(preview) { mutableStateOf(false) }
    var ignoredErrors by remember(preview) { mutableStateOf(false) }
    var replacementConfirmed by remember(preview) { mutableStateOf(false) }
    var review by remember(preview) { mutableStateOf(false) }
    var selected by remember(preview) { mutableStateOf<Occurrence?>(null) }
    var deadline by remember(preview) { mutableStateOf<List<Deadline>?>(null) }
    LaunchedEffect(readOnly,guideBeat){if(readOnly)before=guideBeat<3}
    val current = if (before) data else merged
    val conflicts = remember(merged) { conflictPairs(merged.courses, merged.settings.termStart).size }
    val hints = preview.errors.filter { it.startsWith("提示：") }
    val errors = preview.errors.filterNot { it.startsWith("提示：") }
    val palette = LocalAppPalette.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeading(if (en) "Schedule preview" else "课表预览", onBack) {
            TextButton(onClick = { review = true }) { Text(if (en) "Changes" else "改动明细") }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (en) "+${delta.addedCount} added · ${delta.changedCount} changed · −${delta.removedCount} removed" else "新增 ${delta.addedCount} · 修改 ${delta.changedCount} · 删除 ${delta.removedCount}", fontWeight = FontWeight.SemiBold, color = Brand)
            Text(if (readOnly) { if (en) "Read-only preview · Your schedule is unchanged" else "只读预览 · 不会修改当前日程" } else if (en) "Check exact times and dates. Nothing changes until you confirm." else "请核对准确的时间和日期，确认前不会修改课表。", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !before, onClick = { before = false }, label = { Text(if (en) "After" else "修改后") }, modifier = Modifier.weight(1f).testTag("preview-after"))
                FilterChip(selected = before, onClick = { before = true }, label = { Text(if (en) "Before" else "修改前") }, modifier = Modifier.weight(1f).testTag("preview-before"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { week = week.minusWeeks(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (en) "Previous week" else "上一周") }
            Text("${week.year} · ${week.monthValue}/${week.dayOfMonth} – ${week.plusDays(6).monthValue}/${week.plusDays(6).dayOfMonth}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            TextButton(onClick = { week = weeks.firstOrNull { it > week } ?: weeks.first() }, enabled = weeks.size > 1) { Text(if (en) "Next change" else "下处改动", style = MaterialTheme.typography.labelMedium) }
            IconButton(onClick = { week = week.plusWeeks(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (en) "Next week" else "下一周") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().onboardingTarget("preview.grid").testTag("preview-timetable")) {
            AndroidView(factory = { TimetableView(it) }, update = { view ->
                view.appData = current; view.palette = palette; view.week = week
                view.editingEnabled = false; view.batchDeleteEnabled = false; view.hideSelectedCourse = false
                view.onSelection = { c, d, _ -> if (c != null && d != null) { selected = Occurrence(c, d); view.clearSelection() } }
                view.onDeadline = { deadline = it }
            }, modifier = Modifier.fillMaxSize())
            val empty = current.courses.none { c -> (0..6).any { c.occurs(week.plusDays(it.toLong()), current.settings.termStart) } } && current.deadlines.none { it.dueDate in week..week.plusDays(6) }
            if (empty) GlassSurface(Modifier.align(Alignment.TopCenter).padding(top = 62.dp, start = 20.dp, end = 20.dp), shape = RoundedCornerShape(14.dp), color = SurfaceColor.copy(alpha = .96f)) { Text(if (en) "No events or deadlines in this week" else "这一周没有课程或截止日", Modifier.padding(14.dp), color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (conflicts > 0 || preview.errors.isNotEmpty()) TextButton(onClick = { review = true }, contentPadding = PaddingValues(0.dp)) { Text(if (en) "$conflicts overlaps · ${preview.errors.size} review notes" else "$conflicts 组重叠 · ${preview.errors.size} 条核对提示", color = if (errors.isNotEmpty()) MaterialTheme.colorScheme.error else Brand) }
            if (!readOnly && errors.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(ignoredErrors, { ignoredErrors = it }); Text(if (en) "Skip invalid items; import valid results" else "跳过无效项，仅导入有效结果", style = MaterialTheme.typography.bodySmall) }
            if (!readOnly && preview.backup != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(replacementConfirmed, { replacementConfirmed = it }); Text(if (en) "I reviewed replacements and deletions" else "我已核对替换与删除的内容", style = MaterialTheme.typography.bodySmall) }
            if (!readOnly) PrimaryButton(if (en) "Confirm and apply" else "确认并应用", onConfirm, modifier = Modifier.onboardingTarget("preview.confirm").testTag("preview-confirm"), enabled = (preview.courses.isNotEmpty() || preview.deadlines.isNotEmpty() || preview.backup != null) && (errors.isEmpty() || ignoredErrors) && (preview.backup == null || replacementConfirmed))
        }
    }
    if (review) GlassModalBottomSheet(onDismissRequest = { review = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(if (en) "Every proposed change" else "逐项核对改动", style = MaterialTheme.typography.titleLarge); Text(if (en) "Names, dates and minute-accurate times are preserved." else "完整保留名称、日期与分钟精度。", color = Muted) }
            items(delta.added, key = { "add-${it.id}" }) { c -> PreviewChangeCard(if (en) "Add" else "新增", null, c, merged) }
            items(delta.changed, key = { "change-${it.second.id}" }) { (old, c) -> PreviewChangeCard(if (en) "Change" else "修改", old, c, merged, data) }
            items(delta.removed, key = { "delete-${it.id}" }) { c -> PreviewChangeCard(if (en) "Delete" else "删除", c, null, data) }
            items(delta.addedDeadlines + delta.changedDeadlines.map { it.second } + delta.removedDeadlines, key = { "due-${it.id}" }) { d ->
                WhiteCard { Text(d.title, fontWeight = FontWeight.SemiBold); Text("${d.dueDate} · ${d.dueMinute?.let(::timeText) ?: if (en) "Date only" else "仅日期"}", color = Brand); Text(when { d in delta.removedDeadlines -> if (en) "Deadline removed" else "删除截止日"; delta.changedDeadlines.any { it.second.id == d.id } -> if (en) "Deadline changed" else "修改截止日"; else -> if (en) "Deadline added" else "新增截止日" }, color = Muted); delta.changedDeadlines.firstOrNull { it.second.id == d.id }?.first?.let { old -> Text(if (en) "Before: ${old.title} · ${old.dueDate} ${old.dueMinute?.let(::timeText).orEmpty()}" else "修改前：${old.title} · ${old.dueDate} ${old.dueMinute?.let(::timeText).orEmpty()}", style = MaterialTheme.typography.bodySmall) } }
            }
            if (preview.backup != null && !preview.aiTask) item { Text(if (en) "Restoring this backup also replaces settings." else "恢复完整备份还会替换设置。", color = MaterialTheme.colorScheme.error) }
            if (conflicts > 0) item { Text(if (en) "$conflicts overlapping pairs will be retained side by side." else "$conflicts 组时间重叠会并排保留，不会互相覆盖。", color = MaterialTheme.colorScheme.error) }
            items(hints) { Text(localized(it.removePrefix("提示：")), color = Muted, style = MaterialTheme.typography.bodySmall) }
            items(errors) { Text(localized(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    selected?.let { o -> GlassModalBottomSheet(onDismissRequest = { selected = null }) { Column(Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(o.course.name, style = MaterialTheme.typography.headlineSmall); Text("${o.date} · ${timeText(o.course.start)}–${timeText(o.course.end)}", color = Brand); Text(localized(o.course.place)); if (o.course.teacher.isNotBlank()) Text(o.course.teacher); Text(if (en) "Review only. Editing remains on the schedule after confirmation." else "仅供核对，确认应用后可在主页继续编辑。", color = Muted, style = MaterialTheme.typography.bodySmall) } } }
    deadline?.let { rows -> GlassModalBottomSheet(onDismissRequest = { deadline = null }) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { rows.forEach { d -> Text(d.title, style = MaterialTheme.typography.titleLarge); Text("${d.dueDate} · ${d.dueMinute?.let(::timeText) ?: if (en) "Date only" else "仅日期"}", color = Brand); if (d.details.isNotBlank()) Text(d.details) } } } }
}

@Composable private fun PreviewChangeCard(label: String, old: Course?, next: Course?, data: AppData, beforeData: AppData = data) {
    val en = AppLanguage.english
    fun describe(c: Course, term: LocalDate): String {
        val dates = c.dates(term).sorted()
        return "${c.name}\n${c.date ?: if (en) "Day ${c.day} · weeks ${c.weeks.sorted().joinToString(",")}" else "周${"一二三四五六日"[c.day - 1]} · 第 ${c.weeks.sorted().joinToString(",")} 周"}\n${timeText(c.start)}–${timeText(c.end)} · ${localized(c.place)}" +
            (if (c.date == null) "\n${if (en) "Dates: " else "实际日期："}${dates.take(3).joinToString(", ")}${if (dates.size > 3) " … (${dates.size})" else ""}" else "") +
            (if (c.excluded.isNotEmpty()) "\n${if (en) "Excluded: " else "排除日期："}${c.excluded.sorted().joinToString(",")}" else "")
    }
    WhiteCard {
        Text(label, color = Brand, style = MaterialTheme.typography.labelLarge)
        old?.let { Text((if (next != null) if (en) "Before\n" else "修改前\n" else "") + describe(it, beforeData.settings.termStart), color = if (next != null) Muted else Ink) }
        next?.let { Text((if (old != null) if (en) "After\n" else "修改后\n" else "") + describe(it, data.settings.termStart), fontWeight = FontWeight.Medium) }
    }
}
