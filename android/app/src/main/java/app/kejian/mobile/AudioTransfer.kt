package app.kejian.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class AudioTransferPausedException(cause:Throwable):IOException("Audio transfer paused",cause)
internal const val MAX_AUDIO_UPLOAD_BYTES=512L*1024*1024
internal fun isTransientAudioError(error:Throwable)=error is IOException||
    (error is CloudApiException&&(error.status in setOf(408,429)||error.status in 500..599))

/** Restarts the resumable protocol after its short per-request retry budget is exhausted. */
internal suspend fun <T> recoverAudioTransport(
    beforeAttempt:suspend ()->Unit={},onRetry:suspend (Int,Long)->Unit,
    nowMillis:()->Long={System.nanoTime()/1_000_000},block:suspend ()->T
):T {
    var attempt=0;var failedAt:Long?=null
    while(true){
        currentCoroutineContext().ensureActive();beforeAttempt()
        try {return block()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            if(!isTransientAudioError(error))throw error
            val now=nowMillis();val first=failedAt?:now.also {failedAt=it}
            val remaining=AudioRecoveryPolicy.ACTIVE_RETRY_WINDOW_MS-(now-first)
            if(remaining<=0)throw error
            onRetry(++attempt,remaining)
        }
    }
}

internal suspend fun <T> retryAudioRequest(
    onRetry:(Int)->Unit={},pause:suspend (Long)->Unit={delay(it)},block:suspend ()->T
):T{
    var attempt=0
    while(true){
        currentCoroutineContext().ensureActive()
        try {return block()}
        catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            if(!isTransientAudioError(error))throw error
            attempt++
            if(attempt>=3)throw AudioTransferPausedException(error)
            onRetry(attempt);pause(if(attempt==1)1_000 else 3_000)
        }
    }
}

internal suspend fun audioSha256(file:File,onProgress:suspend (Long,Long)->Unit={_,_->}):String=withContext(Dispatchers.IO){
    val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
    val size=file.length();var read=0L;var lastReported=0L
    onProgress(0,size)
    file.inputStream().use {input->while(true){
        currentCoroutineContext().ensureActive()
        val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count);read+=count
        if(read-lastReported>=8*1024*1024||read==size){onProgress(read,size);lastReported=read}
    }}
    digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
}

internal interface AudioUploadApi{
    suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String):JSONObject
    suspend fun status(requestId:String):JSONObject
    suspend fun put(requestId:String,offset:Long,bytes:ByteArray):JSONObject
    suspend fun complete(requestId:String):JSONObject
}

/** Start cautiously, grow only after fast acknowledgements, shrink on a slow/failed link. */
internal class AdaptiveAudioChunks {
    private var target=1024*1024
    fun limit(serverLimit:Int)=minOf(target,serverLimit.coerceIn(1,4*1024*1024))
    fun accepted(bytes:Int,elapsedMillis:Long){
        if(elapsedMillis>20_000)target=maxOf(256*1024,target/2)
        else if(elapsedMillis in 0..4_000&&bytes>=target)target=minOf(4*1024*1024,target*2)
    }
    fun interrupted(){target=maxOf(256*1024,target/2)}
}

/** The server's acknowledged offset is authoritative, including after a lost PUT response. */
internal class ResumableAudioTransfer(
    private val api:AudioUploadApi,
    private val pause:suspend (Long)->Unit={delay(it)},
    private val nowMillis:()->Long={System.nanoTime()/1_000_000}
){
    suspend fun upload(file:File,duration:Int,requestId:String,sha256:String,onProgress:(JSONObject)->Unit={}):JSONObject{
        UUID.fromString(requestId)
        require(file.isFile&&file.length() in 512..MAX_AUDIO_UPLOAD_BYTES){"Recording is empty or exceeds 512 MB"}
        require(duration in 10..5*3600){"Recording must be between 10 seconds and 5 hours"}
        require(sha256.matches(Regex("[a-f0-9]{64}"))){"Invalid audio fingerprint"}
        val size=file.length()
        var state=retryAudioRequest(pause=pause){api.begin(requestId,duration,size,sha256)}
        var failures=0
        val chunks=AdaptiveAudioChunks()
        fun progress(value:JSONObject){onProgress(value)}
        fun acknowledged(value:JSONObject):Long{
            val offset=value.optLong("uploadedBytes",-1)
            require(offset in 0..size){"Invalid upload offset"}
            require(value.optLong("sizeBytes",size)==size){"Upload file size changed"}
            return offset
        }
        while(true){
            currentCoroutineContext().ensureActive()
            progress(state)
            if(state.optString("jobId").isNotBlank())return state
            if(state.optString("status")=="failed")throw AudioJobFailedException(state.optString("error","The upload could not be completed."))
            val offset=acknowledged(state)
            if(offset==size)break
            val limit=chunks.limit(state.optInt("chunkBytes",1024*1024))
            val chunk=withContext(Dispatchers.IO){
                require(file.length()==size){"Local audio changed during upload"}
                ByteArray(minOf(limit.toLong(),size-offset).toInt()).also {bytes->
                    RandomAccessFile(file,"r").use {input->input.seek(offset);input.readFully(bytes)}
                }
            }
            try {
                val sentAt=nowMillis()
                val received=api.put(requestId,offset,chunk)
                if(received.optString("jobId").isBlank())require(acknowledged(received)>offset){"Upload made no progress"}
                chunks.accepted(chunk.size,(nowMillis()-sentAt).coerceAtLeast(0))
                state=received;failures=0
            }catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){
                val reconcile=isTransientAudioError(error)||(error is CloudApiException&&error.status==409)
                if(!reconcile)throw error
                chunks.interrupted()
                failures++
                if(failures>=3)throw AudioTransferPausedException(error)
                progress(JSONObject().put("status","reconnecting").put("uploadedBytes",offset).put("sizeBytes",size).put("requestId",requestId))
                pause(if(failures==1)1_000 else 3_000)
                state=retryAudioRequest(pause=pause){api.status(requestId)}
                if(state.optString("jobId").isNotBlank())return state
                if(acknowledged(state)>offset)failures=0
            }
        }
        repeat(3){attempt->
            try {return retryAudioRequest(onRetry={onProgress(JSONObject().put("status","reconnecting").put("uploadedBytes",size).put("sizeBytes",size))},pause=pause){api.complete(requestId)}.also {progress(it)}}
            catch(error:CloudApiException){
                if(error.status!=409)throw error
                val current=retryAudioRequest(pause=pause){api.status(requestId)}
                if(current.optString("jobId").isNotBlank())return current.also {progress(it)}
                if(attempt==2)throw AudioTransferPausedException(error)
                pause(1_000)
            }
        }
        error("Upload confirmation did not complete")
    }
}

internal suspend fun pollAudioJob(
    jobId:String,fetch:suspend (String)->JSONObject,onProgress:(JSONObject)->Unit={},
    pause:suspend (Long)->Unit={delay(it)}
):JSONObject{
    while(true){
        currentCoroutineContext().ensureActive()
        val state=retryAudioRequest(onRetry={onProgress(JSONObject().put("status","reconnecting").put("jobId",jobId))},pause=pause){fetch(jobId)}
        onProgress(state)
        when(state.optString("status")){
            "completed"->return state
            "failed","cancelled"->throw AudioJobFailedException(state.optString("error","Transcription could not be completed."))
            "queued","running"->Unit
            else->throw IOException("Unexpected transcription status")
        }
        pause(2_000)
    }
}
