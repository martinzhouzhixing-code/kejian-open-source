package app.kejian.mobile

import java.time.LocalDate

/** A history step changes the timetable, never theme, zoom or notification preferences. */
data class TimetableSnapshot(val courses:List<Course>,val deadlines:List<Deadline>,val termStart:LocalDate,val termName:String) {
    fun applyTo(data:AppData)=data.copy(courses=courses,deadlines=deadlines,settings=data.settings.copy(termStart=termStart,termName=termName))
    companion object { fun of(data:AppData)=TimetableSnapshot(data.courses,data.deadlines,data.settings.termStart,data.settings.termName) }
}

class EditHistory(private val limit:Int=50) {
    private data class Step(val before:TimetableSnapshot,val after:TimetableSnapshot)
    private val past=ArrayDeque<Step>();private val future=ArrayDeque<Step>()
    fun clear(){past.clear();future.clear()}
    val canUndo get()=past.isNotEmpty()
    val canRedo get()=future.isNotEmpty()
    fun record(before:AppData,after:AppData){
        val a=TimetableSnapshot.of(before);val b=TimetableSnapshot.of(after)
        if(a==b)return
        past.addLast(Step(a,b));while(past.size>limit)past.removeFirst();future.clear()
    }
    // Move the cursor only after the caller has durably saved the candidate.
    fun undoCandidate(data:AppData)=past.lastOrNull()?.before?.applyTo(data)
    fun redoCandidate(data:AppData)=future.lastOrNull()?.after?.applyTo(data)
    fun didUndo(){future.addLast(past.removeLast())}
    fun didRedo(){past.addLast(future.removeLast())}
}

fun changeCourse(data:AppData,original:Course,edited:Course?,oldDate:LocalDate,targetDate:LocalDate):AppData {
    val courses=when {
        original.date!=null -> data.courses.filterNot {it.id==original.id}+listOfNotNull(edited?.copy(date=targetDate,day=targetDate.dayOfWeek.value))
        !data.settings.editAllWeeks -> editOccurrence(data.courses,original,edited,oldDate,targetDate)
        edited==null -> data.courses.filterNot {it.id==original.id}
        else -> data.courses.map {if(it.id==original.id)edited.copy(excluded=original.excluded.map {date->date.plusDays((edited.day-original.day).toLong())}.toSet())else it}
    }
    val oldRemark=data.weeklyRemarks[weeklyRemarkKey(original.id,oldDate)]
    val destination=if(original.date!=null)courses.find {it.id==original.id}
        else courses.firstOrNull {c->data.courses.none {it.id==c.id}}
    val remarks=if(edited!=null&&!data.settings.editAllWeeks&&destination!=null&&oldRemark!=null)
        data.weeklyRemarks+(weeklyRemarkKey(destination.id,targetDate) to oldRemark) else data.weeklyRemarks
    return data.copy(courses=courses,weeklyRemarks=remarks)
}

fun duplicateCourse(data:AppData,original:Course,date:LocalDate):Course? {
    val base=original.copy(id=java.util.UUID.randomUUID().toString(),name=original.name,
        date=if(data.settings.editAllWeeks&&original.date==null)null else date,
        day=date.dayOfWeek.value,excluded=if(data.settings.editAllWeeks)original.excluded else emptySet())
    val slots=(420..(1440-base.duration) step 30).toList()
    val ordered=slots.filter {it>=original.end}+slots.filter {it<original.end}
    return ordered.asSequence().map {base.copy(start=it,end=it+base.duration)}.firstOrNull {candidate->data.courses.none {overlap(candidate,it,data.settings.termStart)}}
}
