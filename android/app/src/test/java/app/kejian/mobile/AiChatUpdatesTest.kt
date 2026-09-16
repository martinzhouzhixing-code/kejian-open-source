package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test

class AiChatUpdatesTest {
    @Test fun replacingPendingKeepsStableIdentityAndHistory(){
        val old=AiChatMessage(false,"Saved response")
        val question=AiChatMessage(true,"New question")
        val pending=AiChatMessage(false,"Working",pending=true,taskId="task")
        val updated=resolveAiPending(listOf(old,question,pending),AiChatMessage(false,"Clarify your request",error=true))
        assertEquals(listOf(old,question),updated.dropLast(1));assertEquals(pending.id,updated.last().id)
        assertEquals(pending.createdAt,updated.last().createdAt);assertEquals("task",updated.last().taskId)
        assertFalse(updated.last().pending);assertTrue(updated.last().error)
    }
    @Test fun noPendingAppendsWithoutChangingOldMessages(){
        val old=AiChatMessage(false,"Old");val next=AiChatMessage(false,"New")
        assertEquals(listOf(old,next),resolveAiPending(listOf(old),next))
    }
}
