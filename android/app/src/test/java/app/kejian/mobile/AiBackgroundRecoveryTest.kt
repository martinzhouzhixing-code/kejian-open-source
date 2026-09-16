package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test

class AiBackgroundRecoveryTest {
    @Test fun businessErrorsNeverEnterNetworkRetryLoop(){
        for(status in listOf(400,401,402,403,409,410,413,422))
            assertTrue("HTTP $status",aiFailureIsTerminal(aiCloudFailureCode(status)))
        for(status in listOf(429,500,502,503,504))
            assertFalse("HTTP $status",aiFailureIsTerminal(aiCloudFailureCode(status)))
        assertTrue(aiFailureIsTerminal("INPUT_REQUIRED"))
        assertTrue(aiFailureIsTerminal("failed"))
    }
    @Test fun scheduleReplayStopsBeforeServerMarkersCanExpire(){
        val now=1_900_000_000_000L
        assertTrue(aiBackgroundRetrySafe("image",now-23*60*60*1000L+1,now))
        assertFalse(aiBackgroundRetrySafe("command",now-23*60*60*1000L,now))
        assertTrue(aiBackgroundRetrySafe("mindmap",now-6*24*60*60*1000L,now))
        assertFalse(aiBackgroundRetrySafe("insight",now-7*24*60*60*1000L,now))
        assertFalse(aiBackgroundRetrySafe("image",now+300_001,now))
    }
    @Test fun awaitingRecoveryStillExposesStopUntilManualRetryIsRequired(){
        val task=AiBackgroundTask("id","owner","image","request","","","retry",0,2)
        assertTrue(task.busy)
        assertFalse(task.copy(attempts=3).busy)
        assertFalse(task.copy(state="result").busy)
        assertFalse(task.copy(state="cancelled").busy)
    }
    @Test fun localNoteCacheExpiryNeedsAnExplicitNewPaidRequest(){
        val turn=NoteInsightTurn("id","owner","note","digest","request","Question","[]",status="failed",errorCode="LOCAL_CACHE_EXPIRED")
        assertTrue(insightNeedsFreshRequest(turn))
        assertFalse(insightNeedsFreshRequest(turn.copy(status="retry",errorCode="INSIGHTS_RUNNING")))
    }
}
