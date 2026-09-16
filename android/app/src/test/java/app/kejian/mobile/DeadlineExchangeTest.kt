package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DeadlineExchangeTest {
    @Test fun deadlineRecordRoundTripsAndKeepsUnknownTimeAsPlaceholder(){
        val original=Deadline(id="deadline-1",title="ENG | Essay",dueDate=LocalDate.of(2026,9,18),dueMinute=null,details="提交 PDF\n附封面",courseId=null,color=3,reminder=false)
        val line=DeadlineExchange.encodeDeadline(original)
        assertTrue(line.startsWith("KJD1|deadline-1|"))
        assertTrue(line.contains("|~|~|"))
        assertEquals(original,DeadlineExchange.parseLine(line).getOrThrow())
    }

    @Test fun explicitMidnightIsDifferentFromUnknownTime(){
        val midnight=Deadline(id="midnight",title="项目",dueDate=LocalDate.of(2026,10,1),dueMinute=0)
        assertTrue(DeadlineExchange.encodeDeadline(midnight).contains("|00:00|"))
        assertEquals(0,DeadlineExchange.parseLine(DeadlineExchange.encodeDeadline(midnight)).getOrThrow().dueMinute)
        val unknown=midnight.copy(dueMinute=null)
        assertNull(DeadlineExchange.parseLine(DeadlineExchange.encodeDeadline(unknown)).getOrThrow().dueMinute)
    }

    @Test fun mixedScheduleAndSchemaThreeRoundTrip(){
        val course=Course(id="course-1",name="线性代数")
        val deadline=Deadline(id="deadline-2",title="线性代数作业",dueDate=LocalDate.of(2026,9,9),courseId=course.id,color=course.color)
        val data=AppData(listOf(course),Settings(termStart=LocalDate.of(2026,8,31)),listOf(deadline))
        val mixed=ScheduleExchange.parse(ScheduleExchange.encode(data).joinToString("\n"))
        assertTrue(mixed.errors.toString(),mixed.errors.isEmpty())
        assertEquals(data.courses,mixed.courses);assertEquals(data.deadlines,mixed.deadlines)
        assertEquals(data,DataJson.decode(DataJson.encode(data)))
    }

    @Test fun malformedOrMissingDeadlineFieldsAreRejected(){
        assertTrue(DeadlineExchange.parseLine("KJD1|id|作业|2026-09-09").isFailure)
        assertTrue(DeadlineExchange.parseLine("KJD1|id|作业|bad|~|~|~|蓝色|否").isFailure)
        assertTrue(DeadlineExchange.parseLine("KJD1|id|作业|2026-09-09|25:00|~|~|蓝色|否").isFailure)
    }
}
