package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class NoteExchangeTest {
    @Test fun `KJN1 round trips multilingual text and separators`() {
        val note=LessonNote(id="n-1",courseId="c-1",occurrenceDate=LocalDate.of(2026,9,5),title="线代 | 第三讲",summary="矩阵\\秩\n结论",keyPoints=listOf("A|B","特征值"),actionItems=listOf("周五提交"),transcript="老师说：a|b",durationSeconds=3721,createdAt=Instant.parse("2026-09-05T01:02:03Z"),audioPath="/local/only.m4a")
        assertEquals(note,NoteExchange.parseLine(NoteExchange.encodeNote(note)).getOrThrow())
    }
    @Test fun `cloud JSON strips only local audio`() {
        val note=LessonNote(title="独立笔记",summary="总结",audioPath="/private/audio.m4a")
        val cloud=DataJson.encode(AppData(notes=listOf(note)),includeLocalMedia=false)
        val restored=DataJson.decode(cloud).notes.single()
        assertNull(restored.audioPath);assertEquals("总结",restored.summary);assertEquals(note.id,restored.id)
    }
}
