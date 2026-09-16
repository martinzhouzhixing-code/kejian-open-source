package app.kejian.mobile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Device-only metadata. Audio is never embedded in notes, backups, or map requests. */
object RecordingLibrary {
    var revision by mutableIntStateOf(0); private set
    private val lock=Any()
    private fun prefs(context:Context)=context.getSharedPreferences("kejian_recording_library_v21",Context.MODE_PRIVATE)
    private fun pathKey(path:String)=sha256Text(path)
    fun capture(context:Context,file:File,seconds:Int):LessonNote=synchronized(lock){
        require(file.isFile&&seconds>=10)
        val p=prefs(context);val key="inbox_${pathKey(file.absolutePath)}"
        p.getString(key,null)?.let {NoteExchange.parseLine(it).getOrNull()}?.let {return@synchronized it}
        val created=Instant.ofEpochMilli(file.lastModified().takeIf {it>0}?:System.currentTimeMillis())
        val stamp=created.atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        val note=LessonNote(title=(if(AppLanguage.english)"Recording · "else"录音 · ")+stamp,durationSeconds=seconds,audioPath=file.absolutePath,createdAt=created)
        check(p.edit().putString(key,NoteExchange.encodeNote(note)).commit());revision++
        note
    }
    fun inbox(context:Context):List<LessonNote> = synchronized(lock){
        prefs(context).all.filterKeys {it.startsWith("inbox_")}.values.mapNotNull {(it as? String)?.let {line->NoteExchange.parseLine(line).getOrNull()}}
    }
    fun acknowledge(context:Context,path:String)=synchronized(lock){prefs(context).edit().remove("inbox_${pathKey(path)}").commit()}
    data class FileState(val stage:String="local",val message:String="",val progress:Int=0,val updatedAt:Long=System.currentTimeMillis()) {
        val busy get()=stage in setOf("preparing","uploading","queued","transcribing","summarizing","reconnecting")
    }
    fun fileState(context:Context,path:String?):FileState {
        if(path==null)return FileState()
        return runCatching {val o=JSONObject(prefs(context).getString("state_${pathKey(path)}",null)?:return FileState())
            FileState(o.optString("stage","local"),o.optString("message"),o.optInt("progress"),o.optLong("updatedAt"))}.getOrDefault(FileState())
    }
    fun setState(context:Context,path:String,stage:String,message:String="",progress:Int=0)=synchronized(lock){
        prefs(context).edit().putString("state_${pathKey(path)}",JSONObject().put("stage",stage).put("message",message.take(500))
            .put("progress",progress.coerceIn(0,100)).put("updatedAt",System.currentTimeMillis()).toString()).commit().also {revision++}
    }
    fun mapRequestId(context:Context,note:LessonNote,ownerId:String):String=synchronized(lock){
        val p=prefs(context);val key="map_request_${sha256Text(ownerId)}_${note.id}_${noteContentDigest(note)}"
        p.getString(key,null)?:UUID.randomUUID().toString().also {check(p.edit().putString(key,it).commit())}
    }
    fun clearMapRequest(context:Context,note:LessonNote,ownerId:String)=synchronized(lock){prefs(context).edit().remove("map_request_${sha256Text(ownerId)}_${note.id}_${noteContentDigest(note)}").commit()}
    fun saveMap(context:Context,note:LessonNote,map:LessonMindMap)=synchronized(lock){
        require(map.contentDigest==noteContentDigest(note))
        check(prefs(context).edit().putString("map_${note.id}",map.toJson().toString()).commit());revision++
    }
    fun forget(context:Context,note:LessonNote)=synchronized(lock){
        val p=prefs(context);val e=p.edit().remove("map_${note.id}")
        p.all.keys.filter {it.startsWith("map_request_")&&it.contains("_${note.id}_")}.forEach {e.remove(it)}
        note.audioPath?.let {e.remove("inbox_${pathKey(it)}").remove("state_${pathKey(it)}")}
        check(e.commit());revision++
    }
    fun loadMap(context:Context,note:LessonNote):LessonMindMap? = runCatching {
        val json=prefs(context).getString("map_${note.id}",null)?:return null
        LessonMindMap.parse(JSONObject(json),noteContentDigest(note))
    }.getOrNull()
}

internal fun sha256Text(text:String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
fun noteContentDigest(note:LessonNote):String=sha256Text(JSONArray().put(note.id).put(note.title).put(note.summary)
    .put(JSONArray(note.keyPoints)).put(JSONArray(note.actionItems)).put(note.transcript).toString())

/** Retain the library identity and user-selected association when a server note arrives. */
fun mergeRecordingResult(existing:LessonNote?,result:LessonNote,path:String):LessonNote = result.copy(
    id=existing?.id?:result.id,courseId=existing?.courseId,occurrenceDate=existing?.occurrenceDate,
    createdAt=existing?.createdAt?:result.createdAt,audioPath=path,
    title=existing?.title?.takeUnless {it in setOf("新录音","New recording","课堂录音","Recording")||it.matches(Regex("(?:录音|Recording) · [0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"))}?:result.title)

data class MindMapNode(val id:String,val parentId:String?,val label:String,val details:String="")
data class LessonMindMap(val title:String,val nodes:List<MindMapNode>,val contentDigest:String) {
    fun toJson()=JSONObject().put("version",1).put("title",title).put("contentDigest",contentDigest).put("nodes",JSONArray().apply {
        nodes.forEach {put(JSONObject().put("id",it.id).put("parentId",it.parentId?:JSONObject.NULL).put("label",it.label).put("details",it.details))}
    })
    companion object {
        fun parse(json:JSONObject,expectedDigest:String):LessonMindMap {
            require(json.getInt("version")==1)
            val digest=json.optString("contentDigest",expectedDigest);require(digest==expectedDigest)
            val title=json.getString("title");require(title.isNotBlank()&&title.length<=160)
            val a=json.getJSONArray("nodes");require(a.length() in 1..200)
            val nodes=(0 until a.length()).map {i->val n=a.getJSONObject(i)
                MindMapNode(n.getString("id"),if(n.isNull("parentId"))null else n.getString("parentId"),n.getString("label"),n.optString("details"))}
            require(nodes.map {it.id}.toSet().size==nodes.size&&nodes.count {it.parentId==null}==1)
            val byId=nodes.associateBy {it.id}
            nodes.forEach {node->
                require(node.id.isNotBlank()&&node.id.length<=80&&node.label.isNotBlank()&&node.label.length<=240&&node.details.length<=12000)
                val seen=mutableSetOf<String>();var current:MindMapNode?=node
                while(current!=null){require(seen.add(current.id)&&seen.size<=7);current=current.parentId?.let {byId[it]?:error("Unknown parent")}}
            }
            return LessonMindMap(title,nodes,digest)
        }
    }
}
