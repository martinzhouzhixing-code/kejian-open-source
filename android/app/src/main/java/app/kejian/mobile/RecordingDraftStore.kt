package app.kejian.mobile

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Local-only drafts survive process death and an in-place app update. No audio is moved or deleted here. */
internal object RecordingDraftStore {
    data class Draft(val file:File,val seconds:Int)
    private const val PREFS="kejian_recording_draft"
    private fun prefs(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun save(context:Context,file:File,seconds:Int){
        prefs(context).edit().putString("path",file.absolutePath).putInt("seconds",seconds).commit()
    }
    fun clear(context:Context,path:String?=null){
        val preferences=prefs(context)
        if(path==null||preferences.getString("path",null)==path)preferences.edit().clear().commit()
    }
    suspend fun recover(context:Context,savedAudioPaths:Set<String>):Draft?=withContext(Dispatchers.IO){
        val music=context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?:return@withContext null
        val directory=File(music,"Recordings").canonicalFile
        val preferences=prefs(context)
        fun duration(file:File):Int{
            val reader=MediaMetadataRetriever()
            return try {
                reader.setDataSource(file.absolutePath)
                if(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")0
                else ((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0)/1000).toInt()
            }catch(_:Exception){0}finally{runCatching {reader.release()}}
        }
        findRecoverableRecording(directory,preferences.getString("path",null),savedAudioPaths,::duration)?.also {save(context,it.file,it.seconds)}
    }
}

/** Keeps migration discovery testable without decoding arbitrary user audio during unit tests. */
internal suspend fun findRecoverableRecording(
    directory:File,rememberedPath:String?,savedAudioPaths:Set<String>,inspectDuration:(File)->Int
):RecordingDraftStore.Draft? {
    val root=directory.canonicalFile
    if(!root.isDirectory)return null
    val saved=savedAudioPaths.mapNotNull {runCatching {File(it).canonicalPath}.getOrNull()}.toSet()
    fun candidate(file:File):Boolean=runCatching {
        file.canonicalFile.parentFile==root&&file.name.startsWith("kejian-")&&file.extension.equals("m4a",true)&&
            file.isFile&&file.length()>=512&&file.canonicalPath !in saved
    }.getOrDefault(false)
    val remembered=rememberedPath?.let(::File)?.takeIf(::candidate)
    // Prefer an explicitly saved draft; older versions have no marker, so inspect orphan recordings too.
    val ordered=listOfNotNull(remembered)+root.listFiles().orEmpty().asSequence().filter(::candidate)
        .filter {it.absolutePath!=remembered?.absolutePath}.sortedByDescending {it.lastModified()}.toList()
    for(file in ordered){
        currentCoroutineContext().ensureActive()
        val seconds=inspectDuration(file)
        if(seconds>=10)return RecordingDraftStore.Draft(file,seconds)
    }
    return null
}
