package app.kejian.mobile

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

class AudioTransferTest {
    private val requestId=UUID.randomUUID().toString()
    private fun state(size:Long,offset:Long,status:String="uploading")=JSONObject()
        .put("requestId",requestId).put("sizeBytes",size).put("uploadedBytes",offset)
        .put("chunkBytes",1024*1024).put("status",status)
    private open inner class FakeApi(private val size:Long):AudioUploadApi {
        var offset=0L;var starts=0;var completions=0;var puts=0;var largestChunk=0
        val requestIds=mutableListOf<String>()
        override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String):JSONObject {
            starts++;requestIds+=requestId;return state(size,offset)
        }
        override suspend fun status(requestId:String)=state(size,offset)
        override suspend fun put(requestId:String,offset:Long,bytes:ByteArray):JSONObject {
            assertEquals(this.offset,offset);this.offset+=bytes.size;puts++;largestChunk=maxOf(largestChunk,bytes.size)
            requestIds+=requestId;return state(size,this.offset)
        }
        override suspend fun complete(requestId:String):JSONObject {completions++;return state(size,size,"queued").put("jobId","job-1")}
    }
    private fun recording(size:Long=2_500_000):File=File.createTempFile("audio-transfer-",".m4a").also {RandomAccessFile(it,"rw").use {out->out.setLength(size)}}
    private val hash="0".repeat(64)

    @Test fun lostChunkResponseResumesFromAcknowledgedBytesWithoutUploadingTwice()=runBlocking {
        val file=recording()
        try {
            val api=object:FakeApi(file.length()) {
                var first=true
                override suspend fun put(requestId:String,offset:Long,bytes:ByteArray):JSONObject {
                    val accepted=super.put(requestId,offset,bytes)
                    if(first){first=false;throw IOException("Socket aborted after server accepted the chunk")}
                    return accepted
                }
            }
            val progress=mutableListOf<String>()
            val result=ResumableAudioTransfer(api,pause={}).upload(file,3600,requestId,hash){progress+=it.optString("status")}
            assertEquals("job-1",result.getString("jobId"));assertEquals(3,api.puts);assertEquals(file.length(),api.offset)
            assertTrue(api.largestChunk<=1024*1024);assertTrue("reconnecting" in progress)
            assertEquals(setOf(requestId),api.requestIds.toSet())
        }finally{file.delete()}
    }

    @Test fun permanentForbiddenDoesNotRetryOrSubmit()=runBlocking {
        val file=recording(512)
        try {
            val api=object:FakeApi(file.length()) {
                override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String):JSONObject {starts++;throw CloudApiException(403,"No transcription entitlement")}
            }
            try {ResumableAudioTransfer(api,pause={}).upload(file,10,requestId,hash);fail("Expected forbidden")}
            catch(error:CloudApiException){assertEquals(403,error.status)}
            assertEquals(1,api.starts);assertEquals(0,api.puts);assertEquals(0,api.completions)
        }finally{file.delete()}
    }

    @Test fun networkFailuresPauseAndNextAttemptUsesSameIdentityAndOffset()=runBlocking {
        val file=recording()
        try {
            val api=object:FakeApi(file.length()) {
                var offline=true
                override suspend fun put(requestId:String,offset:Long,bytes:ByteArray):JSONObject {
                    if(offset>0&&offline)throw IOException("Offline")
                    return super.put(requestId,offset,bytes)
                }
            }
            try {ResumableAudioTransfer(api,pause={}).upload(file,3600,requestId,hash);fail("Expected pause")}
            catch(_:AudioTransferPausedException){}
            assertEquals(1024*1024L,api.offset)
            api.offline=false
            val result=ResumableAudioTransfer(api,pause={}).upload(file,3600,requestId,hash)
            assertEquals("job-1",result.getString("jobId"));assertEquals(3,api.puts)
            assertEquals(2,api.starts);assertEquals(setOf(requestId),api.requestIds.toSet());assertEquals(1,api.completions)
        }finally{file.delete()}
    }

    @Test fun completeConflictReconcilesAcceptedJobWithoutResubmitting()=runBlocking {
        val file=recording(512)
        try {
            val api=object:FakeApi(file.length()) {
                override suspend fun complete(requestId:String):JSONObject {completions++;throw CloudApiException(409,"Submission acknowledgement pending")}
                override suspend fun status(requestId:String)=state(file.length(),file.length(),"queued").put("jobId","already-created")
            }
            val result=ResumableAudioTransfer(api,pause={}).upload(file,10,requestId,hash)
            assertEquals("already-created",result.getString("jobId"));assertEquals(1,api.completions)
        }finally{file.delete()}
    }

    @Test fun existingJobAfterLostCompletionResponseSkipsBodyAndComplete()=runBlocking {
        val file=recording(512)
        try {
            val api=object:FakeApi(file.length()) {
                override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String)=state(size,size,"running").put("jobId","same-job")
            }
            val result=ResumableAudioTransfer(api,pause={}).upload(file,10,requestId,hash)
            assertEquals("same-job",result.getString("jobId"));assertEquals(0,api.puts);assertEquals(0,api.completions)
        }finally{file.delete()}
    }

    @Test fun ninetySixMegabyteRecordingIsAcceptedAndUsesBoundedChunks()=runBlocking {
        val file=recording(96L*1024*1024+512)
        try {
            val fingerprint=audioSha256(file)
            val api=FakeApi(file.length())
            ResumableAudioTransfer(api,pause={}).upload(file,7200,requestId,fingerprint)
            assertEquals(97,api.puts);assertEquals(96L*1024*1024+512,file.length())
            assertEquals(file.length(),api.offset);assertEquals(1024*1024,api.largestChunk)
            assertEquals(fingerprint,audioSha256(file))
        }finally{file.delete()}
    }

    @Test fun invalidServerOffsetStopsWithoutCorruptingAudio()=runBlocking {
        val file=recording(512)
        try {
            val api=object:FakeApi(file.length()) {
                override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String)=state(size,size+1)
            }
            try {ResumableAudioTransfer(api,pause={}).upload(file,10,requestId,hash);fail("Expected bad offset")}
            catch(_:IllegalArgumentException){}
            assertEquals(0,api.puts);assertEquals(512L,file.length())
        }finally{file.delete()}
    }

    @Test fun pollingSurvivesTransientFailureAndNeverResubmitsAudio()=runBlocking {
        var calls=0;val status=mutableListOf<String>()
        val result=pollAudioJob("job-1",fetch={
            calls++
            when(calls){1->throw IOException("Connection reset");2->JSONObject().put("status","queued");else->JSONObject().put("status","completed").put("noteLine","note")}
        },onProgress={status+=it.getString("status")},pause={})
        assertEquals(3,calls);assertEquals("note",result.getString("noteLine"));assertTrue("reconnecting" in status)
    }

    @Test fun retryPropagatesCancellationInsteadOfMakingMoreCalls()=runBlocking {
        var calls=0
        try {retryAudioRequest(pause={}){calls++;throw CancellationException("Stopped by user")};fail("Expected cancellation")}
        catch(_:CancellationException){}
        assertEquals(1,calls)
    }

    @Test fun htmlGatewayErrorIsRetriedButClientValidationIsNot()=runBlocking {
        var calls=0
        val result=retryAudioRequest(pause={}){calls++;if(calls<3)throw CloudApiException(503,"Service unavailable");"ready"}
        assertEquals("ready",result);assertEquals(3,calls)
        calls=0
        try {retryAudioRequest(pause={}){calls++;throw CloudApiException(422,"Invalid audio metadata")};fail("Expected validation failure")}
        catch(error:CloudApiException){assertEquals(422,error.status)}
        assertEquals(1,calls)
    }

    @Test fun fingerprintIncludesAllFileBytes()=runBlocking {
        val file=recording(130_000)
        try {
            RandomAccessFile(file,"rw").use {out->out.seek(129_999);out.write(42)}
            val expected=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
            assertEquals(expected,audioSha256(file))
        }finally{file.delete()}
    }
}
