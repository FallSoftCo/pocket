package co.fallsoft.pocket

/** Liveness is advancing playback, not optional container-duration metadata. */
internal class SpeechPlaybackProgress(private val stallMs:Long=15_000) {
    private var lastPosition=0
    private var lastAdvanceAt=0L
    fun restart(now:Long,position:Int){lastPosition=position;lastAdvanceAt=now}
    fun remaining(now:Long,position:Int):Long {
        if(position>lastPosition){lastPosition=position;lastAdvanceAt=now}
        return (stallMs-(now-lastAdvanceAt).coerceAtLeast(0)).coerceAtLeast(0)
    }
}
