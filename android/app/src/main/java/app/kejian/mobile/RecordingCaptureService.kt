package app.kejian.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import java.util.UUID

object RecordingRuntime {
    private val timeline=RecordingTimeline(SystemClock::elapsedRealtime)
    /** True for the complete active session, including a pause. */
    var recording by mutableStateOf(false);private set
    var paused by mutableStateOf(false);private set
    var startedAt by mutableLongStateOf(0L);private set
    var completedFile by mutableStateOf<File?>(null);private set
    var completedSeconds by mutableIntStateOf(0);private set
    var error by mutableStateOf<String?>(null);private set
    fun elapsedMillis():Long=if(recording)timeline.elapsedMillis()else completedSeconds*1000L
    fun elapsedSeconds():Int=(elapsedMillis()/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun started(@Suppress("UNUSED_PARAMETER") file:File){
        if(recording)return
        timeline.start();completedFile=null;completedSeconds=0;error=null
        startedAt=System.currentTimeMillis();paused=false;recording=true
    }
    fun pause(){if(recording&&timeline.pause()){paused=true;error=null}}
    fun resume(){if(recording&&timeline.resume()){paused=false;error=null}}
    fun tooShort(){error=recordingCopy("录音时间过短，至少需要录制 10 秒。","Recording is too short. Record at least 10 seconds of audio.")}
    fun stopped(file:File,seconds:Int=elapsedSeconds(),warning:String?=null){
        timeline.finish();completedSeconds=seconds.coerceAtLeast(0);completedFile=file
        paused=false;recording=false;error=warning
    }
    fun restore(file:File,seconds:Int){if(!recording){timeline.reset();paused=false;completedFile=file;completedSeconds=seconds;error=null}}
    fun failed(message:String){timeline.finish();paused=false;recording=false;error=message}
    fun clear(delete:Boolean=false){
        if(delete)completedFile?.delete()
        completedFile=null;completedSeconds=0;error=null
        if(!recording){timeline.reset();startedAt=0L;paused=false}
    }
}

class RecordingService:Service(){
    companion object {
        const val START="app.kejian.mobile.RECORD_START"
        const val PAUSE="app.kejian.mobile.RECORD_PAUSE"
        const val RESUME="app.kejian.mobile.RECORD_RESUME"
        const val STOP="app.kejian.mobile.RECORD_STOP"
        const val CHANNEL="kejian_recording"
        private const val NOTIFICATION_ID=8500
        private const val MAX_AUDIO_MILLIS=6L*60*60*1000
    }
    private var recorder:MediaRecorder?=null
    private var output:File?=null
    private var wakeLock:PowerManager.WakeLock?=null
    private val handler=Handler(Looper.getMainLooper())
    private var finishing=false
    private var destroying=false
    private val tick=object:Runnable {
        override fun run(){
            if(recorder==null||!RecordingRuntime.recording)return
            if(!RecordingRuntime.paused&&RecordingRuntime.elapsedMillis()>=MAX_AUDIO_MILLIS){
                finishRecording(force=true,warning=recordingCopy("已达到单次 6 小时上限，录音已保存。","The six-hour recording limit was reached. Your audio has been saved."));return
            }
            updateNotification()
            // A paused session needs no timer and holds no wake lock.
            if(!RecordingRuntime.paused)handler.postDelayed(this,1000)
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,recordingCopy("课堂录音","Class recording"),NotificationManager.IMPORTANCE_LOW).apply {
                description=recordingCopy("显示正在录音或暂停的状态，以及录音控制。","Recording status, elapsed audio time, and recording controls.")
                setSound(null,null)
            })
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        when(intent?.action){
            START->startRecording()
            PAUSE->pauseRecording()
            RESUME->resumeRecording()
            STOP->finishRecording()
            else->if(recorder==null)stopSelf(startId)
        }
        // No boot, process-death or null-intent microphone restart.
        return START_NOT_STICKY
    }
    private fun action(action:String,requestCode:Int)=PendingIntent.getService(this,requestCode,
        Intent(this,RecordingService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(message:String?=null):Notification {
        val seconds=RecordingRuntime.elapsedSeconds()
        val clock=String.format(Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        val paused=RecordingRuntime.paused
        return NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(paused)recordingCopy("录音已暂停","Recording paused")else recordingCopy("课间正在录音","Kejian is recording"))
            .setContentText(message?:if(paused)recordingCopy("已录制 $clock · 点击继续录音","Recorded $clock · Resume when ready")else recordingCopy("$clock · 后台继续录音，音频仅保存在本机","$clock · Recording continues in the background. Audio stays local."))
            .setWhen(System.currentTimeMillis()-RecordingRuntime.elapsedMillis()).setUsesChronometer(!paused)
            .setOngoing(true).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(this,8501,Intent(this,MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_RECORDING,true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0,if(paused)recordingCopy("继续","Resume")else recordingCopy("暂停","Pause"),
                if(paused)action(RESUME,8504)else action(PAUSE,8503))
            .addAction(0,recordingCopy("停止","Stop"),action(STOP,8502)).build()
    }
    private fun updateNotification(message:String?=null){
        runCatching {getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(message))}
    }
    private fun acquireWakeLock(){
        val remaining=(MAX_AUDIO_MILLIS-RecordingRuntime.elapsedMillis()).coerceAtLeast(0L)
        val lock=wakeLock?:getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"$packageName:Recording").apply {
            setReferenceCounted(false);wakeLock=this
        }
        // Finite even if a future exception misses cleanup; normal pause/stop
        // paths release immediately. The effective-audio limit is six hours.
        lock.acquire(remaining+30_000L)
    }
    private fun releaseWakeLock(){wakeLock?.let {if(it.isHeld)runCatching {it.release()}}}
    @Suppress("DEPRECATION") private fun startRecording(){
        if(recorder!=null)return
        if(RecordingRuntime.recording){
            RecordingRuntime.failed(recordingCopy("上一段录音已被系统中断，请重新开始录音。","The previous recording was interrupted by the system. Start a new recording when ready."))
            stopSelf();return
        }
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            RecordingRuntime.failed(recordingCopy("请允许麦克风权限后再开始录音。","Allow microphone access before recording."));stopSelf();return
        }
        finishing=false
        try {
            // The visible activity explicitly starts this microphone foreground
            // service. A background/boot action never reaches START implicitly.
            if(Build.VERSION.SDK_INT>=30)startForeground(NOTIFICATION_ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID,notification())
            val music=getExternalFilesDir(Environment.DIRECTORY_MUSIC)?:error("Local recording storage unavailable")
            val dir=File(music,"Recordings")
            check(dir.isDirectory||dir.mkdirs())
            val file=File(dir,"kejian-${System.currentTimeMillis()}-${UUID.randomUUID()}.m4a")
            output=file
            val value=if(Build.VERSION.SDK_INT>=31)MediaRecorder(this)else MediaRecorder()
            recorder=value // Own it before prepare/start so every failure releases it.
            value.setOnErrorListener {source,_,_->if(recorder===source&&!finishing)finishRecording(force=true,
                warning=recordingCopy("录音设备中断，正在保留已录制的音频。","The recorder was interrupted. Preserving the audio recorded so far."))}
            value.setAudioSource(MediaRecorder.AudioSource.MIC)
            value.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            value.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            value.setAudioSamplingRate(16_000);value.setAudioEncodingBitRate(64_000)
            value.setOutputFile(file.absolutePath);value.prepare();value.start()
            RecordingRuntime.started(file);acquireWakeLock()
            runCatching {RecordingDraftStore.save(this,file,0)}
            handler.removeCallbacks(tick);handler.post(tick)
        }catch(_:Exception){
            if(RecordingRuntime.recording)finishRecording(force=true,warning=recordingCopy("无法维持录音，请检查麦克风或设备限制。","Recording could not continue. Check microphone access or device restrictions."))
            else {
                runCatching {recorder?.release()};recorder=null;releaseWakeLock();handler.removeCallbacks(tick)
                output?.let {if(it.isFile&&it.length()==0L)it.delete()};output=null
                RecordingRuntime.failed(recordingCopy("无法开始录音，请检查麦克风权限、可用空间或系统限制。","Could not start recording. Check microphone permission, storage space, and system restrictions."))
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        }
    }
    private fun pauseRecording(){
        val value=recorder?:run {stopSelf();return}
        if(!RecordingRuntime.recording||RecordingRuntime.paused||finishing)return
        try {
            value.pause();RecordingRuntime.pause();handler.removeCallbacks(tick);releaseWakeLock()
            updateNotification()
        }catch(_:Exception){finishRecording(force=true,warning=recordingCopy("无法暂停录音，已结束并尝试保留音频。","Could not pause. Recording was stopped and its audio retained where possible."))}
    }
    private fun resumeRecording(){
        val value=recorder?:run {stopSelf();return}
        if(!RecordingRuntime.recording||!RecordingRuntime.paused||finishing)return
        try {
            acquireWakeLock();value.resume();RecordingRuntime.resume()
            handler.removeCallbacks(tick);handler.post(tick)
        }catch(_:Exception){releaseWakeLock();finishRecording(force=true,warning=recordingCopy("无法继续录音，已结束并尝试保留音频。","Could not resume. Recording was stopped and its audio retained where possible."))}
    }
    private fun inspectedSeconds(file:File):Int {
        val reader=MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            if(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")0
            else ((reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L)/1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }catch(_:Exception){0}finally{runCatching {reader.release()}}
    }
    private fun finishRecording(force:Boolean=false,warning:String?=null){
        if(finishing)return
        val value=recorder?:run {if(!destroying)stopSelf();return}
        if(!force&&RecordingRuntime.elapsedMillis()<10_000L){
            RecordingRuntime.tooShort()
            val message=RecordingRuntime.error.orEmpty()
            Toast.makeText(this,message,Toast.LENGTH_SHORT).show();updateNotification(message);return
        }
        finishing=true;handler.removeCallbacks(tick)
        val file=output;val seconds=RecordingRuntime.elapsedSeconds()
        val stopped=runCatching {value.stop()}.isSuccess
        runCatching {value.release()};recorder=null;output=null;releaseWakeLock()
        try {
            val usable=file?.takeIf {it.isFile&&it.length()>0L}
            val duration=if(stopped)seconds else usable?.let(::inspectedSeconds)?:0
            if(usable!=null&&(stopped||duration>=10)){
                var message=warning
                // Save independently: a metadata failure must not discard the audio.
                val draftSaved=runCatching {RecordingDraftStore.save(this,usable,duration)}.isSuccess
                val librarySaved=duration>=10&&runCatching {RecordingLibrary.capture(this,usable,duration)}.isSuccess
                if(duration>=10&&(!draftSaved||!librarySaved))message=recordingCopy("音频已保存在本机，列表暂未保存；重新打开录音页可尝试恢复。","Audio is saved locally, but its list entry could not be saved. Reopen Record to recover it.")
                if(duration<10)message=recordingCopy("录音已结束，但有效录音不足 10 秒，未加入录音列表。","Recording ended with less than 10 seconds of audio and was not added to the library.")
                RecordingRuntime.stopped(usable,duration,message)
            }else {
                // Keep non-empty bytes after an interrupted finalization. Recovery
                // validates audio before publishing it; never invent a valid file.
                if(usable!=null)runCatching {RecordingDraftStore.save(this,usable,seconds)}
                else file?.let {if(it.isFile&&it.length()==0L)it.delete()}
                RecordingRuntime.failed(recordingCopy("录音保存未能完成。非空音频文件已保留在本机，可重新打开录音页尝试恢复。","Recording could not be finalized. Non-empty audio remains on this device; reopen Record to try recovery."))
            }
        }catch(_:Exception){
            RecordingRuntime.failed(recordingCopy("录音已结束，但无法读取本地音频；文件未被删除，请检查存储空间。","Recording has ended, but the local audio could not be read. The file was not deleted; check device storage."))
        }finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            if(!destroying)stopSelf()
        }
    }
    override fun onDestroy(){
        destroying=true
        try {if(recorder!=null)finishRecording(force=true)}
        finally {handler.removeCallbacks(tick);releaseWakeLock();super.onDestroy()}
    }
}
