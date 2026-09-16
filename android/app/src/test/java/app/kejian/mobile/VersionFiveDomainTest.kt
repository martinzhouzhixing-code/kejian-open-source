package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class VersionFiveDomainTest {
    @Test fun reminderMinutesRoundTripAndOldBackupDefaultsToTen() {
        val source=AppData(listOf(Course()),Settings(termStart=LocalDate.of(2026,8,31),reminderMinutes=37))
        assertEquals(37,DataJson.decode(DataJson.encode(source)).settings.reminderMinutes)
        val legacy=DataJson.encode(source).replace("\n    \"reminderMinutes\": 37,","")
        assertEquals(10,DataJson.decode(legacy).settings.reminderMinutes)
    }

    @Test fun nextColorUsesUnusedThenLeastUsedPaletteEntry() {
        assertEquals(0,nextCourseColor(emptyList()))
        assertEquals(2,nextCourseColor(listOf(Course(color=0),Course(color=1))))
        val all=(0 until 12).map {Course(color=it)}+Course(color=0)
        assertEquals(1,nextCourseColor(all))
    }

    @Test fun importedCoursesReceiveVariedColorsWithoutMutatingFields() {
        val incoming=listOf(Course(name="A",color=0),Course(name="B",color=0),Course(name="C",color=0))
        val colored=distributeCourseColors(listOf(Course(color=0)),incoming)
        assertEquals(listOf("A","B","C"),colored.map {it.name})
        assertEquals(3,colored.map {it.color}.distinct().size)
        assertFalse(0 in colored.map {it.color})
    }
}
