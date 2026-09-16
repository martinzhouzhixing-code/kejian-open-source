package app.kejian.mobile

/**
 * Stable, line-oriented interchange format intended for OCR and future AI output.
 *
 * KJ1|ID|课程|课堂地址|教室|教师|星期|开始-结束|周次|颜色|单次日期|排除日期|提醒
 * KJ1|~|ENG|D楼|D207|~|周一|11:30-12:30|1-16|蓝色|~|~|是
 *
 * `~` means not supplied. A literal `|`, `\` or newline is escaped with a backslash.
 */
object CourseExchange {
    const val EMPTY = "~"
    val colorNames = listOf("青绿","蓝色","橙色","紫色","粉色","青色","黄色","草绿","灰色","天蓝","淡紫","橄榄")
    private val dayNames = listOf("周一","周二","周三","周四","周五","周六","周日")

    fun encode(courses:List<Course>):String=courses.joinToString("\n") { encodeCourse(it) }
    fun encodeCourse(course:Course,includeId:Boolean=true):String =
        listOf(
            "KJ1",if(includeId)course.id.blank() else EMPTY,course.name,course.address.blank(),course.room.blank(),course.teacher.blank(),dayNames[course.day-1],
            "${timeText(course.start)}-${timeText(course.end)}",course.weeks.sorted().ranges(),
            colorNames.getOrElse(course.color){course.color.toString()},course.date?.toString()?:EMPTY,
            course.excluded.sorted().joinToString(",").blank(),if(course.reminder)"是" else "否"
        ).joinToString("|") {escape(it)}

    fun parse(text:String):ImportResult {
        val source=text.removePrefix("\uFEFF").trim()
        if(source.isBlank())return ImportResult(emptyList(),listOf("没有可识别的课程内容"))
        val lines=source.lineSequence().map {it.trim()}.filter {it.isNotBlank()}.take(201).toList()
        if(lines.size>200)return ImportResult(emptyList(),listOf("每次最多识别 200 节课程"))
        val good=mutableListOf<Course>();val bad=mutableListOf<String>()
        lines.forEachIndexed {index,line->
            val result=when {
                line.startsWith("KJ1|")->parseKj1(line)
                !line.contains('|')&&line.count {it=='_'}==4->parseShort(line)
                else->null
            }
            if(result==null)bad.add("第 ${index+1} 行：不是 KJ1 固定格式")
            else result.fold(onSuccess={good.add(it)},onFailure={bad.add("第 ${index+1} 行：${it.message}")})
        }
        return ImportResult(good,bad)
    }

    fun looksLike(text:String):Boolean {
        val first=text.removePrefix("\uFEFF").lineSequence().firstOrNull {it.isNotBlank()}?.trim().orEmpty()
        return first.startsWith("KJ1|")||(!first.contains('|')&&first.count {it=='_'}==4)
    }

    private fun parseKj1(line:String):Result<Course> = runCatching {
        val fields=split(line)
        require(fields.size==13&&fields[0]=="KJ1"){"必须是 13 个字段：KJ1|ID|课程|地址|教室|教师|星期|时间|周次|颜色|单次日期|排除日期|提醒"}
        course(fields[1].value(),fields[2],fields[3].value(),fields[4].value(),fields[5].value(),fields[6],fields[7],fields[8].value(),fields[9].value(),fields[10].value(),fields[11].value(),fields[12].value())
    }

    // Compatibility with the user's compact example. Missing weekday/weeks use Monday and weeks 1–16.
    private fun parseShort(line:String):Result<Course> = runCatching {
        val fields=line.split('_')
        require(fields.size==5){"下划线简写需要：课程_地址_教室_时间_颜色"}
        course("",fields[0],fields[1].value(),fields[2].value(),"","周一",fields[3],"1-16",fields[4].value(),"","","是")
    }

    private fun course(id:String,name:String,address:String,room:String,teacher:String,dayText:String,time:String,weeksText:String,colorText:String,dateText:String,excludedText:String,reminderText:String):Course {
        require(name.isNotBlank()&&name!=EMPTY){"课程名称不能为空"}
        val day=parseDay(dayText)?:error("星期格式应为周一到周日或 1–7")
        val match=Regex("^(\\d{1,2}[:：]\\d{2})\\s*[-–—至]\\s*(\\d{1,2}[:：]\\d{2})$").matchEntire(time.trim())?:error("时间格式应为 11:30-12:30")
        val start=parseTime(match.groupValues[1])?:error("开始时间无效")
        val end=parseTime(match.groupValues[2])?:error("结束时间无效")
        val date=dateText.takeIf {it.isNotBlank()&&it!=EMPTY}?.let {runCatching {java.time.LocalDate.parse(it)}.getOrNull()?:error("单次日期应为 YYYY-MM-DD")}
        val weeks=if(weeksText.isBlank()||weeksText==EMPTY){if(date!=null)emptySet() else (1..16).toSet()}
            else CourseTable.parseWeeks(weeksText)?:error("周次格式应为 1-16 或 1,3,5")
        val excluded=excludedText.takeIf {it.isNotBlank()&&it!=EMPTY}?.split(',')?.map {runCatching {java.time.LocalDate.parse(it.trim())}.getOrNull()?:error("排除日期应为 YYYY-MM-DD，用逗号分隔") }?.toSet().orEmpty()
        val color=parseColor(colorText)?:error("颜色可填写：${colorNames.joinToString("、")}，或 0–11")
        val reminder=reminderText !in listOf("否","关","0","false")
        val generated=Course(name=name.trim(),address=address.trim(),room=room.trim(),teacher=teacher.trim(),day=day,start=start,end=end,weeks=weeks,color=color,date=date,excluded=excluded,reminder=reminder)
        return (if(id.isBlank()||id==EMPTY)generated else generated.copy(id=id.take(80))).also {require(it.error()==null){it.error().orEmpty()}}
    }

    private fun parseDay(value:String):Int? {
        val raw=value.trim().removePrefix("星期").removePrefix("周")
        return raw.toIntOrNull()?.takeIf {it in 1..7}?:mapOf("一" to 1,"二" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"日" to 7,"天" to 7)[raw]
    }
    private fun parseColor(value:String):Int? {
        if(value.isBlank()||value==EMPTY)return 0
        val cleaned=value.trim()
        return cleaned.toIntOrNull()?.takeIf {it in colorNames.indices}
            ?:colorNames.indexOf(cleaned).takeIf {it>=0}
            ?:mapOf("绿色" to 0,"蓝" to 1,"橙" to 2,"紫" to 3,"粉" to 4,"青" to 5,"黄" to 6,"灰" to 8)[cleaned]
    }
    private fun String.blank()=if(isBlank())EMPTY else this
    private fun String.value()=if(trim()==EMPTY)"" else trim()
    private fun Iterable<Int>.ranges():String {
        val sorted=distinct().sorted();if(sorted.isEmpty())return EMPTY
        val result=mutableListOf<String>();var start=sorted.first();var end=start
        sorted.drop(1).forEach {value->if(value==end+1)end=value else {result+=if(start==end)"$start" else "$start-$end";start=value;end=value}}
        result+=if(start==end)"$start" else "$start-$end";return result.joinToString(",")
    }
    private fun escape(value:String)=buildString {value.forEach {ch->when(ch){'\\'->append("\\\\");'|'->append("\\|");'\n'->append("\\n");'\r'->{ }else->append(ch)}}}
    private fun split(line:String):List<String>{
        val fields=mutableListOf<String>();val current=StringBuilder();var escaped=false
        line.forEach {ch->when {escaped->{current.append(if(ch=='n')'\n' else ch);escaped=false};ch=='\\'->escaped=true;ch=='|'->{fields+=current.toString();current.clear()};else->current.append(ch)}}
        require(!escaped){"末尾转义符不完整"};fields+=current.toString();return fields
    }
}
