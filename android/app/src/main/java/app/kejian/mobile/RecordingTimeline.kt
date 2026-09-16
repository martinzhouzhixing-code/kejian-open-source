package app.kejian.mobile

/** Monotonic active-audio clock. Paused time is never part of an output file. */
internal class RecordingTimeline(private val monotonicMillis:()->Long) {
    enum class Phase { IDLE, RECORDING, PAUSED, ENDED }
    var phase=Phase.IDLE;private set
    private var accumulated=0L
    private var activeSince=0L
    val sessionActive get()=phase==Phase.RECORDING||phase==Phase.PAUSED
    fun start():Boolean {
        if(sessionActive)return false
        accumulated=0L;activeSince=monotonicMillis();phase=Phase.RECORDING;return true
    }
    fun pause():Boolean {
        if(phase!=Phase.RECORDING)return false
        accumulated=elapsedMillis();phase=Phase.PAUSED;return true
    }
    fun resume():Boolean {
        if(phase!=Phase.PAUSED)return false
        activeSince=monotonicMillis();phase=Phase.RECORDING;return true
    }
    fun finish():Long {
        if(sessionActive){accumulated=elapsedMillis();phase=Phase.ENDED}
        return accumulated
    }
    fun elapsedMillis():Long=accumulated+if(phase==Phase.RECORDING)(monotonicMillis()-activeSince).coerceAtLeast(0L)else 0L
    fun canStop(minimumMillis:Long=10_000L)=sessionActive&&elapsedMillis()>=minimumMillis
    fun reset(){phase=Phase.IDLE;accumulated=0L;activeSince=0L}
}
