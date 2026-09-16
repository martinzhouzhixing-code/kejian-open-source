package app.kejian.mobile

import java.time.*
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.floor

data class Course(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新课程",
    val day: Int = 1,
    val start: Int = 510,
    val end: Int = 570,
    val color: Int = 0,
    val address: String = "",
    val room: String = "",
    val teacher: String = "",
    val weeks: Set<Int> = (1..16).toSet(),
    val reminder: Boolean = true,
    val date: LocalDate? = null,
    val excluded: Set<LocalDate> = emptySet()
) {
    val duration get() = end - start
    val place get() = listOf(address.trim(), room.trim()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "未填写课堂地址" }
    fun error(): String? = when {
        name.isBlank() -> "请填写课程名称"
        name.length > 80 -> "课程名称最多 80 字"
        address.length > 300 || room.length > 80 || teacher.length > 80 -> "地址或其他字段过长"
        day !in 1..7 -> "请选择星期"
        start !in 0..1439 || end !in 1..1440 || end <= start -> "结束时间必须晚于开始时间，且不跨天"
        color !in 0 until 12 -> "课程颜色无效"
        date == null && (weeks.isEmpty() || weeks.any { it !in 1..30 }) -> "周次应在 1–30 周内"
        else -> null
    }
    fun dates(term: LocalDate): Set<LocalDate> = (date?.let { setOf(it) }
        ?: weeks.map { monday(term).plusWeeks((it - 1).toLong()).plusDays((day - 1).toLong()) }.toSet()) - excluded
    fun occurs(date: LocalDate, term: LocalDate) = date in dates(term)
}

/** A date-level due item that never occupies a synthetic midnight course slot. */
data class Deadline(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新截止日",
    val dueDate: LocalDate = LocalDate.now().plusDays(1),
    val dueMinute: Int? = null,
    val details: String = "",
    val courseId: String? = null,
    val color: Int = 0,
    val reminder: Boolean = true
) {
    fun error(): String? = when {
        id.isBlank() || id.length > 80 -> "截止日 ID 无效"
        title.isBlank() -> "请填写截止日名称"
        title.length > 80 -> "截止日名称最多 80 字"
        details.length > 500 -> "截止日详情最多 500 字"
        courseId != null && courseId.length > 80 -> "关联课程 ID 无效"
        dueMinute != null && dueMinute !in 0..1439 -> "截止时间无效"
        color !in 0 until 12 -> "截止日颜色无效"
        else -> null
    }
    val timeLabel get() = dueMinute?.let(::timeText) ?: "全天"
}

/** Text result of one lesson recording. audioPath is device-only and is never uploaded in cloud backups. */
data class LessonNote(
    val id:String = UUID.randomUUID().toString(),
    val courseId:String? = null,
    val occurrenceDate:LocalDate? = null,
    val title:String = "课堂录音",
    val summary:String = "",
    val keyPoints:List<String> = emptyList(),
    val actionItems:List<String> = emptyList(),
    val transcript:String = "",
    val durationSeconds:Int = 0,
    val createdAt:Instant = Instant.now(),
    val audioPath:String? = null
) {
    fun error():String?=when {
        id.isBlank()||id.length>80 -> "课堂总结 ID 无效"
        courseId!=null&&courseId.length>80 -> "关联课程 ID 无效"
        title.isBlank()||title.length>120 -> "课堂总结标题无效"
        summary.length>400_000||transcript.length>500_000 -> "课堂总结内容过长"
        keyPoints.size>2000||actionItems.size>2000||keyPoints.any {it.length>12_000}||actionItems.any {it.length>12_000}->"课堂总结条目过多"
        durationSeconds !in 0..86_400 -> "录音时长无效"
        else->null
    }
}

data class Settings(val termStart: LocalDate = monday(LocalDate.now()), val termName: String = "我的学期", val reminders: Boolean = true, val reminderMinutes:Int=10, val themeMode:String="system",val language:String="zh",val gridZoom:Float=1f,val editAllWeeks:Boolean=false,val gridZoomX:Float=gridZoom,val gridZoomY:Float=gridZoom,val widgetOpacity:Float=1f,val widgetStyle:WidgetStyle=WidgetStyle(),val widgetBackgroundMode:String="solid",val widgetBackgroundUri:String?=null,val widgetBackgroundTone:Float=0f,val widgetTextMode:String="auto",val widgetImageLuminance:Float?=null,val skin:String="forest",val glassBackground:Boolean=true,val widgetFrosted:Boolean=false,val liquidGlass:Boolean=true,val appBackgroundUri:String?=null,val appBackgroundBlur:Float=.25f,val appBackgroundTone:Float=0f,val customTheme:Boolean=false,val customAccent:String="#245C9B",val customSurface:String="#245C9B",val hideEmptyWeekends:Boolean=true,val visibleStart:Int=420,val visibleEnd:Int=1440)
data class AppData(val courses: List<Course> = emptyList(), val settings: Settings = Settings(), val deadlines: List<Deadline> = emptyList(), val notes:List<LessonNote> = emptyList(), val weeklyRemarks:Map<String,String> = emptyMap())
data class Occurrence(val course: Course, val date: LocalDate) {
    fun startAt(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = date.atStartOfDay().plusMinutes(course.start.toLong()).atZone(zone)
    fun endAt(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = date.atStartOfDay().plusMinutes(course.end.toLong()).atZone(zone)
}

fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
fun weekNumber(date: LocalDate, term: LocalDate): Int = floor(ChronoUnit.DAYS.between(monday(term), date) / 7.0).toInt() + 1
fun timeText(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
fun parseTime(value: String): Int? {
    val match = Regex("^(\\d{1,2})[:：](\\d{2})$").matchEntire(value.trim()) ?: return null
    val h = match.groupValues[1].toInt(); val m = match.groupValues[2].toInt()
    return if (h in 0..23 && m in 0..59 || h == 24 && m == 0) h * 60 + m else null
}
fun snapStart(rawMinutes: Float, duration: Int): Int {
    require(duration in 1..1440)
    return (floor((rawMinutes + 15) / 30.0).toInt() * 30).coerceIn(0, (1440 - duration) / 30 * 30)
}

/** Prefer a color not yet present; once all colors are used, choose the least-used one. */
fun nextCourseColor(courses:List<Course>):Int {
    val counts=IntArray(12)
    courses.forEach {if(it.color in counts.indices)counts[it.color]++}
    return counts.indices.minWithOrNull(compareBy<Int>{counts[it]}.thenBy {it})?:0
}

/** Assign distinct colors to newly imported or AI-created modules. */
fun distributeCourseColors(existing:List<Course>,incoming:List<Course>):List<Course> {
    val counts=IntArray(12);existing.forEach {if(it.color in counts.indices)counts[it.color]++}
    return incoming.map {course->val chosen=counts.indices.minWithOrNull(compareBy<Int>{counts[it]}.thenBy {it})?:0;counts[chosen]++;course.copy(color=chosen)}
}
fun overlap(a: Course, b: Course, term: LocalDate): Boolean = a.id != b.id && a.start < b.end && b.start < a.end && a.dates(term).any { it in b.dates(term) }
fun conflictPairs(courses: List<Course>, term: LocalDate): List<Pair<Course,Course>> = courses.flatMapIndexed { i,a -> courses.drop(i+1).filter { overlap(a,it,term) }.map { a to it } }
fun upcoming(data: AppData, now: ZonedDateTime = ZonedDateTime.now(), includeOngoing: Boolean = true): List<Occurrence> = data.courses.flatMap { c ->
    c.dates(data.settings.termStart).map { Occurrence(c,it) }
}.filter { if(includeOngoing) it.endAt(now.zone).isAfter(now) else it.startAt(now.zone).isAfter(now) }.sortedBy { it.startAt(now.zone).toInstant() }

/** Only this occurrence: exclude its original date and add a detached dated exception. */
fun editOccurrence(courses: List<Course>, original: Course, edited: Course?, originalDate: LocalDate, targetDate: LocalDate): List<Course> {
    val retained = if(original.date != null) courses.filterNot { it.id == original.id }
        else courses.map { if(it.id == original.id) it.copy(excluded = it.excluded + originalDate) else it }
    return retained + listOfNotNull(edited?.copy(id = UUID.randomUUID().toString(), date = targetDate, excluded = emptySet(), day = targetDate.dayOfWeek.value))
}

fun demoCourses(): List<Course> = listOf(
    Course(name="高等数学",day=1,start=510,end=600,address="东校区 · 博学楼",room="A201",teacher="陈老师"),
    Course(name="大学英语",day=1,start=630,end=720,color=1,address="东校区 · 文科楼",room="B302"),
    Course(name="线性代数",day=2,start=540,end=630,color=3,address="东校区 · 博学楼",room="A305"),
    Course(name="设计基础",day=3,start=480,end=570,color=2,address="西校区 · 艺术楼",room="C105"),
    Course(name="高等数学",day=4,start=510,end=600,address="东校区 · 博学楼",room="A201"),
    Course(name="程序设计",day=4,start=690,end=780,color=3,address="东校区 · 实验楼",room="D406"),
    Course(name="大学英语",day=5,start=600,end=690,color=1,address="东校区 · 文科楼",room="B302")
)

data class ImportResult(val courses: List<Course>, val errors: List<String>)
object CourseTable {
    private val header = listOf("课程","星期","开始","结束","课堂地址","教室","颜色","周次","教师","提醒")
    fun parseWeeks(s: String): Set<Int>? {
        if(s.isBlank()) return (1..16).toSet()
        val result=mutableSetOf<Int>()
        for(part in s.replace("，",",").split(",")) {
            val ends=part.trim().split("-").map { it.trim().toIntOrNull() ?: return null }
            if(ends.size !in 1..2 || ends.any { it !in 1..30 }) return null
            val last=ends.last(); if(last<ends.first())return null
            result.addAll(ends.first()..last)
        }
        return result.takeIf { it.isNotEmpty() }
    }
    private fun records(text: String, delimiter: Char): List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val cell=StringBuilder()
        var quoted=false;var i=0
        while(i<text.length){ val ch=text[i]
            when {
                ch=='"' && quoted && i+1<text.length && text[i+1]=='"' -> {cell.append('"');i++}
                ch=='"' -> quoted=!quoted
                ch==delimiter && !quoted -> {row.add(cell.toString());cell.clear()}
                (ch=='\n'||ch=='\r') && !quoted -> {if(ch=='\r'&&i+1<text.length&&text[i+1]=='\n')i++;row.add(cell.toString());cell.clear();if(row.any { it.isNotBlank() })rows.add(row.toList());row.clear()}
                else -> cell.append(ch)
            };i++
        }
        require(!quoted){"引号未闭合，请检查文件"};row.add(cell.toString());if(row.any { it.isNotBlank() })rows.add(row)
        return rows
    }
    fun parse(text: String): ImportResult {
        if(text.length>1_000_000)return ImportResult(emptyList(),listOf("文件超过 1 MB，请分批导入"))
        val source=text.removePrefix("\uFEFF")
        if(CourseExchange.looksLike(source))return CourseExchange.parse(source)
        val rows=try { records(source,if(source.lineSequence().firstOrNull()?.contains('\t')==true)'\t' else ',') } catch(e:IllegalArgumentException){return ImportResult(emptyList(),listOf(e.message.orEmpty()))}
        if(rows.isEmpty())return ImportResult(emptyList(),listOf("没有可导入的数据"))
        val hasHeader=rows.first().first().trim() in listOf("课程","课程名称","名称","name")
        val aliases=mapOf("课程名称" to "课程","名称" to "课程","name" to "课程","地址" to "课堂地址","老师" to "教师")
        val columns=if(hasHeader)rows.first().map { aliases[it.trim()]?:it.trim() } else header
        if(hasHeader&&!listOf("课程","星期","开始","结束").all { it in columns })return ImportResult(emptyList(),listOf("表头需要：课程、星期、开始、结束"))
        val data=if(hasHeader)rows.drop(1) else rows
        if(data.size>200)return ImportResult(emptyList(),listOf("每次最多导入 200 行"))
        val good=mutableListOf<Course>();val bad=mutableListOf<String>()
        data.forEachIndexed { index,row ->
            fun get(name:String)=columns.indexOf(name).takeIf { it>=0 }?.let { row.getOrNull(it)?.trim() }.orEmpty()
            val rawDay=get("星期").removePrefix("星期").removePrefix("周")
            val day=rawDay.toIntOrNull()?:mapOf("一" to 1,"二" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"日" to 7,"天" to 7)[rawDay]
            val start=parseTime(get("开始"));val end=parseTime(get("结束"));val weeks=parseWeeks(get("周次"))
            val c=if(day!=null&&start!=null&&end!=null&&weeks!=null)Course(name=get("课程"),day=day,start=start,end=end,address=get("课堂地址"),room=get("教室"),teacher=get("教师"),color=get("颜色").ifBlank { "0" }.toIntOrNull()?:-1,weeks=weeks,reminder=get("提醒") !in listOf("false","0","关","否")) else null
            val error=c?.error()?:if(c==null)"星期、时间或周次格式错误" else null
            if(error!=null)bad.add("第 ${index+if(hasHeader)2 else 1} 行：$error") else good.add(c!!)
        };return ImportResult(good,bad)
    }
    fun encode(courses: List<Course>, delimiter: Char='\t'): String {
        fun escape(s:String):String {
            val safe=if(s.firstOrNull() in listOf('=','+','-','@'))"'$s" else s
            return if(safe.any { it==delimiter||it=='"'||it=='\n'||it=='\r' })"\"${safe.replace("\"","\"\"")}\"" else safe
        }
        return (listOf(header)+courses.map { listOf(it.name,it.day.toString(),timeText(it.start),timeText(it.end),it.address,it.room,it.color.toString(),it.weeks.sorted().joinToString(","),it.teacher,if(it.reminder)"是" else "否") }).joinToString("\n"){row->row.joinToString(delimiter.toString()){escape(it)}}
    }
}

/** Rolling 24 hours measured on the timeline, not "today and tomorrow". Ongoing lessons are excluded. */
fun widgetOccurrences(data:AppData,now:ZonedDateTime=ZonedDateTime.now()):List<Occurrence> {
    val start=now.toInstant();val end=start.plusSeconds(24*60*60)
    return upcoming(data,now,false).filter {val instant=it.startAt(now.zone).toInstant();!instant.isBefore(start)&&instant.isBefore(end)}
}
