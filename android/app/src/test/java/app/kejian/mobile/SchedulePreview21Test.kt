package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchedulePreview21Test {
    private val term = LocalDate.of(2026, 9, 7)
    private val settings = Settings(termStart = term)

    @Test fun deletionOfAllEventsRemainsAnInspectableChange() {
        val original = Course(id = "a", day = 3, start = 675, end = 790, weeks = setOf(1))
        val before = AppData(courses = listOf(original), settings = settings)
        val after = before.copy(courses = emptyList())
        val changes = schedulePreviewDelta(before, after)
        assertEquals(listOf(original), changes.removed)
        assertEquals(listOf(term), schedulePreviewWeeks(before, after, changes))
        assertEquals(1, before.courses.size)
    }

    @Test fun movedDateShowsBothOldAndNewWeeksWithoutRoundingMinutes() {
        val original = Course(id = "a", date = term, day = 1, start = 675, end = 790)
        val revised = original.copy(date = term.plusDays(10), day = 4, start = 885, end = 967)
        val before = AppData(listOf(original), settings)
        val after = before.copy(courses = listOf(revised))
        val changes = schedulePreviewDelta(before, after)
        assertEquals(listOf(original to revised), changes.changed)
        assertEquals(listOf(term, term.plusWeeks(1)), schedulePreviewWeeks(before, after, changes))
        assertEquals(885, changes.changed.single().second.start)
        assertEquals(967, changes.changed.single().second.end)
    }

    @Test fun sparseRecurrenceAndExcludedOccurrencesUseActualDates() {
        val added = Course(id = "a", day = 4, weeks = setOf(1, 9), excluded = setOf(term.plusDays(3)))
        val before = AppData(settings = settings)
        val after = before.copy(courses = listOf(added))
        assertEquals(listOf(term.plusWeeks(8)), schedulePreviewWeeks(before, after, schedulePreviewDelta(before, after)))
    }

    @Test fun deadlineWithoutCoursesStillOpensItsCorrectWeek() {
        val due = Deadline(id = "due", title = "Report", dueDate = term.plusDays(20))
        val before = AppData(settings = settings)
        val after = before.copy(deadlines = listOf(due))
        val changes = schedulePreviewDelta(before, after)
        assertEquals(1, changes.addedCount)
        assertEquals(listOf(term.plusWeeks(2)), schedulePreviewWeeks(before, after, changes))
        assertEquals(null, due.dueMinute)
    }

    @Test fun restoringDifferentTermShowsTheShiftedOccurrenceDates() {
        val course = Course(id = "a", weeks = setOf(1))
        val before = AppData(listOf(course), settings)
        val after = before.copy(settings = settings.copy(termStart = term.plusWeeks(2)))
        val changes = schedulePreviewDelta(before, after)
        assertEquals(1, changes.changedCount)
        assertEquals(listOf(term, term.plusWeeks(2)), schedulePreviewWeeks(before, after, changes))
    }
}
