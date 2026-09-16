package app.kejian.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NoteCitation(val id:String,val source:String,val index:Int,val quote:String,val url:String?=null,val title:String?=null){
    fun toJson()=JSONObject().put("id",id).put("source",source).put("index",index).put("quote",quote).put("url",url).put("title",title)
    fun sourceText(note:LessonNote):String?=when(source){
        "summary"->note.summary.takeIf {index==0};"transcript"->note.transcript.takeIf {index==0}
        "keyPoint"->note.keyPoints.getOrNull(index);"actionItem"->note.actionItems.getOrNull(index);else->null
    }
}
data class NoteInsightAnswer(val text:String,val insufficientEvidence:Boolean,val citations:List<NoteCitation>,val inputTokens:Int,val outputTokens:Int){
    fun toJson()=JSONObject().put("version",1).put("text",text).put("insufficientEvidence",insufficientEvidence)
        .put("citations",JSONArray().apply {citations.forEach {put(it.toJson())}}).put("inputTokens",inputTokens).put("outputTokens",outputTokens)
    companion object {
        fun parse(json:JSONObject,note:LessonNote?=null,usage:JSONObject?=null):NoteInsightAnswer{
            require(json.getInt("version")==1)
            val text=json.getString("text");require(text.isNotBlank()&&text.length<=20000)
            val a=json.getJSONArray("citations");require(a.length()<=12)
            val citations=(0 until a.length()).map {i->val c=a.getJSONObject(i)
                NoteCitation(c.getString("id"),c.getString("source"),c.getInt("index"),c.getString("quote"),c.optString("url").takeIf {it.isNotBlank()&&it!="null"},c.optString("title").takeIf {it.isNotBlank()&&it!="null"})}
            require(citations.map {it.id}.distinct().size==citations.size)
            citations.forEach {c->
                require(c.id.matches(Regex("[skatw][0-9]{1,23}"))&&c.index in 0..1999&&c.quote.length in 4..600&&text.contains("[${c.id}]"))
                require(c.source in setOf("summary","transcript","keyPoint","actionItem","web"))
                if(c.source=="web")require(safeNoteUrl(c.url.orEmpty()))
                if(note!=null&&c.source!="web")require(c.sourceText(note)?.contains(c.quote)==true){"Citation is not present in this recording"}
            }
            require(Regex("\\[([skatw][0-9]{1,23})\\]").findAll(text).map {it.groupValues[1]}.toSet()==citations.map {it.id}.toSet())
            val insufficient=json.getBoolean("insufficientEvidence")
            require(insufficient||citations.isNotEmpty())
            val input=usage?.getInt("inputTokens")?:json.optInt("inputTokens")
            val output=usage?.getInt("outputTokens")?:json.optInt("outputTokens")
            require(input>=0&&output>=0)
            return NoteInsightAnswer(text,insufficient,citations,input,output)
        }
    }
}
data class NoteInsightTurn(val id:String,val owner:String,val noteId:String,val digest:String,val requestId:String,
    val question:String,val history:String,val status:String="pending",val answer:NoteInsightAnswer?=null,
    val errorCode:String="",val createdAt:Long=System.currentTimeMillis(),val webSearch:Boolean=false)
internal const val INSIGHT_RETRY_WINDOW_MS=30L*24*60*60*1000
internal fun insightRetryWindowExpired(turn:NoteInsightTurn,now:Long=System.currentTimeMillis())=
    now-turn.createdAt>=INSIGHT_RETRY_WINDOW_MS||turn.createdAt-now>5*60*1000
internal fun insightNeedsFreshRequest(turn:NoteInsightTurn,now:Long=System.currentTimeMillis())=
    insightRetryWindowExpired(turn,now)||turn.status=="failed"&&turn.errorCode in setOf("INSIGHTS_FAILED","INSIGHTS_CANCELLED","INSIGHTS_EXPIRED","LOCAL_CACHE_EXPIRED")

/** Account + note + content version isolation. Never include a different notebook as chat context. */
internal fun insightHistory(turns:List<NoteInsightTurn>,owner:String,note:LessonNote):String=JSONArray().apply {
    turns.filter {it.owner==owner&&it.noteId==note.id&&it.digest==noteContentDigest(note)&&it.answer!=null}
        .takeLast(4).forEach {turn->
            put(JSONObject().put("role","user").put("content",turn.question))
            put(JSONObject().put("role","assistant").put("content",turn.answer!!.text.take(6000)))
        }
}.toString()
internal fun insightCanApply(owner:String,visibleOwner:String?,persistedOwner:String?,digest:String,currentNote:LessonNote?)=
    mindMapOwnerMatches(owner,visibleOwner,persistedOwner)&&currentNote!=null&&noteContentDigest(currentNote)==digest

/** Device-only SQLite; source recordings and note text are never copied into this history store. */
class RecordingInsightsStore(context:Context,name:String="kejian_recording_insights_v211.db"):SQLiteOpenHelper(context.applicationContext,name,null,2){
    companion object {var revision by mutableIntStateOf(0);private set}
    override fun onCreate(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE turns(id TEXT PRIMARY KEY,owner TEXT NOT NULL,note_id TEXT NOT NULL,digest TEXT NOT NULL,request_id TEXT NOT NULL,question TEXT NOT NULL,history TEXT NOT NULL,status TEXT NOT NULL,answer TEXT,error_code TEXT NOT NULL,created_at INTEGER NOT NULL,web_search INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX note_turns ON turns(owner,note_id,digest,created_at)")
        db.execSQL("CREATE TABLE checklist(owner TEXT NOT NULL,note_id TEXT NOT NULL,item_key TEXT NOT NULL,PRIMARY KEY(owner,note_id,item_key))")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion<2)db.execSQL("ALTER TABLE turns ADD COLUMN web_search INTEGER NOT NULL DEFAULT 0")}
    fun deleteNote(noteId:String){
        writableDatabase.beginTransaction()
        try {writableDatabase.delete("turns","note_id=?",arrayOf(noteId));writableDatabase.delete("checklist","note_id=?",arrayOf(noteId));writableDatabase.setTransactionSuccessful()}
        finally {writableDatabase.endTransaction()}
        revision++
    }
    fun turns(owner:String,note:LessonNote,allVersions:Boolean=false):List<NoteInsightTurn> = readableDatabase.rawQuery(
        if(allVersions)"SELECT * FROM turns WHERE owner=? AND note_id=? ORDER BY created_at DESC,id DESC" else "SELECT * FROM turns WHERE owner=? AND note_id=? AND digest=? ORDER BY created_at DESC,id DESC LIMIT 100",if(allVersions)arrayOf(owner,note.id)else arrayOf(owner,note.id,noteContentDigest(note))).use {c->
        buildList {while(c.moveToNext()){
            fun s(key:String)=c.getString(c.getColumnIndexOrThrow(key))
            add(NoteInsightTurn(s("id"),s("owner"),s("note_id"),s("digest"),s("request_id"),s("question"),s("history"),s("status"),
                s("answer")?.let {NoteInsightAnswer.parse(JSONObject(it))},s("error_code"),c.getLong(c.getColumnIndexOrThrow("created_at")),c.getInt(c.getColumnIndexOrThrow("web_search"))==1))
        }}.reversed()
    }
    fun create(owner:String,note:LessonNote,question:String):NoteInsightTurn{
        require(owner.isNotBlank()&&note.summary.isNotBlank()&&question.isNotBlank()&&question.length<=2000)
        val existing=turns(owner,note)
        // A duplicate tap cannot create a second paid request. A pending or
        // uncertain task must be resolved explicitly through the same row.
        require(existing.none {it.status in setOf("pending","retry")})
        val turn=NoteInsightTurn(UUID.randomUUID().toString(),owner,note.id,noteContentDigest(note),UUID.randomUUID().toString(),question.trim(),insightHistory(existing,owner,note),webSearch=true)
        writableDatabase.execSQL("INSERT INTO turns VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(turn.id,turn.owner,turn.noteId,turn.digest,turn.requestId,turn.question,turn.history,turn.status,null,"",turn.createdAt,if(turn.webSearch)1 else 0))
        revision++;return turn
    }
    fun retry(owner:String,note:LessonNote,id:String,allowNewRequest:Boolean=true,confirmFreshRequest:Boolean=true):NoteInsightTurn{
        val turn=turns(owner,note).first {it.id==id}
        require(turn.answer==null)
        // Only a confirmed terminal server failure releases the old request ID.
        val fresh=insightNeedsFreshRequest(turn)
        if(fresh&&!confirmFreshRequest)throw NoteInsightRequestException(409,"INSIGHTS_REASK_REQUIRED","Explicitly ask again after the retry window")
        if(fresh&&!allowNewRequest)throw NoteInsightRequestException(402,"INSIGHTS_MEMBERSHIP","A new question needs an AI allowance")
        val request=if(fresh)UUID.randomUUID().toString()else turn.requestId
        val createdAt=if(fresh)System.currentTimeMillis()else turn.createdAt
        writableDatabase.execSQL("UPDATE turns SET request_id=?,created_at=?,status='pending',error_code='' WHERE id=? AND owner=?",arrayOf<Any?>(request,createdAt,id,owner))
        revision++;return turn.copy(requestId=request,createdAt=createdAt,status="pending",errorCode="")
    }
    fun complete(turn:NoteInsightTurn,answer:NoteInsightAnswer){
        writableDatabase.execSQL("UPDATE turns SET status='complete',answer=?,error_code='' WHERE id=? AND owner=? AND request_id=? AND digest=?",arrayOf(answer.toJson().toString(),turn.id,turn.owner,turn.requestId,turn.digest));revision++
    }
    fun failed(turn:NoteInsightTurn,code:String){
        val terminal=code in setOf("INSIGHTS_FAILED","INSIGHTS_CANCELLED","INSIGHTS_EXPIRED","LOCAL_CACHE_EXPIRED")
        writableDatabase.execSQL("UPDATE turns SET status=?,error_code=? WHERE id=? AND owner=? AND request_id=?",arrayOf(if(terminal)"failed"else"retry",code,turn.id,turn.owner,turn.requestId));revision++
    }
    fun checked(owner:String,noteId:String):Set<String> = readableDatabase.rawQuery("SELECT item_key FROM checklist WHERE owner=? AND note_id=?",arrayOf(owner,noteId)).use {c->buildSet {while(c.moveToNext())add(c.getString(0))}}
    fun check(owner:String,noteId:String,key:String,value:Boolean){
        if(value)writableDatabase.execSQL("INSERT OR IGNORE INTO checklist VALUES(?,?,?)",arrayOf(owner,noteId,key))
        else writableDatabase.execSQL("DELETE FROM checklist WHERE owner=? AND note_id=? AND item_key=?",arrayOf(owner,noteId,key))
        revision++
    }
}
