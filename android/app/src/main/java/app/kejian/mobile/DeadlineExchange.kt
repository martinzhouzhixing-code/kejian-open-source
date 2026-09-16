package app.kejian.mobile

import java.time.LocalDate

/**
 * Stable deadline record used by local storage, cloud sync and AI output.
 * KJD1|ID|标题|日期|时间|关联课程ID|详情|颜色|提醒
 * `~` is the only representation of an unknown optional value.
 */
object DeadlineExchange {
    const val EMPTY = "~"

    fun encode(deadlines: List<Deadline>) = deadlines.joinToString("\n", transform = ::encodeDeadline)
    fun encodeDeadline(deadline: Deadline, includeId: Boolean = true): String = listOf(
        "KJD1", if (includeId) deadline.id else EMPTY, deadline.title, deadline.dueDate.toString(),
        deadline.dueMinute?.let(::timeText) ?: EMPTY, deadline.courseId ?: EMPTY,
        deadline.details.ifBlank { EMPTY }, CourseExchange.colorNames.getOrElse(deadline.color) { deadline.color.toString() },
        if (deadline.reminder) "是" else "否"
    ).joinToString("|") { escape(it) }

    fun parseLine(line: String): Result<Deadline> = runCatching {
        val fields = split(line.trim())
        require(fields.size == 9 && fields[0] == "KJD1") { "必须是 9 个字段：KJD1|ID|标题|日期|时间|关联课程ID|详情|颜色|提醒" }
        val title = fields[2].value(); require(title.isNotBlank()) { "截止日名称不能为空" }
        val date = runCatching { LocalDate.parse(fields[3]) }.getOrNull() ?: error("日期应为 YYYY-MM-DD")
        val minute = fields[4].value().takeIf { it.isNotBlank() }?.let { parseTime(it) ?: error("时间应为 HH:mm，未知请用 ~") }
        val colorText = fields[7].value()
        val color = if (colorText.isBlank()) 0 else colorText.toIntOrNull()?.takeIf { it in 0..11 }
            ?: CourseExchange.colorNames.indexOf(colorText).takeIf { it >= 0 }
            ?: error("颜色应为 0–11 或预设颜色名称")
        val generated = Deadline(title = title, dueDate = date, dueMinute = minute,
            courseId = fields[5].value().takeIf { it.isNotBlank() }, details = fields[6].value(), color = color,
            reminder = fields[8].value() !in listOf("否", "关", "0", "false"))
        (if (fields[1].value().isBlank()) generated else generated.copy(id = fields[1].value().take(80)))
            .also { require(it.error() == null) { it.error().orEmpty() } }
    }

    private fun String.value() = if (trim() == EMPTY) "" else trim()
    private fun escape(value: String) = buildString { value.forEach { ch -> when (ch) { '\\' -> append("\\\\"); '|' -> append("\\|"); '\n' -> append("\\n"); '\r' -> Unit; else -> append(ch) } } }
    private fun split(line: String): List<String> {
        val fields = mutableListOf<String>(); val current = StringBuilder(); var escaped = false
        line.forEach { ch -> when { escaped -> { current.append(if (ch == 'n') '\n' else ch); escaped = false }; ch == '\\' -> escaped = true; ch == '|' -> { fields += current.toString(); current.clear() }; else -> current.append(ch) } }
        require(!escaped) { "末尾转义符不完整" }; fields += current.toString(); return fields
    }
}

data class ScheduleRecords(val courses: List<Course>, val deadlines: List<Deadline>, val errors: List<String>)

object ScheduleExchange {
    fun encode(data: AppData): List<String> = data.courses.map(CourseExchange::encodeCourse) + data.deadlines.map(DeadlineExchange::encodeDeadline)
    fun parse(text: String): ScheduleRecords {
        val courses = mutableListOf<Course>(); val deadlines = mutableListOf<Deadline>(); val errors = mutableListOf<String>()
        text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotBlank() }.take(1201).forEachIndexed { index, line ->
            if (line.startsWith("KJD1|")) DeadlineExchange.parseLine(line).fold({ deadlines += it }, { errors += "第 ${index + 1} 行：${it.message}" })
            else {
                val parsed = CourseExchange.parse(line); courses += parsed.courses
                errors += parsed.errors.map { "第 ${index + 1} 行：${it.substringAfter("：", it)}" }
            }
        }
        return ScheduleRecords(courses, deadlines, errors)
    }
}
