package app.kejian.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecordingInsights211Test {
    @Test fun knowledgeHeadingsKeepDecimalValuesWhileRemovingActualListNumbers(){
        listOf("1.5 million tokens","3.14159 is pi","12.50 元","1.25e3 measurements").forEach {decimal->
            assertEquals(decimal,noteKnowledgeSection(decimal,0,true).title)
            assertEquals(decimal,noteKnowledgeSection("$decimal\n- Keep this exact value",0,true).title)
        }
        listOf("1. First concept" to "First concept","2.Second concept" to "Second concept","3)Third concept" to "Third concept","4、第四个知识点" to "第四个知识点","5. **Bold concept**" to "Bold concept").forEach {(source,title)->
            assertEquals(title,noteKnowledgeSection(source,0,true).title)
        }
        assertEquals("1.5 million tokens",noteKnowledgeSection("1.5 million tokens: monthly allowance",0,true).title)
    }
    @Test fun onlyWithinRetentionCanAnUncertainRequestPromiseSameIdRetry(){
        val turn=NoteInsightTurn("id","owner","note","digest","request","question","[]",status="retry",createdAt=1000)
        assertFalse(insightNeedsFreshRequest(turn,1000+INSIGHT_RETRY_WINDOW_MS-1))
        assertTrue(insightNeedsFreshRequest(turn,1000+INSIGHT_RETRY_WINDOW_MS))
        assertTrue(insightNeedsFreshRequest(turn.copy(status="failed",errorCode="INSIGHTS_EXPIRED"),2000))
    }
    @Test fun documentFormattingKeepsHierarchyAndAllLongText(){
        val text="## Overview\n\nOrdered input is required.\n\n- Compare the middle element\n  - Discard half of the range\n    - Stop when left > right\n\n"+"A".repeat(13001)
        val blocks=noteDocumentBlocks(text)
        assertEquals("heading",blocks.first().kind)
        assertEquals(listOf(0,1,2),blocks.filter {it.kind=="bullet"}.map {it.level})
        assertEquals(13001,blocks.filter {it.text.startsWith("A")}.sumOf {it.text.length})
        assertTrue(blocks.any {it.text.contains("left > right")})
        val section=noteKnowledgeSection("1. **Binary search**\n- Sorted input\n  - Check empty arrays",0,true)
        assertEquals("Binary search",section.title)
        assertEquals("- Sorted input\n  - Check empty arrays",section.details)
    }
    @Test fun onlyExactCitationsFromThisNoteAreAccepted(){
        val note=LessonNote(summary="The array must be sorted.",transcript="The teacher said sorted input is required.")
        fun answer(quote:String,source:String="summary",index:Int=0)=JSONObject().put("version",1).put("text","Sorted input is required. [s0]").put("insufficientEvidence",false)
            .put("citations",JSONArray().put(JSONObject().put("id","s0").put("source",source).put("index",index).put("quote",quote)))
        assertEquals(1,NoteInsightAnswer.parse(answer("array must be sorted"),note).citations.size)
        assertTrue(runCatching {NoteInsightAnswer.parse(answer("invented homework"),note)}.isFailure)
        assertTrue(runCatching {NoteInsightAnswer.parse(answer("array must be sorted",index=1),note)}.isFailure)
        assertTrue(runCatching {NoteInsightAnswer.parse(answer("array must be sorted",source="otherRecording"),note)}.isFailure)
        val unsupported=JSONObject().put("version",1).put("text","The notes do not provide a deadline.").put("insufficientEvidence",true).put("citations",JSONArray())
        assertTrue(NoteInsightAnswer.parse(unsupported,note).insufficientEvidence)
    }
    @Test fun historyCannotCrossAccountsNotesOrContentVersions(){
        val note=LessonNote(id="same",summary="Original notes")
        val answer=NoteInsightAnswer("Saved answer",true,emptyList(),5,10)
        fun turn(owner:String,n:LessonNote,q:String)=NoteInsightTurn(q,owner,n.id,noteContentDigest(n),"request",q,"[]",answer=answer)
        val turns=listOf(turn("a",note,"owned"),turn("b",note,"other-account"),turn("a",note.copy(id="different"),"other-note"),turn("a",note.copy(summary="old version"),"old-content"))
        val history=JSONArray(insightHistory(turns,"a",note))
        assertEquals(2,history.length());assertEquals("owned",history.getJSONObject(0).getString("content"))
        assertFalse(history.toString().contains("other"));assertFalse(history.toString().contains("old-content"))
    }
    @Test fun accountOrNoteSwitchBlocksInflightResultButRelinkingDoesNot(){
        val note=LessonNote(summary="Current notes")
        val digest=noteContentDigest(note)
        assertTrue(insightCanApply("a","a","a",digest,note.copy(courseId="new course")))
        assertFalse(insightCanApply("a","b","b",digest,note))
        assertFalse(insightCanApply("a","a",null,digest,note))
        assertFalse(insightCanApply("a","a","b",digest,note))
        assertFalse(insightCanApply("a","a","a",digest,note.copy(summary="Edited notes")))
        assertFalse(insightCanApply("a","a","a",digest,null))
    }
}
