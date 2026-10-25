package co.fallsoft.pocket

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Optional sound never participates in recorder or Android service startup. */
internal object VoiceFeedback {
    private val executor=ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task,"nextcomp-voice-feedback").apply{isDaemon=true} })
    private val channel=VoiceFeedbackChannel(executor,{SystemClock.elapsedRealtime()}) {
        val generator=ToneGenerator(AudioManager.STREAM_MUSIC,55)
        object:FeedbackTone {
            override fun play(ok:Boolean){generator.startTone(if(ok)ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK,110)}
            override fun release(){generator.release()}
        }
    }
    fun open()=Session(channel.open())
    class Session internal constructor(private val owner:Long) {
        fun beep(ok:Boolean)=channel.play(owner,ok)
        fun close()=channel.close(owner)
    }
}

internal interface FeedbackTone { fun play(ok:Boolean);fun release() }

/** One serial hardware owner, one coalesced pending signal, no service references.
 * Native setup/release may block: neither runs under the state lock or on Main.
 * Closing fences queued sounds immediately; late initialization cannot beep.
 */
internal class VoiceFeedbackChannel(private val executor:Executor,private val clock:()->Long,
                                    private val factory:()->FeedbackTone) {
    private data class Signal(val owner:Long,val ok:Boolean?,val at:Long)
    private val gate=Any()
    private var sequence=0L
    private var owner=0L
    private var pending:Signal?=null
    private var draining=false
    // Only the serial worker may touch the native resource.
    private var tone:FeedbackTone?=null
    fun open():Long=synchronized(gate){++sequence;owner=sequence;owner}
    fun play(session:Long,ok:Boolean)=synchronized(gate){
        if(owner==session){pending=Signal(session,ok,clock());schedule()}
    }
    fun close(session:Long)=synchronized(gate){
        if(owner==session){owner=0;pending=Signal(session,null,clock());schedule()}
    }
    private fun schedule(){
        if(!draining){draining=true;executor.execute(::drain)}
    }
    private fun current(session:Long)=synchronized(gate){owner==session}
    private fun releaseIfIdle(){
        if(synchronized(gate){owner==0L}){
            val previous=tone;tone=null
            try{previous?.release()}catch(_:Exception){/* Optional feedback cannot fail capture. */}
        }
    }
    private fun drain(){
        try {
            while(true){
                val signal=synchronized(gate){pending.also{pending=null}}?:break
                if(signal.ok!=null&&current(signal.owner)&&clock()-signal.at<=750){
                    try{
                        val ready=tone?:factory().also{tone=it}
                        if(current(signal.owner)&&clock()-signal.at<=750)ready.play(signal.ok)
                    }catch(_:Exception){/* Missing/busy audio feedback must not block recording. */}
                }
                releaseIfIdle()
            }
        }finally{
            synchronized(gate){draining=false;if(pending!=null)schedule()}
        }
    }
}
