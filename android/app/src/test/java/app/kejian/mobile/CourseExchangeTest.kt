package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CourseExchangeTest {
    @Test fun strictRecordRoundTripsEveryModuleField(){
        val original=Course(id="course-1",name="ENG口语",address="D楼",room="D207",teacher="",day=2,start=690,end=750,weeks=setOf(1,2,4),color=1,excluded=setOf(LocalDate.of(2026,9,8)),reminder=false)
        val line=CourseExchange.encodeCourse(original)
        assertEquals(13,line.split('|').size)
        assertTrue(line.contains("|~|"))
        val result=CourseExchange.parse(line)
        assertTrue(result.errors.toString(),result.errors.isEmpty())
        assertEquals(original,result.courses.single())
    }
    @Test fun oneOffDateUsesPlaceholderForWeeks(){
        val date=LocalDate.of(2026,9,3);val original=Course(id="once",name="讲座",day=4,start=600,end=660,date=date,weeks=emptySet())
        val line=CourseExchange.encodeCourse(original);assertTrue(line.contains("|~|青绿|2026-09-03|"));assertEquals(original,CourseExchange.parse(line).courses.single())
    }
    @Test fun missingFieldsMustUsePlaceholderAndCannotBeOmitted(){
        val valid="KJ1|~|ENG|~|~|~|周一|11:30-12:30|1-16|蓝色|~|~|是"
        assertTrue(CourseExchange.parse(valid).errors.isEmpty())
        assertFalse(CourseExchange.parse("KJ1|ENG|周一|11:30-12:30").errors.isEmpty())
    }
    @Test fun compactExampleIsAcceptedButAlwaysNormalizesToStrictKj1(){
        val parsed=CourseExchange.parse("ENG_D楼_D207_11:30-12:30_蓝色")
        assertTrue(parsed.errors.toString(),parsed.errors.isEmpty());assertEquals("ENG",parsed.courses.single().name)
        assertTrue(CourseExchange.encode(parsed.courses).startsWith("KJ1|"));assertEquals(13,CourseExchange.encode(parsed.courses).split('|').size)
    }
}
