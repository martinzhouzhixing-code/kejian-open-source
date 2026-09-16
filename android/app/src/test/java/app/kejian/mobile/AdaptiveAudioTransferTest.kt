package app.kejian.mobile

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

class AdaptiveAudioTransferTest {
    @Test fun adaptiveUploadGrowsAndRecoversLostResponseWithoutDuplicateBytes()=runBlocking {
        val mib=1024*1024
        val file=File.createTempFile("adaptive-audio-",".m4a")
        RandomAccessFile(file,"rw").use {it.setLength(12L*mib)}
        val request=UUID.randomUUID().toString()
        var remoteOffset=0L;var now=0L;var completions=0;var disconnected=false
        val accepted=mutableListOf<Int>()
        fun state()=JSONObject().put("requestId",request).put("sizeBytes",file.length()).put("uploadedBytes",remoteOffset).put("chunkBytes",4*mib).put("status","uploading")
        val api=object:AudioUploadApi {
            override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String)=state()
            override suspend fun status(requestId:String)=state()
            override suspend fun put(requestId:String,offset:Long,bytes:ByteArray):JSONObject {
                assertEquals(request,requestId);assertEquals(remoteOffset,offset)
                accepted+=bytes.size;remoteOffset+=bytes.size
                // Model the server persisting the chunk before its ACK is lost.
                now+=1000
                return state().also {
                    if(bytes.size==4*mib&&!disconnected){disconnected=true;throw IOException("ACK lost")}
                }
            }
            override suspend fun complete(requestId:String):JSONObject {completions++;return state().put("jobId","one-job").put("status","queued")}
        }
        try {
            val result=ResumableAudioTransfer(api,pause={},nowMillis={now}).upload(file,7200,request,"0".repeat(64))
            assertEquals("one-job",result.getString("jobId"));assertEquals(1,completions)
            assertEquals(12L*mib,remoteOffset);assertEquals(listOf(mib,2*mib,4*mib,2*mib,3*mib),accepted)
            assertTrue(accepted.all {it<=4*mib})
        }finally {file.delete()}
    }

    @Test fun slowOrInterruptedLinkShrinksAndNeverExceedsServerLimit(){
        val chunks=AdaptiveAudioChunks();val mib=1024*1024
        chunks.accepted(mib,1_000);chunks.accepted(2*mib,1_000)
        assertEquals(4*mib,chunks.limit(8*mib));assertEquals(mib,chunks.limit(mib))
        chunks.accepted(4*mib,25_000);assertEquals(2*mib,chunks.limit(4*mib))
        repeat(5){chunks.interrupted()}
        assertEquals(256*1024,chunks.limit(4*mib));assertEquals(128*1024,chunks.limit(128*1024))
    }
}
