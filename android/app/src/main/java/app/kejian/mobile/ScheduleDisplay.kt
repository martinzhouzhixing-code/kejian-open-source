package app.kejian.mobile

import java.time.LocalDate

/** A dated exception and a deadline count as real content for weekend visibility. */
fun visibleScheduleDays(data:AppData,week:LocalDate,editing:Boolean=false):Int {
    if(editing||!data.settings.hideEmptyWeekends)return 7
    return if((5..6).any { offset ->
        val date=monday(week).plusDays(offset.toLong())
        data.courses.any {it.occurs(date,data.settings.termStart)} || data.deadlines.any {it.dueDate==date}
    })7 else 5
}
fun scheduleTimeRange(data:AppData,week:LocalDate):IntRange {
    val start=data.settings.visibleStart.coerceIn(0,1410)
    val end=data.settings.visibleEnd.coerceIn(start+30,1440)
    val courses=data.courses.filter {c->(0..6).any {c.occurs(monday(week).plusDays(it.toLong()),data.settings.termStart)}}
    // A new import outside the preference must remain visible.
    return minOf(start,courses.minOfOrNull {it.start}?:start)..maxOf(end,courses.maxOfOrNull {it.end}?:end)
}
fun weeklyRemarkKey(courseId:String,date:LocalDate)="$courseId@${monday(date)}"
