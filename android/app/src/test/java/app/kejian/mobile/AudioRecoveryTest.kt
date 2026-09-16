package app.kejian.mobile

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioRecoveryTest {
    @Test fun transientFailureAfterThreeShortRetriesStillAutomaticallyContinues()=runBlocking {
        var calls=0;var waits=0;val identities=mutableListOf<String>()
        val id="original-request"
        val result=recoverAudioTransport(onRetry={_,_->waits++}) {
            identities+=id
            retryAudioRequest(pause={}){if(++calls<=6)throw IOException("Offline after switching networks");"completed"}
        }
        assertEquals("completed",result);assertEquals(7,calls);assertEquals(2,waits)
        assertEquals(setOf(id),identities.toSet())
    }
    @Test fun explicitPauseDuringRecoveryStopsBeforeAnotherAttempt()=runBlocking {
        var paused=false;var calls=0
        try {
            recoverAudioTransport(beforeAttempt={if(paused)throw CancellationException("User paused")},onRetry={_,_->paused=true}){
                calls++;throw IOException("Network interrupted")
            };fail("Must remain paused")
        }catch(_:CancellationException){}
        assertEquals(1,calls)
    }
    @Test fun accountChangeCannotResumeExistingWork() {
        assertFalse(AudioRecoveryPolicy.canResume(false,"owner-a","owner-b"))
        assertFalse(AudioRecoveryPolicy.canResume(false,"owner-a",null))
        assertFalse(AudioRecoveryPolicy.canResume(true,"owner-a","owner-a"))
        assertTrue(AudioRecoveryPolicy.canResume(false,"owner-a","owner-a"))
    }
    @Test fun permanentFailureDoesNotLoopOrCreateNewIdentity()=runBlocking {
        var calls=0
        try {recoverAudioTransport(onRetry={_,_->fail("No retry for forbidden")}){calls++;throw CloudApiException(403,"Quota")};fail("Expected quota")}
        catch(error:CloudApiException){assertEquals(403,error.status)}
        assertEquals(1,calls)
    }
    @Test fun continuousOfflineHasBoundedActiveRetryWindow()=runBlocking {
        var now=0L;var calls=0
        try {recoverAudioTransport(onRetry={_,remaining->now+=remaining},nowMillis={now}){calls++;throw IOException("Offline")};fail("Expected deferred recovery")}
        catch(_:IOException){}
        assertEquals(2,calls);assertEquals(AudioRecoveryPolicy.ACTIVE_RETRY_WINDOW_MS,now)
    }
    @Test fun throughputCountsOnlyNewAcknowledgedBytesNotReplayedChunks() {
        val rate=AudioUploadRate()
        assertEquals(0L,rate.update(1_000_000,0)) // Existing resumable offset isn't new traffic.
        assertEquals(100_000L,rate.update(1_100_000,1000))
        assertEquals(50_000L,rate.update(1_100_000,2000)) // Same ack, not a second upload.
        assertEquals(2L,rate.remainingSeconds(1_200_000,1_100_000))
        assertEquals(0L,rate.update(0,3000))
        assertNull(rate.remainingSeconds(1_200_000,0))
    }
}
