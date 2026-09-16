package app.kejian.mobile

import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class EditHistoryTest {
    private val term=LocalDate.of(2026,8,31)
    private val c=Course(id="series",weeks=setOf(1,2,3),start=490,end=585,address="北校区",room="A201")
    private val original=AppData(listOf(c),Settings(termStart=term))
    @Test fun duplicateKeepsTitleAndGetsIndependentIdentity(){
        val duplicate=duplicateCourse(original,c,term)!!
        assertEquals(c.name,duplicate.name);assertNotEquals(c.id,duplicate.id)
        assertEquals(c.duration,duplicate.duration)
    }
    @Test fun timetableUndoDoesNotReplaceNotesSavedInTheBackground(){
        val edited=original.copy(courses=listOf(c.copy(start=600,end=695)))
        val history=EditHistory();history.record(original,edited)
        val note=LessonNote(id="new-notes",title="Detailed notes",summary="New background result")
        val current=edited.copy(notes=listOf(note))
        val undone=history.undoCandidate(current)!!;history.didUndo()
        assertEquals(listOf(note),undone.notes)
        assertEquals(listOf(note),history.redoCandidate(undone)!!.notes)
    }
    @Test fun deletingOneOccurrenceThenUndoRedoPreservesFutureWeeksAndIdentity(){
        val deleted=changeCourse(original,c,null,term,term)
        assertFalse(deleted.courses.single().occurs(term,term));assertTrue(deleted.courses.single().occurs(term.plusWeeks(1),term))
        val history=EditHistory();history.record(original,deleted)
        assertEquals(original,history.undoCandidate(deleted));history.didUndo()
        assertEquals(deleted,history.redoCandidate(original));history.didRedo();assertFalse(history.canRedo)
    }
    @Test fun allWeeksDeletionRestoresDetachedRecordsAndDoesNotUndoAppearance(){
        val detached=c.copy(id="detached",date=term.plusDays(2))
        val before=original.copy(courses=listOf(c,detached),settings=original.settings.copy(editAllWeeks=true))
        val deleted=changeCourse(before,c,null,term,term)
        assertEquals(listOf(detached),deleted.courses)
        val history=EditHistory();history.record(before,deleted)
        val themed=deleted.copy(settings=deleted.settings.copy(themeMode="dark",gridZoom=1.7f,reminders=false))
        val restored=history.undoCandidate(themed)!!
        assertEquals(before.courses,restored.courses);assertEquals(themed.settings,restored.settings)
    }
    @Test fun creationMovementDeletionUndoRedoAndBranching(){
        val empty=original.copy(courses=emptyList());val moved=changeCourse(original,c,c.copy(day=2,start=630,end=725),term,term.plusDays(1))
        val history=EditHistory();history.record(empty,original);history.record(original,moved)
        assertEquals(original,history.undoCandidate(moved));history.didUndo()
        assertEquals(empty,history.undoCandidate(original));history.didUndo()
        assertEquals(original,history.redoCandidate(empty));history.didRedo()
        // Zoom-only change neither adds an undo step nor erases the redo branch.
        history.record(original,original.copy(settings=original.settings.copy(gridZoom=1.5f)))
        assertTrue(history.canRedo)
        val renamed=original.copy(courses=listOf(c.copy(name="新名称")))
        history.record(original,renamed);assertFalse(history.canRedo)
        assertEquals(original,history.undoCandidate(renamed))
    }
    @Test fun boundedHistoryAndUncommittedCandidateDoNotMoveCursor(){
        val history=EditHistory(2);var data=original
        repeat(3){index->val next=data.copy(courses=listOf(c.copy(name="$index")));history.record(data,next);data=next}
        val candidate=history.undoCandidate(data)
        assertEquals(candidate,history.undoCandidate(data));assertFalse(history.canRedo)
        history.didUndo();data=candidate!!;data=history.undoCandidate(data)!!;history.didUndo()
        assertFalse(history.canUndo);assertEquals("0",data.courses.single().name)
    }
    @Test fun scopeMoveRetainsArbitraryDurationAndShiftsExcludedDates(){
        val before=original.copy(courses=listOf(c.copy(excluded=setOf(term))),settings=original.settings.copy(editAllWeeks=true))
        val moved=changeCourse(before,before.courses.single(),c.copy(day=3,start=600,end=695),term,term.plusDays(2))
        assertEquals(setOf(term.plusDays(2)),moved.courses.single().excluded)
        assertEquals(95,moved.courses.single().duration)
    }
    @Test fun deadlineCreateEditDeleteParticipatesInUndoRedo(){
        val deadline=Deadline(id="d1",title="作业",dueDate=term.plusDays(2))
        val created=original.copy(deadlines=listOf(deadline));val edited=created.copy(deadlines=listOf(deadline.copy(title="项目截止")))
        val history=EditHistory();history.record(original,created);history.record(created,edited)
        assertEquals(created,history.undoCandidate(edited));history.didUndo()
        assertEquals(original,history.undoCandidate(created));history.didUndo()
        assertEquals(created,history.redoCandidate(original))
    }
}
