package app.kejian.mobile

import java.time.Instant
import java.time.LocalDate

/**
 * Strict lesson-note interchange format shared by storage, cloud and the AI pipeline.
 * KJN1|ID|课程ID|课程日期|标题|总结|要点JSON|待办JSON|逐字稿|秒数|创建时间
 * Unknown optional values use `~`; literal pipes, backslashes and newlines are escaped.
 */
object NoteExchange {
    const val EMPTY="~"
    fun encodeNote(note:LessonNote,includeLocalAudio:Boolean=true)=listOf(
        "KJN1",note.id,note.courseId?:EMPTY,note.occurrenceDate?.toString()?:EMPTY,note.title,
        note.summary.blank(),jsonArray(note.keyPoints),jsonArray(note.actionItems),note.transcript.blank(),
        note.durationSeconds.toString(),note.createdAt.toString(),if(includeLocalAudio)note.audioPath?:EMPTY else EMPTY
    ).joinToString("|"){escape(it)}

    fun parseLine(line:String):Result<LessonNote> = runCatching {
        val f=split(line.trim());require(f.size==12&&f[0]=="KJN1"){"KJN1 必须包含 12 个字段"}
        LessonNote(
            id=f[1].value().ifBlank {java.util.UUID.randomUUID().toString()},courseId=f[2].value().takeIf {it.isNotBlank()},
            occurrenceDate=f[3].value().takeIf {it.isNotBlank()}?.let(LocalDate::parse),title=f[4].value().ifBlank {"课堂录音"},
            summary=f[5].content(),keyPoints=parseArray(f[6].value()),actionItems=parseArray(f[7].value()),transcript=f[8].content(),
            durationSeconds=f[9].toInt(),createdAt=Instant.parse(f[10]),audioPath=f[11].value().takeIf {it.isNotBlank()}
        ).also {require(it.error()==null){it.error().orEmpty()}}
    }
    private fun jsonArray(values:List<String>)=org.json.JSONArray(values).toString()
    private fun parseArray(value:String):List<String>{if(value.isBlank())return emptyList();val a=org.json.JSONArray(value);return (0 until a.length()).map {a.getString(it)}}
    private fun String.blank()=if(isBlank())EMPTY else this
    private fun String.value()=if(trim()==EMPTY)"" else trim()
    private fun String.content()=if(this==EMPTY)""else this
    private fun escape(value:String)=buildString {value.forEach {ch->when(ch){'\\'->append("\\\\");'|'->append("\\|");'\n'->append("\\n");'\r'->Unit;else->append(ch)}}}
    private fun split(line:String):List<String>{val out=mutableListOf<String>();val current=StringBuilder();var escaped=false;line.forEach {ch->when {escaped->{current.append(if(ch=='n')'\n' else ch);escaped=false};ch=='\\'->escaped=true;ch=='|'->{out+=current.toString();current.clear()};else->current.append(ch)}};require(!escaped){"末尾转义符不完整"};out+=current.toString();return out}
}
