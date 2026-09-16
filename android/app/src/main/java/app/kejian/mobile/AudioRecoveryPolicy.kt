package app.kejian.mobile

/** Transient transport failures are resumable work, never a user's pause instruction. */
internal object AudioRecoveryPolicy {
    const val ACTIVE_RETRY_WINDOW_MS=15L*60*1000
    fun retryDelayMillis(attempt:Int):Long=(2_000L shl (attempt-1).coerceIn(0,5)).coerceAtMost(60_000L)
    fun canResume(paused:Boolean,expectedOwner:String?,currentOwner:String?):Boolean=
        !paused&&expectedOwner!=null&&expectedOwner==currentOwner
}

/** Uses only newly acknowledged bytes; duplicate/replayed chunks never inflate throughput. */
internal class AudioUploadRate {
    private var baselineBytes:Long?=null
    private var baselineMillis=0L
    var bytesPerSecond=0L;private set
    fun update(acknowledged:Long,nowMillis:Long):Long {
        val baseline=baselineBytes
        if(baseline==null||acknowledged<baseline){baselineBytes=acknowledged;baselineMillis=nowMillis;bytesPerSecond=0;return 0}
        val elapsed=nowMillis-baselineMillis
        if(elapsed>=1_000)bytesPerSecond=((acknowledged-baseline)*1000/elapsed).coerceAtLeast(0)
        return bytesPerSecond
    }
    fun remainingSeconds(total:Long,acknowledged:Long):Long?=
        bytesPerSecond.takeIf {it>0}?.let {((total-acknowledged).coerceAtLeast(0)+it-1)/it}
}
