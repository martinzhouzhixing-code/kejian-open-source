package app.kejian.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class CloudHttpResponse(val code:Int,val text:String)

/** Small cancellable HTTP boundary, with an injectable connection factory for failure-path tests. */
internal class CloudHttpTransport(
    private val connectionFactory:(URL)->HttpURLConnection={it.openConnection() as HttpURLConnection}
){
    suspend fun execute(
        method:String,url:String,headers:Map<String,String> = emptyMap(),body:ByteArray?=null,
        contentType:String="application/json; charset=utf-8",readTimeoutMs:Int=20_000,
        responseLimit:Int=9_000_000
    ):CloudHttpResponse=suspendCancellableCoroutine { continuation->
        val active=AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation {active.get()?.disconnect()}
        Dispatchers.IO.dispatch(EmptyCoroutineContext,Runnable{
            var connection:HttpURLConnection?=null
            try {
                if(!continuation.isActive)return@Runnable
                val opened=connectionFactory(URL(url));connection=opened;active.set(opened)
                if(!continuation.isActive)throw CancellationException("HTTP request cancelled")
                opened.requestMethod=method
                opened.connectTimeout=20_000;opened.readTimeout=readTimeoutMs
                opened.useCaches=false;opened.instanceFollowRedirects=false
                headers.forEach {(key,value)->opened.setRequestProperty(key,value)}
                if(body!=null){
                    opened.doOutput=true
                    opened.setRequestProperty("Content-Type",contentType)
                    opened.setFixedLengthStreamingMode(body.size)
                    opened.outputStream.use {out->
                        var offset=0
                        while(offset<body.size){
                            if(!continuation.isActive)throw CancellationException("HTTP request cancelled")
                            val length=minOf(64*1024,body.size-offset)
                            out.write(body,offset,length);offset+=length
                        }
                    }
                }
                if(!continuation.isActive)throw CancellationException("HTTP request cancelled")
                val status=opened.responseCode
                val stream=if(status in 200..299)opened.inputStream else opened.errorStream
                val text=stream?.use {input->
                    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(true){
                        if(!continuation.isActive)throw CancellationException("HTTP request cancelled")
                        val count=input.read(buffer);if(count<0)break
                        require(output.size()+count<=responseLimit){"Server response is too large"}
                        output.write(buffer,0,count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
                if(continuation.isActive)continuation.resume(CloudHttpResponse(status,text))
            }catch(error:Throwable){
                if(continuation.isActive)continuation.resumeWithException(error)
            }finally {active.set(null);connection?.disconnect()}
        })
    }
}
