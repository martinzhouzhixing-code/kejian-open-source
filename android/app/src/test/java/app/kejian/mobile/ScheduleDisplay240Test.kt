package app.kejian.mobile
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class ScheduleDisplay240Test {
    private val week=LocalDate.of(2026,9,14)
    private val settings=Settings(termStart=week)
    @Test fun weekendVisibilityUsesActualWeekAndExceptions(){
        val course=Course(day=6,weeks=setOf(2))
        val data=AppData(listOf(course),settings)
        assertEquals(5,visibleScheduleDays(data,week))
        assertEquals(7,visibleScheduleDays(data,week.plusWeeks(1)))
        assertEquals(7,visibleScheduleDays(data,week,true))
        assertEquals(5,visibleScheduleDays(data.copy(courses=listOf(course.copy(excluded=setOf(week.plusDays(12))))),week.plusWeeks(1)))
        assertEquals(7,visibleScheduleDays(data.copy(deadlines=listOf(Deadline(dueDate=week.plusDays(6)))),week))
    }
    @Test fun preferredHoursNeverHideActualLessons(){
        val data=AppData(listOf(Course(day=1,start=390,end=480,weeks=setOf(1)),Course(day=2,start=1200,end=1290,weeks=setOf(1))),settings.copy(visibleStart=480,visibleEnd=1200))
        assertEquals(390..1290,scheduleTimeRange(data,week))
        assertEquals(480..1200,scheduleTimeRange(data,week.plusWeeks(1)))
    }
    @Test fun remarksPersistIndependentlyAcrossWeeks(){
        val first=weeklyRemarkKey("course",week)
        assertEquals(first,weeklyRemarkKey("course",week.plusDays(5)))
        val second=weeklyRemarkKey("course",week.plusWeeks(1))
        assertNotEquals(first,second)
        val data=AppData(settings=settings.copy(visibleStart=480,visibleEnd=1200),weeklyRemarks=mapOf(first to "本周作业",second to "重点复习"))
        val result=DataJson.decode(DataJson.encode(data))
        assertEquals(data.weeklyRemarks,result.weeklyRemarks)
        assertEquals(480,result.settings.visibleStart)
        assertEquals(1200,result.settings.visibleEnd)
        assertTrue(result.settings.hideEmptyWeekends)
    }
    @Test fun movingAnOccurrenceKeepsItsWeeklyRemark(){
        val course=Course(id="original",day=1,weeks=setOf(1,2))
        val data=AppData(listOf(course),settings,weeklyRemarks=mapOf(weeklyRemarkKey(course.id,week) to "作业"))
        val changed=changeCourse(data,course,course,week,week.plusDays(1))
        val moved=changed.courses.single {it.id!=course.id}
        assertEquals("作业",changed.weeklyRemarks[weeklyRemarkKey(moved.id,week.plusDays(1))])
        assertEquals("作业",changed.weeklyRemarks[weeklyRemarkKey(course.id,week)])
    }
}
