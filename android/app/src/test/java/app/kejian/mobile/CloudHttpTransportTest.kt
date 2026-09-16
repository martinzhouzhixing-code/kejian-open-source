package app.kejian.mobile

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CloudHttpTransportTest {
    private open class FakeConnection:HttpURLConnection(URL("https://example.invalid/test")) {
        val disconnected=AtomicBoolean(false)
        var response="{\"ok\":true}";var httpStatus=200
        override fun connect(){}
        override fun usingProxy()=false
        override fun disconnect(){disconnected.set(true)}
        override fun getResponseCode()=httpStatus
        override fun getInputStream()=ByteArrayInputStream(response.toByteArray())
        override fun getErrorStream()=ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream():OutputStream=object:OutputStream(){override fun write(value:Int){}}
    }

    @Test fun abortedBodyWriteAlwaysDisconnects()=runBlocking {
        val connection=object:FakeConnection(){
            override fun getOutputStream():OutputStream=object:OutputStream(){override fun write(value:Int){throw IOException("Software caused connection abort")}}
        }
        try {CloudHttpTransport{connection}.execute("POST","https://example.invalid/upload",body=ByteArray(512));fail("Expected abort")}
        catch(_:IOException){}
        // The continuation can resume before the I/O worker enters finally.
        withTimeout(1000){while(!connection.disconnected.get())delay(5)}
        assertTrue(connection.disconnected.get())
    }

    @Test fun stopDisconnectsBlockedUploadAndReturnsWithoutWaitingForTimeout()=runBlocking {
        val writeStarted=CountDownLatch(1);val released=CountDownLatch(1)
        val connection=object:FakeConnection(){
            override fun getOutputStream():OutputStream=object:OutputStream(){
                override fun write(value:Int){writeStarted.countDown();released.await(5,TimeUnit.SECONDS);throw IOException("Disconnected")}
            }
            override fun disconnect(){super.disconnect();released.countDown()}
        }
        val request=launch(Dispatchers.Default){CloudHttpTransport{connection}.execute("POST","https://example.invalid/upload",body=ByteArray(1_048_576))}
        assertTrue(writeStarted.await(2,TimeUnit.SECONDS))
        withTimeout(1000){request.cancelAndJoin()}
        assertTrue(connection.disconnected.get());assertTrue(request.isCancelled)
    }

    @Test fun stopDuringConnectionCreationStillClosesCreatedConnection()=runBlocking {
        val factoryStarted=CountDownLatch(1);val factoryReleased=CountDownLatch(1)
        val connection=FakeConnection()
        val transport=CloudHttpTransport{factoryStarted.countDown();factoryReleased.await(5,TimeUnit.SECONDS);connection}
        val request=launch(Dispatchers.Default){transport.execute("GET","https://example.invalid/status")}
        assertTrue(factoryStarted.await(2,TimeUnit.SECONDS))
        request.cancel();factoryReleased.countDown();withTimeout(1000){request.join();while(!connection.disconnected.get())delay(5)}
        assertTrue(connection.disconnected.get())
    }

    @Test fun nonJsonGatewayResponsePreservesStatusForCaller()=runBlocking {
        val connection=FakeConnection().apply {httpStatus=503;response="<html>Service unavailable</html>"}
        val result=CloudHttpTransport{connection}.execute("POST","https://example.invalid/recognize")
        assertEquals(503,result.code);assertTrue(result.text.startsWith("<html>"))
    }

    @Test fun responseLimitPreventsUnboundedDownloadAndClosesSocket()=runBlocking {
        val connection=FakeConnection().apply {response="a".repeat(8192)}
        try {CloudHttpTransport{connection}.execute("GET","https://example.invalid/status",responseLimit=512);fail("Expected response limit")}
        catch(_:IllegalArgumentException){}
        withTimeout(1000){while(!connection.disconnected.get())delay(5)}
        assertTrue(connection.disconnected.get())
    }
}
