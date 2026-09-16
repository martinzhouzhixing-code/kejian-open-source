package app.kejian.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Intent
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** A recovery checkpoint for already requested work, not a permission to start new recordings. */
internal object AudioBackgroundRecovery {
    private const val ID=8615
    fun schedule(context:Context,delayMillis:Long=15*60_000L){
        val pending=BackgroundTaskResults.pendingAudio(context)
        if(pending?.paused!=false&&BackgroundTaskResults.pendingDocument(context)==null&&BackgroundTaskResults.documentCancels(context).isEmpty())return
        runCatching {context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(ID,ComponentName(context,AudioRecoveryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delayMillis)
                .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())}
    }
    fun cancel(context:Context){
        if(BackgroundTaskResults.pendingDocument(context)!=null||BackgroundTaskResults.documentCancels(context).isNotEmpty()||BackgroundTaskResults.pendingAudio(context)?.paused==false)return
        context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}

class AudioRecoveryJobService:JobService(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var running:Job?=null
    private var activeParameters:JobParameters?=null
    override fun onStartJob(params:JobParameters):Boolean {
        if(running?.isActive==true)return false
        val owner=currentBackgroundOwner(this)?:return false
        activeParameters=params
        running=scope.launch {
            try {
                // Direct, bounded JobScheduler execution: never launches a foreground service.
                withTimeout(8*60_000L){
                    retryDocumentCancellations(this@AudioRecoveryJobService,owner)
                    supervisorScope {
                        val pending=BackgroundTaskResults.pendingAudio(this@AudioRecoveryJobService)
                        if(pending!=null&&AudioRecoveryPolicy.canResume(pending.paused,pending.ownerId,owner))launch {
                            BackgroundWorkCoordinator.run("audio"){
                                BackgroundWorkRunner(this@AudioRecoveryJobService,owner,scheduled=true).runAudio(Intent())
                            }
                        }
                        val document=BackgroundTaskResults.pendingDocument(this@AudioRecoveryJobService)
                        if(document!=null&&document.optString("owner")==owner)launch {
                            val id=document.getString("requestId")
                            BackgroundWorkCoordinator.run("document"){
                                try {BackgroundWorkRunner(this@AudioRecoveryJobService,owner,scheduled=true)
                                    .runDocument(BackgroundTaskService.documentIntent(this@AudioRecoveryJobService,document),id)}
                                finally {BackgroundTaskRuntime.document(false,id)}
                            }
                        }
                    }
                }
            }catch(_:CancellationException){/* onStopJob/time budget preserves the existing checkpoint. */}
            catch(_:Exception){/* An authenticated recoverable checkpoint remains available. */}
            finally {
                if(!BackgroundWorkCoordinator.active("audio")&&!BackgroundWorkCoordinator.active("document"))BackgroundWorkNotifications.clearProgress(this@AudioRecoveryJobService)
                if(activeParameters===params){activeParameters=null;jobFinished(params,hasOwnedPending(owner))}
            }
        }
        return true
    }
    private fun hasOwnedPending(owner:String):Boolean {
        val audio=BackgroundTaskResults.pendingAudio(this)
        return (audio!=null&&AudioRecoveryPolicy.canResume(audio.paused,audio.ownerId,owner))||
            BackgroundTaskResults.pendingDocument(this)?.optString("owner")==owner||
            BackgroundTaskResults.documentCancels(this).any {it.optString("owner")==owner}
    }
    override fun onStopJob(params:JobParameters):Boolean {
        activeParameters=null;running?.cancel()
        val owner=currentBackgroundOwner(this)?:return false
        return hasOwnedPending(owner)
    }
    override fun onDestroy(){activeParameters=null;scope.cancel();super.onDestroy()}
}

internal suspend fun retryDocumentCancellations(context:Context,owner:String){
    for(pending in BackgroundTaskResults.documentCancels(context)){
        if(pending.optString("owner")!=owner)continue
        val id=pending.getString("requestId")
        if(System.currentTimeMillis()-pending.optLong("createdAt")>24L*60*60*1000){BackgroundTaskResults.clearDocumentCancel(context,id);continue}
        if(currentBackgroundOwner(context)!=owner)return
        try {
            withTimeout(15_000){CloudAccountClient(context).cancelAiRequest(id,owner)}
            if(currentBackgroundOwner(context)==owner)BackgroundTaskResults.clearDocumentCancel(context,id)
        }catch(cancelled:CancellationException){if(!currentCoroutineContext().isActive)throw cancelled}
        catch(_:Exception){/* Retain the owner-bound tombstone for the next scheduled attempt. */}
    }
}

internal fun Context.audioNetworkAvailable():Boolean=runCatching {
    val manager=getSystemService(ConnectivityManager::class.java)
    manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
}.getOrDefault(false)

internal suspend fun Context.awaitAudioNetwork(timeoutMillis:Long):Boolean {
    if(audioNetworkAvailable())return true
    val manager=getSystemService(ConnectivityManager::class.java)
    var registered:ConnectivityManager.NetworkCallback?=null
    return try {withTimeoutOrNull(timeoutMillis){
        suspendCancellableCoroutine<Boolean> {continuation->
            val delivered=java.util.concurrent.atomic.AtomicBoolean(false)
            val callback=object:ConnectivityManager.NetworkCallback(){
                override fun onAvailable(network:Network){if(delivered.compareAndSet(false,true)&&continuation.isActive)continuation.resume(true)}
            }
            registered=callback
            try {manager.registerDefaultNetworkCallback(callback)}catch(_:Exception){if(delivered.compareAndSet(false,true)&&continuation.isActive)continuation.resume(false)}
        }
    }?:false}finally {registered?.let {runCatching {manager.unregisterNetworkCallback(it)}}}
}
