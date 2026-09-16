package app.kejian.mobile

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class VersionFourDomainTest {
    private val now=ZonedDateTime.of(2026,8,31,12,0,0,0,ZoneId.of("Asia/Shanghai"))
    private fun c(date:LocalDate,start:Int,end:Int=start+60)=Course(date=date,day=date.dayOfWeek.value,start=start,end=end)
    @Test fun widgetWindowExcludesOngoingPastAndExactly24HoursAway(){
        val today=now.toLocalDate();val ongoing=c(today,690);val past=c(today,480);val next=c(today,780);val tomorrow=c(today.plusDays(1),690);val edge=c(today.plusDays(1),720)
        val data=AppData(listOf(edge,past,ongoing,tomorrow,next))
        assertEquals(listOf(next.id,tomorrow.id),widgetOccurrences(data,now).map {it.course.id})
    }
    @Test fun windowUses24ElapsedHoursAcrossDaylightSavingChange(){
        val n=ZonedDateTime.of(2026,3,7,12,0,0,0,ZoneId.of("America/New_York"));val day=n.toLocalDate().plusDays(1)
        val a=c(day,750);val b=c(day,780)
        assertEquals(listOf(a.id),widgetOccurrences(AppData(listOf(a,b)),n).map {it.course.id})
    }
    @Test fun excludedRepeatDoesNotLeakIntoWidget(){
        val repeat=Course(day=1,start=780,end=840,weeks=setOf(1,2),excluded=setOf(now.toLocalDate()))
        assertTrue(widgetOccurrences(AppData(listOf(repeat),Settings(termStart=now.toLocalDate())),now).isEmpty())
    }
    @Test fun duplicateOnlyThisOccurrenceUsesNewIdAndFreeHalfHour(){
        val original=Course(start=540,end=635,weeks=setOf(1,2));val data=AppData(listOf(original),Settings(termStart=now.toLocalDate()))
        val copy=duplicateCourse(data,original,now.toLocalDate())!!
        assertNotEquals(original.id,copy.id);assertEquals(now.toLocalDate(),copy.date);assertEquals(660,copy.start);assertEquals(95,copy.duration);assertFalse(overlap(original,copy,data.settings.termStart))
    }
    @Test fun duplicateAllWeeksKeepsRecurrenceAndChecksEveryDate(){
        val original=Course(start=480,end=540,weeks=setOf(1,2));val block=Course(start=540,end=600,weeks=setOf(2))
        val data=AppData(listOf(original,block),Settings(termStart=now.toLocalDate(),editAllWeeks=true))
        val copy=duplicateCourse(data,original,now.toLocalDate())!!
        assertNull(copy.date);assertEquals(setOf(1,2),copy.weeks);assertEquals(600,copy.start)
    }
    @Test fun fullDayDuplicateDoesNotOverwriteOrUseHiddenEarlyHours(){
        val original=Course(start=420,end=1440,weeks=setOf(1));val data=AppData(listOf(original),Settings(termStart=now.toLocalDate()))
        assertNull(duplicateCourse(data,original,now.toLocalDate()));assertEquals(listOf(original),data.courses)
    }
    @Test fun hidingEarlyGridDoesNotDeleteEarlyCourse(){
        val early=c(now.toLocalDate().plusDays(1),360)
        assertEquals(listOf(early.id),widgetOccurrences(AppData(listOf(early)),now).map {it.course.id})
    }
}
