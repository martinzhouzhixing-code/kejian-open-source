package app.kejian.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal fun currentBackgroundOwner(context:Context)=CloudAccountClient(context).currentSession?.userId

/** Shared by the user-started foreground service and network-constrained JobScheduler work. */
internal class BackgroundWorkRunner(
    private val context:Context,private val expectedOwner:String,private val scheduled:Boolean=false,
    private val audioApi:AudioUploadApi?=null,
    private val fetchAudioJob:(suspend (String)->JSONObject)?=null,
    private val ownerProvider:()->String?={currentBackgroundOwner(context)}
){
    private var workWakeLock:PowerManager.WakeLock?=null
    private var lastWakeRenewed=0L
    private var lastProgressNotice=0L
    private var lastProgressStage=""
    private fun checkpoint(delayMillis:Long=15*60_000L){if(!scheduled)AudioBackgroundRecovery.schedule(context,delayMillis)}
    private fun holdCpu(){
        // JobScheduler holds a wake lock for onStartJob..jobFinished/onStopJob.
        if(scheduled)return
        val now=SystemClock.elapsedRealtime()
        if(workWakeLock?.isHeld==true&&now-lastWakeRenewed<5*60_000L)return
        releaseCpu()
        workWakeLock=context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Kejian:background-transfer").apply {
            setReferenceCounted(false);acquire(10*60_000L)
        };lastWakeRenewed=now;checkpoint()
    }
    private fun releaseCpu(){workWakeLock?.let {if(it.isHeld)runCatching {it.release()}};workWakeLock=null}
    private fun showActiveNotification()=BackgroundWorkNotifications.progress(context)
    private fun finish(kind:String,title:String,body:String,error:Boolean=false,requestId:String?=null){
        BackgroundWorkNotifications.finished(context,kind,title,body)
        if(error)BackgroundTaskResults.saveError(context,kind,body,requestId,expectedOwner)
        BackgroundTaskRuntime.done(body)
    }
    suspend fun runAudio(intent:Intent){
        val restored=BackgroundTaskResults.pendingAudio(context)?:return
        if(restored.paused)return
        val requestedPath=intent.getStringExtra("path")
        var pending=restored
        val file=File(pending.path);val client=CloudAccountClient(context);val started=System.currentTimeMillis()
        var phase="preflight"
        var verifiedFingerprint:String?=null
        BackgroundTaskRuntime.audio(true,pending.estimate)
        try {
            if(restored!=null&&requestedPath!=null&&requestedPath!=restored.path)throw CloudApiException(409,taskCopy("请先继续现有录音任务","Continue the existing recording task first."))
            val owner=expectedOwner
            if(ownerProvider()!=owner)throw CloudApiException(401,"Recording account changed")
            if(pending.ownerId!=null&&pending.ownerId!=owner)throw CloudApiException(401,taskCopy("请登录提交这段录音的账号","Sign in to the account that submitted this recording."))
            pending=pending.copy(ownerId=owner,paused=false);BackgroundTaskResults.savePendingAudio(context,pending)
            checkpoint()
            recoverAudioConnection {
            var jobId=pending.jobId
            if(jobId==null){
                phase="fingerprint";require(file.isFile){"Local recording is unavailable"}
                require(file.length() in 512..MAX_AUDIO_UPLOAD_BYTES){"Recording is empty or exceeds 512 MB"}
                require(pending.duration in 10..5*3600){"Recording must be between 10 seconds and 5 hours"}
                BackgroundTaskRuntime.audioUploadPreparing(file.length())
                val fingerprint=verifiedFingerprint?:audioSha256(file){read,size->withContext(Dispatchers.Main.immediate) {
                    BackgroundTaskRuntime.audioPreparation(read,size)
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastProgressNotice>=1_000||read==size){lastProgressNotice=now;showActiveNotification()}
                }}.also {verifiedFingerprint=it}
                require(pending.sha256==null||pending.sha256==fingerprint){"Local recording changed"}
                pending=pending.copy(sha256=fingerprint,sizeBytes=file.length());BackgroundTaskResults.savePendingAudio(context,pending)
                phase="upload"
                val queued=if(audioApi!=null)ResumableAudioTransfer(audioApi).upload(file,pending.duration,pending.requestId,fingerprint){showAudioProgress(it)}
                    else client.enqueueAudio(file,pending.duration,pending.requestId,fingerprint,owner,pending.summaryLanguage,pending.summaryRequirements){showAudioProgress(it)}
                jobId=queued.getString("jobId")
                pending=pending.copy(jobId=jobId);BackgroundTaskResults.savePendingAudio(context,pending);showAudioProgress(queued)
            }
            phase="poll"
            val root=if(fetchAudioJob!=null)pollAudioJob(jobId,fetchAudioJob,{showAudioProgress(it)})
                else client.waitForAudioJob(jobId,owner){showAudioProgress(it)}
            currentCoroutineContext().ensureActive()
            if(ownerProvider()!=owner)throw CloudApiException(401,"Recording account changed")
            if(!BackgroundTaskResults.saveAudio(context,root.getString("noteLine"),file.absolutePath,owner))throw IOException("Could not store completed recording notes")
            BackgroundTaskResults.clearPendingAudio(context);if(!scheduled)AudioBackgroundRecovery.cancel(context)
            RecordingLibrary.setState(context,file.absolutePath,"completed")
            val message=taskCopy("转写与总结已完成，打开对应录音即可查看","Transcription and notes are ready. Open the recording to read them.")
            BackgroundTaskRuntime.audioFinished(message,AudioWorkStage.COMPLETED)
            finish("audio",taskCopy("课堂总结已完成","Summary ready"),message)
            }
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            val terminal=error is AudioJobFailedException||error is IllegalArgumentException||(error is CloudApiException&&error.status in setOf(404,410,422))
            // Keep the local file reachable even after a terminal server response or process restart.
            // A failed/expired server job needs a fresh attempt, while connection failures retain identity.
            val retryPending=if(terminal)pending.copy(jobId=null,requestId=java.util.UUID.randomUUID().toString(),sha256=null,paused=true)
                else pending.copy(paused=!isTransientAudioError(error))
            BackgroundTaskResults.savePendingAudio(context,retryPending)
            if(!retryPending.paused)checkpoint(60_000) else if(!scheduled)AudioBackgroundRecovery.cancel(context)
            val message=when {
                error is CloudApiException&&error.status==401->taskCopy("请重新登录提交录音的账号，再点击继续任务。音频仍保留在本机。","Sign in to the account that submitted this recording, then continue. Your audio is still on this device.")
                pending.duration !in 10..5*3600->taskCopy("单次录音需为 10 秒至 5 小时；原始音频仍保留在本机，可先导出。","Each recording must be 10 seconds to 5 hours. Your original audio is still on this device and can be exported.")
                file.isFile&&file.length()>MAX_AUDIO_UPLOAD_BYTES->taskCopy("这段音频超过单份 512 MB 上限。原始音频仍在本机，可先导出；512 MB 内会自动分段上传。","This recording exceeds the 512 MB file limit. The original remains on this device and can be exported. Files within 512 MB upload automatically in chunks.")
                isTransientAudioError(error)->taskCopy("网络暂时不可用，进度已保存，等待系统允许恢复；回到课间也会自动续传。无需重新录音。","Network unavailable. Progress is saved for background recovery; opening Kejian also resumes it automatically.")
                error is CloudApiException->if(AppLanguage.english)when(error.status){
                    403->"Check your transcription entitlement and remaining minutes, then continue. Your audio is safe on this device."
                    409->"The recording task could not continue yet. Try again shortly. Your audio and upload identity are saved."
                    404,410->"The temporary upload expired. Continue to upload the original audio again."
                    422->"The recording could not be accepted. Check its format and duration. Your original audio is still on this device."
                    else->"The transcription service is unavailable (HTTP ${error.status}). Your audio is still on this device."
                }else localized(error.message?:"服务暂时不可用，请稍后继续")
                error is AudioJobFailedException->if(AppLanguage.english)"Transcription could not finish. The original audio is still on this device; you can try again."
                    else localized(error.message?:"转写未完成，原始音频仍在本机")
                else->taskCopy("暂时无法处理这段录音，原始音频仍保留在本机。","This recording could not be processed. The original audio is still on this device.")
            }
            val autoRetry=!retryPending.paused
            RecordingLibrary.setState(context,file.absolutePath,if(terminal)"failed"else if(autoRetry)"reconnecting"else"paused",message,BackgroundTaskRuntime.audioPercent())
            BackgroundTaskRuntime.audioFinished(message,if(terminal)AudioWorkStage.FAILED else if(autoRetry)AudioWorkStage.RECONNECTING else AudioWorkStage.PAUSED)
            finish("audio",taskCopy(if(autoRetry)"等待恢复录音任务"else"录音任务未完成",if(autoRetry)"Waiting to resume recording task"else"Recording task incomplete"),message,true)
            try {withTimeout(5_000){client.uploadClientError(pending.jobId,"audio_$phase","Audio transfer error",JSONObject()
                .put("requestId",pending.requestId).put("phase",phase).put("durationSeconds",pending.duration).put("sizeBytes",pending.sizeBytes)
                .put("uploadedBytes",BackgroundTaskRuntime.audioUploadedBytes).put("elapsedMs",System.currentTimeMillis()-started)
                .put("exception",error.javaClass.simpleName).put("cause",error.cause?.javaClass?.simpleName)
                .put("httpStatus",(error as? CloudApiException)?.status))}}catch(cancelled:CancellationException){if(!currentCoroutineContext().isActive)throw cancelled}catch(_:Exception){}
        }finally {BackgroundTaskRuntime.audio(false);releaseCpu()}
    }
    private suspend fun <T> recoverAudioConnection(block:suspend ()->T):T {
        return recoverAudioTransport(beforeAttempt={
            val pending=BackgroundTaskResults.pendingAudio(context)?:throw CancellationException("Audio work removed")
            if(pending.paused)throw CancellationException("Audio transfer paused by user")
            if(ownerProvider()!=pending.ownerId)throw CloudApiException(401,"Recording account changed")
            holdCpu()
        },onRetry={attempt,remaining->
            showAudioProgress(JSONObject().put("status","reconnecting"))
            releaseCpu()
            if(!context.audioNetworkAvailable())context.awaitAudioNetwork(minOf(remaining,60_000L))
            else delay(minOf(remaining,AudioRecoveryPolicy.retryDelayMillis(attempt)))
        },nowMillis={SystemClock.elapsedRealtime()},block=block)
    }
    private fun showAudioProgress(root:JSONObject){
        holdCpu()
        BackgroundTaskRuntime.audioProgress(root)
        val now=SystemClock.elapsedRealtime();val stage=BackgroundTaskRuntime.audioStage.name
        if(stage==lastProgressStage&&now-lastProgressNotice<1_000)return
        lastProgressNotice=now;lastProgressStage=stage
        BackgroundTaskResults.pendingAudio(context)?.path?.let {RecordingLibrary.setState(context,it,BackgroundTaskRuntime.audioStage.name.lowercase(),BackgroundTaskRuntime.audioStatus.orEmpty(),BackgroundTaskRuntime.audioPercent())}
        showActiveNotification()
    }
    suspend fun runDocument(intent:Intent,requestId:String){
        val pending=BackgroundTaskResults.pendingDocument(context)?:return
        if(pending.optString("requestId")!=requestId)return
        val owner=pending.optString("owner")
        if(owner!=expectedOwner)return
        val createdAt=pending.optLong("createdAt",0)
        val client=CloudAccountClient(context)
        BackgroundTaskRuntime.document(true,requestId);showActiveNotification();checkpoint()
        val uri=Uri.parse(intent.getStringExtra("uri"));val name=intent.getStringExtra("name")?:"schedule"
        val mime=intent.getStringExtra("mime").orEmpty();val color=intent.getIntExtra("color",0)
        fun ensureCurrent(){
            if(BackgroundTaskResults.documentCancelled(context,requestId)||BackgroundTaskResults.pendingDocument(context)?.optString("requestId")!=requestId)
                throw CancellationException("Document task cancelled")
            if(ownerProvider()!=owner)throw CloudApiException(401,"Document account changed")
            val age=System.currentTimeMillis()-createdAt
            if(age !in 0 until 23L*60*60*1000)throw CloudApiException(410,taskCopy("任务恢复期限已过，请手动重新提交","The recovery period expired. Submit a new task manually."))
        }
        var jpeg:ByteArray?=null
        try {
            val result=recoverAudioTransport(beforeAttempt={ensureCurrent();holdCpu()},onRetry={attempt,remaining->
                BackgroundTaskRuntime.documentProgress(taskCopy("文件任务连接中断，正在从原任务恢复","Reconnecting to the original file task"));showActiveNotification()
                releaseCpu()
                if(!context.audioNetworkAvailable())context.awaitAudioNetwork(minOf(remaining,60_000L))
                else delay(minOf(remaining,AudioRecoveryPolicy.retryDelayMillis(attempt)))
            }) {
                var value:JSONObject?=null
                while(value==null){
                    currentCoroutineContext().ensureActive();ensureCurrent();holdCpu()
                    val state=client.queryAiRequest(requestId,owner)
                    when(state?.optString("status")){
                        null->{
                            if(jpeg==null)jpeg=withContext(Dispatchers.IO){LocalDocumentRenderer.render(context,uri,name,mime)}
                            currentCoroutineContext().ensureActive();ensureCurrent()
                            try {value=client.recognizeImage(jpeg!!,"image/jpeg","${name.substringBeforeLast('.')}-rendered.jpg",color,requestId,owner)}
                            catch(error:CloudApiException){if(error.status!=409)throw error;delay(2_000)}
                        }
                        "preview"->value=state.getJSONObject("result")
                        "running","queued"->{BackgroundTaskRuntime.documentProgress(taskCopy("文件已上传，AI 正在识别","File uploaded. AI recognition is in progress."));showActiveNotification();delay(3_000)}
                        "applied"->throw CloudApiException(409,taskCopy("此预览已经应用，不会重复执行","This preview was already applied and will not run again."))
                        "failed","cancelled"->throw CloudApiException(400,taskCopy("文件任务已结束，请手动重新提交","The file task ended. Submit a new task manually."))
                        else->throw IOException("Unexpected document recovery status")
                    }
                }
                requireNotNull(value)
            }
            currentCoroutineContext().ensureActive();ensureCurrent()
            result.put("clientRequestId",requestId)
            check(BackgroundTaskResults.saveDocument(context,result.toString(),owner)){"Could not save document preview"}
            BackgroundTaskResults.clearPendingDocument(context,requestId)
            finish("document",taskCopy("文件识别完成","File recognition ready"),taskCopy("打开课间核对识别预览","Open Kejian to review the recognition preview."))
        }catch(cancelled:CancellationException){throw cancelled}
        catch(error:Exception){
            currentCoroutineContext().ensureActive()
            if(isTransientAudioError(error))checkpoint(60_000)
            else if(error !is CloudApiException||error.status!=401)BackgroundTaskResults.clearPendingDocument(context,requestId)
            finish("document",taskCopy("文件处理未完成","File processing incomplete"),
                if(isTransientAudioError(error))taskCopy("进度已保留，等待网络恢复；回到课间后也会自动继续。","Progress is saved for network recovery. Opening Kejian also resumes the task.")
                else localized(error.message?:taskCopy("文件转换或识别失败","File conversion or recognition failed.")),true,requestId)
        }finally {releaseCpu()}
    }
}
