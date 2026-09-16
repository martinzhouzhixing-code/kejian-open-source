package app.kejian.mobile

import biweekly.Biweekly
import biweekly.component.VEvent
import java.time.*
import java.util.Date
import java.util.TimeZone
import java.util.UUID

/** Local-only conversion. Expands a user-visible window, never silently drops invalid records. */
object IcsImport {
    fun parse(text:String,from:LocalDate,through:LocalDate,zone:ZoneId=ZoneId.systemDefault()):ImportPreview {
        require(text.length<=4_000_000){"ICS 文件超过 4 MB，请分开导出"}
        require(!through.isBefore(from)&&through<=from.plusYears(1)){"导入范围最多一年"}
        require(text.trimStart('\uFEFF',' ','\r','\n','\t').startsWith("BEGIN:VCALENDAR",true)){"这不是 ICS 日历文件"}
        val calendars=Biweekly.parse(text.trimStart('\uFEFF')).all()
        require(calendars.isNotEmpty()){ "日历中没有数据" }
        val courses=mutableListOf<Course>();val deadlines=mutableListOf<Deadline>();val errors=mutableListOf<String>()
        val lower=Date.from(from.atStartOfDay(zone).toInstant())
        val upper=Date.from(through.plusDays(1).atStartOfDay(zone).toInstant())
        var examined=0
        fun id(key:String)=UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        for(calendar in calendars){
            require(calendar.events.size<=1000){"日历项目过多，请按学期分开导出"}
            val overrides=calendar.events.filter {it.recurrenceId!=null}.groupBy {it.uid?.value}
            fun add(event:VEvent,start:Date,key:String){
                if(event.status?.value.equals("CANCELLED",true))return
                val ds=event.dateStart?.value?:error("缺少开始时间")
                val title=event.summary?.value?.trim().orEmpty()
                require(title.isNotEmpty()&&title.length<=80){"课程名称为空或超过 80 字"}
                val end=event.dateEnd?.value
                val duration=end?.let {it.time-ds.time}?:event.duration?.value?.toMillis()
                val local=start.toInstant().atZone(zone)
                if(!ds.hasTime()){
                    val dateZone=ZoneId.systemDefault()
                    val days=if(end!=null)java.time.temporal.ChronoUnit.DAYS.between(ds.toInstant().atZone(dateZone).toLocalDate(),end.toInstant().atZone(dateZone).toLocalDate()) else ((duration?:86_400_000L)/86_400_000L)
                    require(days>0){"全天事项结束日期必须晚于开始日期"}
                    require(days<=366){"全天事项跨度过长"}
                    repeat(days.toInt()){offset->val date=start.toInstant().atZone(dateZone).toLocalDate().plusDays(offset.toLong())
                        if(date in from..through)deadlines+=Deadline(id=id("ics:$key:$offset"),title=title,dueDate=date,details=event.description?.value.orEmpty().take(500))
                    }
                }else{
                    require(duration!=null&&duration>0){"缺少有效的结束时间或时长"}
                    require(duration<=7*86_400_000L){"单个项目超过七天"}
                    var cursor=local;val finish=start.toInstant().plusMillis(duration).atZone(zone)
                    // Cross-midnight events become explicit daily segments rather than invalid courses.
                    while(cursor<finish){
                        val next=cursor.toLocalDate().plusDays(1).atStartOfDay(zone)
                        val stop=minOf(next,finish)
                        val date=cursor.toLocalDate()
                        if(date in from..through){
                            val beginMinute=cursor.hour*60+cursor.minute
                            val endMinute=if(stop==next)1440 else stop.hour*60+stop.minute
                            require(endMinute>beginMinute){"不支持不足一分钟的项目"}
                            val location=event.location?.value.orEmpty()
                            require(location.length<=300){"地点超过 300 字，请缩短后导入"}
                            courses+=Course(id=id("ics:$key:$date"),name=title,day=date.dayOfWeek.value,start=beginMinute,end=endMinute,date=date,address=location)
                        }
                        cursor=stop
                    }
                }
                require(courses.size<=1000&&deadlines.size<=1000){"转换结果超过 1000 项，请缩短日期范围"}
            }
            calendar.events.filter {it.recurrenceId==null}.forEachIndexed {index,event->
                try {
                    if(event.status?.value.equals("CANCELLED",true))return@forEachIndexed
                    val start=event.dateStart?:error("缺少开始时间")
                    val uid=event.uid?.value?:"${event.summary?.value}:${start.value.time}"
                    val exceptions=overrides[event.uid?.value].orEmpty()
                    require(exceptions.none {it.recurrenceId?.parameters?.get("RANGE")?.isNotEmpty()==true}){"暂不支持 THISANDFUTURE 例外，请导出展开后的日历"}
                    require(event.recurrenceDates.none {it.periods.isNotEmpty()}){"暂不支持 RDATE PERIOD，请导出独立项目"}
                    val frequency=event.recurrenceRule?.value?.frequency?.toString()
                    require(frequency==null||frequency in setOf("DAILY","WEEKLY","MONTHLY","YEARLY")){"循环频率过高"}
                    val tz=calendar.timezoneInfo.getTimezone(start)?.timeZone?:if(calendar.timezoneInfo.isFloating(start))TimeZone.getTimeZone(zone)else TimeZone.getTimeZone("UTC")
                    if(event.recurrenceDates.isNotEmpty())event.addRecurrenceDates(biweekly.property.RecurrenceDates().also {it.dates.add(start.value)})
                    val iterator=event.getDateIterator(tz)
                    iterator.advanceTo(Date(lower.time-7*86_400_000L))
                    while(iterator.hasNext()){
                        val occurrence=iterator.next();if(occurrence>=upper)break
                        require(++examined<=6000){"循环项目过多，请缩短日期范围"}
                        if(exceptions.none {it.recurrenceId.value.time==occurrence.time})add(event,occurrence,"$uid:${occurrence.time}")
                    }
                }catch(e:Exception){errors+="第 ${index+1} 项（${event.summary?.value.orEmpty().take(40)}）：${e.message}"}
            }
            overrides.values.flatten().forEach {event->
                try {if(!event.status?.value.equals("CANCELLED",true))add(event,event.dateStart?.value?:error("例外缺少日期"),"${event.uid?.value}:${event.recurrenceId.value.time}")}
                catch(e:Exception){errors+="循环例外：${e.message}"}
            }
        }
        return ImportPreview(courses.distinctBy {it.id},errors.take(30),deadlines=deadlines.distinctBy {it.id})
    }
    fun withoutDuplicates(preview:ImportPreview,data:AppData):ImportPreview=preview.copy(
        courses=preview.courses.filter {candidate->data.courses.none {it.id==candidate.id||it.name==candidate.name&&it.start==candidate.start&&it.end==candidate.end&&candidate.date in it.dates(data.settings.termStart)}},
        deadlines=preview.deadlines.filter {candidate->data.deadlines.none {it.id==candidate.id||it.title==candidate.title&&it.dueDate==candidate.dueDate&&it.dueMinute==candidate.dueMinute}})
}
