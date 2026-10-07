package co.fallsoft.pocket

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.*

/** Playback time, not proof of audibility or provider billing. */
internal class SpeechPlaybackMeter {
    private var since:Long?=null
    fun start(now:Long){if(since==null)since=now}
    fun sample(now:Long):Long {val begin=since?:return 0;since=now;return (now-begin).coerceAtLeast(0)}
    fun stop(now:Long):Long {val amount=sample(now);since=null;return amount}
}

/** Local aggregate counters only. No text, account IDs, or provider-price estimates. */
internal object SpeechUsage {
    var revision by mutableIntStateOf(0);private set
    private val prefs get()=Pocket.context.getSharedPreferences("native-speech-usage",Context.MODE_PRIVATE)
    @Synchronized fun add(key:String,amount:Long=1){if(amount<=0)return;prefs.edit().putLong(key,prefs.getLong(key,0)+amount).apply();revision++}
    fun summary():String {
        revision
        fun minutes(key:String)=String.format(java.util.Locale.getDefault(),"%.1f",prefs.getLong(key,0)/60000.0)
        return "Since tracking began · ${prefs.getLong("generated",0)} generated chunks · ${minutes("generatedMs")} min generated · ${minutes("playedMs")} min played · ${prefs.getLong("cancelled",0)} cancelled · ${prefs.getLong("failed",0)} failed · ${prefs.getLong("cacheHits",0)} cached requests · ${prefs.getLong("unknownDuration",0)} durations unknown · ${minutes("voiceConnectionMs")} min voice-mode connection"
    }
    fun playback(meter:SpeechPlaybackMeter,stop:Boolean=false){add("playedMs",if(stop)meter.stop(SystemClock.elapsedRealtime()) else meter.sample(SystemClock.elapsedRealtime()))}
}
