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

/** Only runs for the duration of a short, explicitly enabled spoken notification. */
object PocketSpeech {
    fun text(n:JSONObject):String {
        val source=n.s("spoken_summary").ifBlank{n.s("title")+". "+n.s("body")}
        return source.replace(Regex("```[\\s\\S]*?(?:```|$)")," ")
            .replace(Regex("`[^`]*`")," ").replace(Regex("\\[([^]]+)]\\([^)]*\\)"),"$1")
            .replace(Regex("https?://\\S+|(?:^|\\s)(?:/?[\\w.-]+/){2,}\\S*")," ")
            .replace(Regex("\\b(?:Bearer\\s+\\S+|(?:api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S+)",RegexOption.IGNORE_CASE),"private value")
            .replace(Regex("[*_#~>|]")," ").replace(Regex("\\s+")," ").trim().split(" ").take(28).joinToString(" ").take(190)
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
    private val seen=mutableSetOf<Long>()
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val timeout=Runnable{stopSelf()}
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
        handler.postDelayed(timeout,30000)
        engine=TextToSpeech(this){result->handler.post{
            if(closed)return@post
            val tts=engine?:return@post
            if(result!=TextToSpeech.SUCCESS){stopSelf();return@post}
            val voice=tts.voices?.filter{!it.isNetworkConnectionRequired&&it.locale.language=="en"&&!(it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)?:false)}
                ?.sortedWith(compareByDescending<android.speech.tts.Voice>{it.locale.country==Locale.getDefault().country}.thenByDescending{it.quality})?.firstOrNull()
            if(voice==null||tts.setVoice(voice)!=TextToSpeech.SUCCESS){Log.i("PocketSpeech","Offline voice unavailable; tone retained");stopSelf();return@post}
            tts.setAudioAttributes(attributes);tts.setSpeechRate(1.05f)
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){Log.i("PocketSpeech","Started summary $id")}
                override fun onDone(id:String?){handler.post{if(!closed){Log.i("PocketSpeech","Completed summary $id");speaking=false;next()}}}
                override fun onError(id:String?){handler.post{stopSelf()}}
            })
            ready=true;handler.postDelayed({next()},400)
        }}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop"){stopSelf();return START_NOT_STICKY}
        val n=try{JSONObject(intent?.getStringExtra("notification")?:"")}catch(_:Exception){stopSelf();return START_NOT_STICKY}
        if(!PocketSpeech.allowed(this,n.s("kind"))){stopSelf();return START_NOT_STICKY}
        if(seen.add(n.optLong("id"))){if(queue.size>=3)queue.removeFirst();queue.addLast(n)}
        if(ready&&!speaking)next()
        return START_NOT_STICKY
    }
    private fun next(){
        if(closed||!ready||speaking)return
        if(queue.isEmpty()){stopSelf();return}
        val n=queue.removeFirst()
        if(!PocketSpeech.allowed(this,n.s("kind"))){stopSelf();return}
        val text=PocketSpeech.text(n)
        if(text.isBlank()){next();return}
        val audio=getSystemService(AudioManager::class.java)
        if(focus==null){
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({change->if(change<0)stopSelf()},handler).build()
            if(audio.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){stopSelf();return}
            focus=request
        }
        speaking=true
        if(engine?.speak(text,TextToSpeech.QUEUE_FLUSH,Bundle(),n.optLong("id").toString())!=TextToSpeech.SUCCESS)stopSelf()
    }
    override fun onDestroy(){
        closed=true;handler.removeCallbacksAndMessages(null);queue.clear();engine?.stop();engine?.shutdown();engine=null
        focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
