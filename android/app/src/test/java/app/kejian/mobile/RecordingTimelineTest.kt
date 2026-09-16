package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test

class RecordingTimelineTest {
    @Test fun multiplePausesExcludeAllIdleTimeAndPreserveOneSession(){
        var monotonic=400L;val clock=RecordingTimeline {monotonic}
        assertTrue(clock.start());monotonic+=4250;assertTrue(clock.pause())
        assertTrue(clock.sessionActive);assertEquals(4250L,clock.elapsedMillis())
        monotonic+=60_000;assertEquals(4250L,clock.elapsedMillis())
        assertTrue(clock.resume());monotonic+=5750;assertTrue(clock.canStop())
        assertTrue(clock.pause());monotonic+=600_000
        assertEquals(10_000L,clock.finish());assertFalse(clock.sessionActive)
        monotonic+=5000;assertEquals(10_000L,clock.elapsedMillis())
    }
    @Test fun shortPausedSessionCannotMeetTenSecondsByWaiting(){
        var now=0L;val clock=RecordingTimeline {now}
        clock.start();now=9999;clock.pause();now=999_999
        assertFalse(clock.canStop());assertEquals(9999L,clock.elapsedMillis())
        clock.resume();now++;assertTrue(clock.canStop());assertEquals(10_000L,clock.finish())
    }
    @Test fun duplicateOrOutOfOrderActionsDoNotRestartOrLoseAudio(){
        var now=0L;val clock=RecordingTimeline {now}
        assertFalse(clock.pause());assertFalse(clock.resume());assertEquals(0L,clock.finish())
        clock.start();now=12_000;assertFalse(clock.start());assertFalse(clock.resume())
        clock.pause();assertFalse(clock.pause());assertFalse(clock.start())
        now=900_000;assertEquals(12_000L,clock.finish());assertFalse(clock.resume())
        assertEquals(12_000L,clock.finish());assertTrue(clock.start());assertEquals(0L,clock.elapsedMillis())
    }
    @Test fun systemWallClockChangesCannotAffectRecordingDuration(){
        var monotonic=100L;var wall=1700000000000L
        val clock=RecordingTimeline {monotonic};clock.start()
        wall-=24*60*60*1000;monotonic+=5000;assertEquals(5000L,clock.elapsedMillis())
        clock.pause();wall+=48*60*60*1000;monotonic+=100_000
        assertEquals(5000L,clock.elapsedMillis());assertTrue(wall>0)
        clock.resume();monotonic+=6000;assertEquals(11_000L,clock.finish())
    }
}
