package app.kejian.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkCoordinatorTest {
    @Test fun foregroundAndScheduledHostsCannotRunSameAudioConcurrently()=runBlocking {
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>();var executions=0
        val foreground=async {BackgroundWorkCoordinator.run("test-audio") {executions++;entered.complete(Unit);finish.await()}}
        entered.await()
        assertFalse(BackgroundWorkCoordinator.run("test-audio"){executions++})
        finish.complete(Unit);assertTrue(foreground.await());assertEquals(1,executions)
        assertTrue(BackgroundWorkCoordinator.run("test-audio"){executions++});assertEquals(2,executions)
    }
    @Test fun pausingAudioDoesNotCancelDocumentSharingScheduledHost()=runBlocking {
        supervisorScope {
            val audioStarted=CompletableDeferred<Unit>();val documentStarted=CompletableDeferred<Unit>();val documentFinish=CompletableDeferred<Unit>()
            val audio=launch {BackgroundWorkCoordinator.run("test-audio"){audioStarted.complete(Unit);awaitCancellation()}}
            val document=async {BackgroundWorkCoordinator.run("test-document"){documentStarted.complete(Unit);documentFinish.await()}}
            audioStarted.await();documentStarted.await()
            BackgroundWorkCoordinator.cancel("test-audio");audio.join()
            assertTrue(audio.isCancelled);assertTrue(document.isActive)
            assertFalse(BackgroundWorkCoordinator.active("test-audio"));assertTrue(BackgroundWorkCoordinator.active("test-document"))
            documentFinish.complete(Unit);assertTrue(document.await())
        }
    }
}
