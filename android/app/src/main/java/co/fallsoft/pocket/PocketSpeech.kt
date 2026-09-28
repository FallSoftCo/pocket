package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.*

/** Only runs for the duration of a short, explicitly enabled spoken notification. */
object PocketSpeech {
    fun text(n:JSONObject):String {
        val source=n.s("spoken_text").ifBlank{n.s("spoken_summary").ifBlank{n.s("title")+". "+n.s("body")}}
        return SpeechText.clean(source)
    }
    fun allowed(c:Context,kind:String):Boolean {
        if(PocketAudio.mode!="summaries"||Pocket.token.isBlank())return false
        val audio=c.getSystemService(AudioManager::class.java)
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!NotificationManagerCompat.from(c).areNotificationsEnabled()||audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL||audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION)==0||audio.mode!=AudioManager.MODE_NORMAL)return false
        if(manager.currentInterruptionFilter!=NotificationManager.INTERRUPTION_FILTER_ALL)return false
        val channel=manager.getNotificationChannel(PocketAudio.channel(c,kind))
        return channel!=null&&channel.importance>=NotificationManager.IMPORTANCE_DEFAULT&&channel.sound!=null
    }
    fun request(c:Context,n:JSONObject){
        if(!allowed(c,n.s("kind")))return
        try{ContextCompat.startForegroundService(c,Intent(c,PocketSpeechService::class.java).putExtra("notification",n.toString()))}
        catch(_:RuntimeException){Log.i("PocketSpeech","Speech unavailable; notification retained")}
    }
}

class PocketSpeechService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private var engine:TextToSpeech?=null
    private var ready=false
    private var speaking=false
    private var closed=false
    private var focus:AudioFocusRequest?=null
    private val queue=ArrayDeque<JSONObject>()
    private val chunks=ArrayDeque<String>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var loading=false
    private var activeKind="update"
    private var notificationId=0L
    private var chunkIndex=0
    private val seen=mutableSetOf<Long>()
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var activeId:String?=null
    private val timeout=Runnable{Log.w("PocketSpeech","Speech initialization or utterance stalled");stopSelf()}
    private fun armTimeout(milliseconds:Long){handler.removeCallbacks(timeout);handler.postDelayed(timeout,milliseconds)}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("spoken-playback","Spoken summary playback",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val stop=PendingIntent.getService(this,998,Intent(this,PocketSpeechService::class.java).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,"spoken-playback").setSmallIcon(R.drawable.ic_notification).setContentTitle("Speaking a Pocket summary")
            .setSilent(true).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addAction(R.drawable.ic_notification,"Stop",stop).build()
        try{
            if(Build.VERSION.SDK_INT>=29)startForeground(998,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(998,notification)
        }catch(_:RuntimeException){stopSelf();return}
        armTimeout(30000)
        engine=TextToSpeech(this){result->handler.post{
            if(closed)return@post
            val tts=engine?:return@post
            if(result!=TextToSpeech.SUCCESS){stopSelf();return@post}
            val voice=tts.voices?.filter{!it.isNetworkConnectionRequired&&it.locale.language=="en"&&!(it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)?:false)}
                ?.sortedWith(compareByDescending<android.speech.tts.Voice>{it.locale.country==Locale.getDefault().country}.thenByDescending{it.quality})?.firstOrNull()
            if(voice==null||tts.setVoice(voice)!=TextToSpeech.SUCCESS){Log.i("PocketSpeech","Offline voice unavailable; tone retained");stopSelf();return@post}
            tts.setAudioAttributes(attributes);tts.setSpeechRate(1.0f)
            Log.i("PocketSpeech","Using offline voice ${voice.name}")
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){handler.post{if(!closed&&id==activeId){Log.i("PocketSpeech","Started summary $id");armTimeout(90000)}}}
                override fun onDone(id:String?){handler.post{if(!closed&&id==activeId){Log.i("PocketSpeech","Completed summary $id");handler.removeCallbacks(timeout);activeId=null;speaking=false;next()}}}
                override fun onError(id:String?){handler.post{Log.w("PocketSpeech","Speech engine failed for $id");stopSelf()}}
                override fun onStop(id:String?,interrupted:Boolean){Log.i("PocketSpeech","Stopped summary $id; interrupted=$interrupted")}
            })
            ready=true;handler.removeCallbacks(timeout);handler.postDelayed({next()},400)
        }}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        val n=try{JSONObject(intent?.getStringExtra("notification")?:"")}catch(_:Exception){stopSelf();return START_NOT_STICKY}
        if(!PocketSpeech.allowed(this@PocketSpeechService,n.s("kind"))){stopSelf();return START_NOT_STICKY}
        if(seen.add(n.optLong("id"))){if(queue.size>=3)queue.removeFirst();queue.addLast(n)}
        if(ready&&!speaking&&!loading)next()
        return START_NOT_STICKY
    }
    private fun next(){
        if(closed||!ready||speaking||loading)return
        if(chunks.isNotEmpty()){if(PocketSpeech.allowed(this,activeKind))speakChunk() else stopSelf();return}
        if(queue.isEmpty()){stopSelf();return}
        val n=queue.removeFirst()
        if(!PocketSpeech.allowed(this@PocketSpeechService,n.s("kind"))){stopSelf();return}
        loading=true;armTimeout(30000)
        scope.launch{
            val full=if(n.s("speech_pending")=="1")try{
                Pocket.api("/api/notifications/${n.optLong("id")}").getJSONObject("notification")
            }catch(_:Exception){
                Log.i("PocketSpeech","Full message unavailable for ${n.optLong("id")}")
                JSONObject(n.toString()).put("spoken_text",n.s("spoken_summary").ifBlank{"A Pocket update has arrived."}+" The full spoken message is unavailable until the workstation reconnects.")
            }else n
            if(closed)return@launch
            loading=false;handler.removeCallbacks(timeout)
            if(!PocketSpeech.allowed(this@PocketSpeechService,n.s("kind"))){stopSelf();return@launch}
            notificationId=n.optLong("id");activeKind=n.s("kind");chunkIndex=0
            chunks.addAll(SpeechText.chunks(PocketSpeech.text(full),minOf(600,TextToSpeech.getMaxSpeechInputLength())))
            next()
        }
    }
    private fun speakChunk(){
        val audio=getSystemService(AudioManager::class.java)
        if(focus==null){
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({change->if(change<0){Log.i("PocketSpeech","Stopped for audio focus loss ($change)");stopSelf()}},handler).build()
            if(audio.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){stopSelf();return}
            focus=request
        }
        val text=chunks.removeFirst()
        speaking=true;activeId="$notificationId/${chunkIndex++}";armTimeout(90000)
        Log.i("PocketSpeech","Queued summary $activeId (${text.length} characters)")
        if(engine?.speak(text,TextToSpeech.QUEUE_ADD,Bundle(),activeId)!=TextToSpeech.SUCCESS)stopSelf()
    }
    override fun onDestroy(){
        closed=true;scope.cancel();handler.removeCallbacksAndMessages(null);queue.clear();chunks.clear();engine?.stop();engine?.shutdown();engine=null
        focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
