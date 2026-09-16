package app.kejian.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RecordingLibrary21Test {
    @Test fun detailedNotesRoundTripWithoutTruncation(){
        val note=LessonNote(title="Lecture",summary="Detail and context.\n\n".repeat(7000),keyPoints=List(500){"Concept $it\n"+"Specific explanation. ".repeat(80)},actionItems=List(130){"Assignment $it"},transcript="Actual speech. ".repeat(20000),durationSeconds=7200)
        assertNull(note.error())
        val decoded=NoteExchange.parseLine(NoteExchange.encodeNote(note)).getOrThrow()
        assertEquals(note,decoded)
        assertEquals(note.summary.replace(Regex("\\n\\s*\\n"),""),noteTextParagraphs(note.summary).joinToString(""))
    }
    @Test fun summaryLimitsRejectOverlargeTextButAcceptExactBoundary(){
        assertNull(LessonNote(summary="a".repeat(400000),keyPoints=List(2000){"x".repeat(12000)}).error())
        assertNotNull(LessonNote(summary="a".repeat(400001)).error())
        assertNotNull(LessonNote(keyPoints=List(2001){"x"}).error())
        assertNotNull(LessonNote(keyPoints=listOf("x".repeat(12001))).error())
    }
    @Test fun resultUpdatesSameLibraryEntryAndKeepsUserAssociation(){
        val old=LessonNote(id="local-id",title="My lecture",courseId="course-b",occurrenceDate=LocalDate.parse("2026-09-10"),audioPath="local.m4a",createdAt=Instant.EPOCH)
        val result=LessonNote(id="server-id",title="AI title",summary="Details",transcript="Speech")
        val merged=mergeRecordingResult(old,result,"local.m4a")
        assertEquals("local-id",merged.id);assertEquals("course-b",merged.courseId);assertEquals(old.occurrenceDate,merged.occurrenceDate)
        assertEquals("My lecture",merged.title);assertEquals(Instant.EPOCH,merged.createdAt);assertEquals("Speech",merged.transcript)
    }
    @Test fun relinkingOrRemovingLocalAudioDoesNotInvalidateMindMapButContentChangeDoes(){
        val note=LessonNote(id="same",title="Algorithms",summary="Quick sort",keyPoints=listOf("partition"))
        val digest=noteContentDigest(note)
        assertEquals(digest,noteContentDigest(note.copy(courseId="another",occurrenceDate=LocalDate.now(),audioPath="private.m4a")))
        assertNotEquals(digest,noteContentDigest(note.copy(summary="Merge sort")))
        assertNotEquals(digest,noteContentDigest(note.copy(transcript="New speech")))
    }
    private fun tree(nodes:List<MindMapNode>)=LessonMindMap("Lesson",nodes,"abc").toJson()
    @Test fun mapValidatorRejectsCyclesDisconnectedParentsAndStaleDigest(){
        val root=MindMapNode("r",null,"Root")
        assertTrue(runCatching {LessonMindMap.parse(tree(listOf(root,MindMapNode("a","b","A"),MindMapNode("b","a","B"))),"abc")}.isFailure)
        assertTrue(runCatching {LessonMindMap.parse(tree(listOf(root,MindMapNode("a","missing","A"))),"abc")}.isFailure)
        assertTrue(runCatching {LessonMindMap.parse(tree(listOf(root)),"changed")}.isFailure)
        assertEquals(2,LessonMindMap.parse(tree(listOf(root,MindMapNode("a","r","A"))),"abc").nodes.size)
    }
    @Test fun mindMapLayoutUsesAllNodesWithNoOverlappingSiblings(){
        val map=LessonMindMap("Lesson",listOf(MindMapNode("r",null,"Root"))+List(35){MindMapNode("c$it","r","Long concept\n".repeat(it%7+1))},"hash")
        val boxes=layoutMindMap(map){it.split("\n")}
        assertEquals(map.nodes.size,boxes.size)
        val siblings=boxes.filter {it.node.parentId=="r"}.sortedBy {it.y}
        siblings.zipWithNext().forEach {(first,second)->assertTrue(first.y+first.height<second.y)}
    }
    @Test fun initialMindMapFitContainsWholeTreeAndDemoAvoidsTopGuideCard(){
        val map=LessonMindMap("Lesson",listOf(MindMapNode("r",null,"Root"),MindMapNode("a","r","Overview"),MindMapNode("b","r","Concepts"),MindMapNode("c","b","Applications"),MindMapNode("d","r","Actions")),"digest")
        val boxes=layoutMindMap(map){listOf(it)}
        listOf(false,true).forEach {demo->
            val viewport=fitMindMap(boxes,360f,520f,demo)
            boxes.forEach {box->
                val left=(box.x+viewport.pan.x)*viewport.zoom
                val top=(box.y+viewport.pan.y)*viewport.zoom
                val right=(box.x+box.width+viewport.pan.x)*viewport.zoom
                val bottom=(box.y+box.height+viewport.pan.y)*viewport.zoom
                assertTrue(left>=15.9f);assertTrue(right<=344.1f)
                assertTrue(top>=(if(demo)520f*.32f else 20f)-.1f);assertTrue(bottom<=448.1f)
            }
        }
    }
}
