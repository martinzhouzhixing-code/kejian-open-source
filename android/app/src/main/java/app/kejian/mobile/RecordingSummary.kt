package app.kejian.mobile

import org.json.JSONObject
import java.util.UUID

internal fun summaryRequest(note:LessonNote,language:String,requirements:String,allowCharge:Boolean=false):JSONObject {
    require(language in setOf("zh-CN","en")&&requirements.length<=1000&&note.transcript.isNotBlank())
    return JSONObject().put("requestId",UUID.randomUUID().toString()).put("noteId",note.id)
        .put("contentDigest",noteContentDigest(note)).put("transcript",note.transcript)
        .put("language",language).put("requirements",requirements).put("allowTokenCharge",allowCharge)
}

internal fun applyRegeneratedSummary(note:LessonNote,response:JSONObject):LessonNote {
    require(response.getString("noteId")==note.id)
    val result=response.getJSONObject("summary")
    require(result.getString("protocol")=="KJN1-SUMMARY/3")
    require(result.getString("language") in setOf("zh-CN","en"))
    fun strings(key:String):List<String> = result.getJSONArray(key).let {a->(0 until a.length()).map {a.getString(it)}}
    val review=strings("uncertainties")
    val summary=result.getString("summary")+(if(review.isEmpty())""else (if(result.getString("language")=="en")"\n\nNeeds review:\n"else"\n\n待确认：\n")+review.joinToString("\n"))
    require(summary.isNotBlank())
    // Association, audio path, title and transcript may have been edited while waiting.
    // Only generated fields are replaced; the original source remains on the device.
    return note.copy(summary=summary,keyPoints=strings("keyPoints"),actionItems=strings("actionItems"))
}
