package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** A translated rendering can have a different number of chunks than its preserved source. */
internal fun completeRenderedSpeechChunk(queue:SpeechQueue,id:Long,index:Int,chunks:List<String>,expectedProfile:String,currentProfile:String):Boolean {
    if(expectedProfile!=currentProfile||queue.paused||queue.current?.id!=id||queue.chunkIndex!=index)return false
    queue.positionMs=0;queue.chunkIndex++
    if(chunks.getOrNull(queue.chunkIndex)==null){queue.messages.removeAt(0);queue.chunkIndex=0}
    return true
}

object PocketSpeech {
    internal var queue=SpeechQueue()
    internal var service:PocketSpeechService?=null
    var count by mutableIntStateOf(0); private set
    var paused by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set
    private val storage get()=Pocket.context.getSharedPreferences("speech-playback",Context.MODE_PRIVATE)
    internal val directory get()=File(Pocket.context.filesDir,"speech-playback").apply{mkdirs()}
    fun text(n:JSONObject)=SpeechText.clean(n.s("spoken_text").ifBlank{n.s("spoken_summary").ifBlank{n.s("title")+". "+n.s("body")}})
    fun init(){
        PocketSpeechCaptions.init()
        queue=try{
            val s=JSONObject(storage.getString("queue","{}")!!)
            SpeechQueue(s.optJSONArray("messages")?.objects()?.map{SpokenMessage(it.getLong("id"),it.s("title"),it.s("kind"),it.s("text"),it.optBoolean("needsFetch"))}?.toMutableList()?:mutableListOf(),s.optInt("chunk"),s.optInt("position"),true,"Saved for later")
        }catch(_:Exception){SpeechQueue()}
        if(Pocket.token.isBlank()||PocketAudio.mode!="summaries")clear() else {publish();if(count>0)showPaused()}
    }
    internal fun publish(){count=queue.messages.size;paused=queue.paused;title=queue.current?.title.orEmpty().ifBlank{"NextComp update"};status=queue.reason}
    internal fun save(durable:Boolean=false){
        val messages=JSONArray();queue.messages.forEach{messages.put(JSONObject().put("id",it.id).put("title",it.title).put("kind",it.kind).put("text",it.text).put("needsFetch",it.needsFetch))}
        val edit=storage.edit().putString("queue",JSONObject().put("messages",messages).put("chunk",queue.chunkIndex).put("position",queue.positionMs).toString())
        if(durable)edit.commit() else edit.apply()
        publish()
    }
    fun clear(){
        service?.discard();queue=SpeechQueue();storage.edit().clear().commit();directory.deleteRecursively()
        Pocket.context.getSystemService(NotificationManager::class.java).cancel(998);publish()
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
    fun request(c:Context,n:JSONObject){if(PocketVoice.active){PocketVoice.notification(n);return};Pocket.scope.launch{
        if(!allowed(c,n.s("kind")))return@launch
        val hadSaved=queue.messages.isNotEmpty()&&queue.paused
        queue.enqueue(SpokenMessage(n.optLong("id"),n.s("title"),n.s("kind"),text(n),n.s("speech_pending")=="1"));save(true)
        if(hadSaved){showPaused();return@launch}
        if(service==null)queue.resume()
        if(service==null&&c.getSystemService(AudioManager::class.java).isMusicActive){hold("Waiting while other audio plays");return@launch}
        service?.next()?:start(c,"play")
    }}
    fun control(action:String){
        if(action=="pause"){service?.pause("Paused by you");return}
        if(queue.current!=null)start(Pocket.context,"resume")
    }
    private fun start(c:Context,action:String){
        try{ContextCompat.startForegroundService(c,Intent(c,PocketSpeechService::class.java).setAction(action))}
        catch(_:RuntimeException){hold("Ready when you are")}
    }
    internal fun hold(reason:String){queue.pause(queue.positionMs,reason);save(true);showPaused()}
    internal fun notification(session:MediaSession?=null):Notification {
        val c=Pocket.context;val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("spoken-playback","Spoken message controls",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val action=if(queue.paused)"resume" else "pause"
        val intent=PendingIntent.getForegroundService(c,998,Intent(c,PocketSpeechService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(c,998,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label=if(queue.paused)"Resume" else "Pause"
        val detail=if(queue.paused)queue.reason+" · Your place is saved" else "${queue.messages.size} message${if(queue.messages.size==1)"" else "s"} · Pause any time"
        val builder=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if(queue.paused)"NextComp speech paused" else "Listening to NextComp").setContentText(detail)
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(!queue.paused)
            .addAction(Notification.Action.Builder(if(queue.paused)android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,label,intent).build())
        if(session!=null)builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
        PocketSpeechCaptions.decorate(builder)
        return builder.build()
    }
    internal fun showPaused(){if(queue.current!=null)try{Pocket.context.getSystemService(NotificationManager::class.java).notify(998,notification())}catch(_:SecurityException){}}
}

/** Local TTS synthesizes one private audio chunk; MediaPlayer provides real pause/seek. */
class PocketSpeechService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var engine:TextToSpeech?=null
    private var initializing=false
    private var ready=false
    private var closed=false
    private var finished=false
    private var loading=false
    private var explicitPlayback=false
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){pause("Headphones disconnected")}}
    private fun speechProfile()="${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}"
    private val speakingProfile=speechProfile()
    private var renderedKey=""
    private var renderedText=""
    private var renderedLanguage="en"
    private val renderingStorage get()=getSharedPreferences("speech-playback",Context.MODE_PRIVATE)
    private val renderedChunks get()=SpeechText.chunks(renderedText)
    private val spokenChunk get()=renderedChunks.getOrNull(queue.chunkIndex)
    private fun renderKey(message:SpokenMessage):String {
        val source=MessageDigest.getInstance("SHA-256").digest(message.text.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return "${PocketImmersion.enabled}:${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}:${message.id}:$source"
    }
    private fun installedVoice(language:String)=engine?.voices?.filter {
        !it.isNetworkConnectionRequired&&it.locale.language==language&&
            !(it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)?:false)
    }?.sortedWith(compareByDescending<android.speech.tts.Voice>{it.locale.country==Locale.getDefault().country}.thenByDescending{it.quality})?.firstOrNull()
    private fun selectVoice(language:String):Boolean {
        val voice=installedVoice(language)?:return false
        return engine?.setVoice(voice)==TextToSpeech.SUCCESS
    }
    private fun completeSpokenChunk(id:Long,index:Int):Boolean {
        val accepted=completeRenderedSpeechChunk(queue,id,index,renderedChunks,speakingProfile,speechProfile())
        if(accepted&&queue.current?.id!=id){renderedKey="";renderedText="";renderingStorage.edit().remove("currentRendering").apply()}
        return accepted
    }
    private var synthesizing:String?=null
    private var player:MediaPlayer?=null
    private var prepared=false
    private var started=false
    private var transientPaused=false
    private var focus:AudioFocusRequest?=null
    private lateinit var session:MediaSession
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val queue get()=PocketSpeech.queue
    private val timeout=Runnable{pause("Speech stalled · Tap Resume to retry")}
    private val checkpoint=object:Runnable{override fun run(){
        if(closed||queue.paused)return
        if(prepared)player?.let{queue.positionMs=it.currentPosition;PocketSpeech.save();queue.current?.let{m->PocketSpeechCaptions.update(m.id,m.title,queue.chunk,queue.chunkIndex,SpeechText.chunks(m.text).size)}}
        handler.postDelayed(this,2000)
    }}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate();PocketSpeech.service=this
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        session=MediaSession(this,"NextComp speech").apply{
            setCallback(object:MediaSession.Callback(){
                override fun onPause(){pause("Paused by you")}
                override fun onStop(){pause("Saved for later")}
                override fun onPlay(){queue.resume();PocketSpeech.save();next()}
            },handler)
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"NextComp spoken updates").putString(MediaMetadata.METADATA_KEY_ARTIST,"NextComp").build())
            isActive=true
        }
        try{
            val n=PocketSpeech.notification(session)
            if(Build.VERSION.SDK_INT>=29)startForeground(998,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(998,n)
        }catch(_:RuntimeException){pause("Ready when you are")}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(finished)return START_NOT_STICKY
        if(intent?.action=="pause"){pause("Paused by you");return START_NOT_STICKY}
        if(queue.current==null){finish();return START_NOT_STICKY}
        if(intent?.action=="resume"){explicitPlayback=true;queue.resume()}
        if(queue.paused){pause(queue.reason);return START_NOT_STICKY}
        PocketSpeech.save();next()
        return START_NOT_STICKY
    }
    private fun armTimeout(){handler.removeCallbacks(timeout);handler.postDelayed(timeout,90000)}
    private fun initializeVoice(){
        if(initializing)return
        initializing=true;armTimeout()
        engine=TextToSpeech(this){result->handler.post{
            if(closed||finished)return@post
            val tts=engine?:return@post
            if(result!=TextToSpeech.SUCCESS){pause("Offline voice unavailable · Tap Resume to retry");return@post}
            tts.setSpeechRate(1.0f)
            tts.setOnUtteranceProgressListener(object:UtteranceProgressListener(){
                override fun onStart(id:String?){}
                override fun onDone(id:String?){handler.post{
                    if(!closed&&!finished&&id==synthesizing){
                        synthesizing=null;handler.removeCallbacks(timeout)
                        val temp=File(PocketSpeech.directory,"pending.wav");val dest=audioFile()
                        if(dest==null||temp.length()<=44||!temp.renameTo(dest)){pause("Could not prepare speech · Tap Resume to retry");return@post}
                        next()
                    }
                }}
                override fun onError(id:String?){handler.post{if(!closed&&!finished&&id==synthesizing)pause("Could not prepare speech · Tap Resume to retry")}}
            })
            ready=true;handler.removeCallbacks(timeout);next()
        }}
    }
    private fun audioFile():File?=spokenChunk?.let{chunk->
        val hash=MessageDigest.getInstance("SHA-256").digest((renderedLanguage+":"+chunk).toByteArray()).joinToString(""){"%02x".format(it)}
        File(PocketSpeech.directory,"$hash.wav")
    }
    internal fun next(){
        if(closed||finished||queue.paused)return
        if(speechProfile()!=speakingProfile){pause("Profile changed · Your place is saved");return}
        if(player!=null){if(prepared)updateControls(PlaybackState.STATE_PLAYING);return}
        if(loading||synthesizing!=null)return
        val message=queue.current?:run{finish();return}
        if(!PocketSpeech.allowed(this,message.kind)){pause("Waiting for sound to be enabled");return}
        updateControls(PlaybackState.STATE_BUFFERING)
        if(message.needsFetch){
            loading=true;armTimeout()
            scope.launch{
                val full=try{Pocket.api("/api/notifications/${message.id}").getJSONObject("notification")}catch(e:CancellationException){throw e}catch(_:Exception){null}
                if(closed||finished)return@launch
                loading=false;handler.removeCallbacks(timeout)
                if(full==null){pause("Reconnect to your workstation to load the full message");return@launch}
                queue.messages[0]=message.copy(text=PocketSpeech.text(full),needsFetch=false);PocketSpeech.save(true);next()
            }
            return
        }
        if(!ready){initializeVoice();return}
        val sourceKey=renderKey(message)
        if(renderedKey!=sourceKey){
            loading=true;armTimeout()
            scope.launch {
                val saved=try{JSONObject(renderingStorage.getString("currentRendering","{}")!!)}catch(_:Exception){JSONObject()}
                val cached=saved.s("key")==sourceKey
                if(!cached&&queue.chunkIndex>0){queue.chunkIndex=0;queue.positionMs=0;PocketSpeech.save(true)}
                var language=if(cached)saved.s("language","en") else "en"
                var rendering=if(cached)saved.s("text",message.text) else message.text
                if(!cached&&PocketImmersion.enabled&&installedVoice("it")!=null){
                    val translated=PocketImmersion.spoken("notification-speech:${message.id}",message.text)
                    if(translated!=message.text){rendering=translated;language="it"}
                }
                if(closed||finished)return@launch
                if(speechProfile()!=speakingProfile){loading=false;pause("Profile changed · Your place is saved");return@launch}
                if(queue.current?.id!=message.id||renderKey(message)!=sourceKey){loading=false;handler.removeCallbacks(timeout);next();return@launch}
                loading=false;handler.removeCallbacks(timeout)
                if(!selectVoice(language)){
                    rendering=message.text;language="en"
                    if(!selectVoice(language)){pause("Install an offline English voice to listen");return@launch}
                    // Cursor belongs to a different rendering; restart rather than skip part of the source.
                    queue.chunkIndex=0;queue.positionMs=0
                }
                renderedKey=sourceKey;renderedText=rendering;renderedLanguage=language
                renderingStorage.edit().putString("currentRendering",JSONObject().put("key",sourceKey).put("text",rendering).put("language",language).toString()).apply()
                next()
            }
            return
        }
        val text=spokenChunk
        if(text==null){completeSpokenChunk(message.id,queue.chunkIndex);PocketSpeech.save(true);next();return}
        val file=audioFile()!!
        if(file.length()>44){play(file,message.id,queue.chunkIndex);return}
        // Only the current chunk is retained. Text for the rest stays in the private queue.
        PocketSpeech.directory.listFiles()?.forEach{it.delete()}
        synthesizing="${message.id}/${queue.chunkIndex}";armTimeout()
        if(engine?.synthesizeToFile(text,Bundle(),File(PocketSpeech.directory,"pending.wav"),synthesizing)!=TextToSpeech.SUCCESS)pause("Could not prepare speech · Tap Resume to retry")
    }
    private fun play(file:File,id:Long,index:Int){
        if(focus==null){
            if(!explicitPlayback&&getSystemService(AudioManager::class.java).isMusicActive){pause("Waiting while other audio plays");return}
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener({change->when(change){
                    AudioManager.AUDIOFOCUS_GAIN->{player?.setVolume(1f,1f);resumeTransient()}
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->player?.setVolume(.25f,.25f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->pauseTransient()
                    AudioManager.AUDIOFOCUS_LOSS->pause("Paused for other audio")
                }},handler).build()
            if(getSystemService(AudioManager::class.java).requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause("Waiting for other audio to finish");return}
            focus=request
        }
        try{
            val media=MediaPlayer();player=media
            media.setAudioAttributes(attributes);media.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);media.setDataSource(file.path)
            media.setOnErrorListener{_,_,_->pause("Playback interrupted · Tap Resume to retry");true}
            media.setOnPreparedListener{
                if(player!==media||finished)return@setOnPreparedListener
                prepared=true
                if(queue.positionMs>0){
                    media.setOnSeekCompleteListener{if(player===media&&!finished)startPlayer(media,id,index)}
                    media.seekTo(queue.positionMs.toLong().coerceAtMost(media.duration.toLong()),MediaPlayer.SEEK_CLOSEST)
                }else startPlayer(media,id,index)
            }
            media.setOnCompletionListener{
                if(player!==media||finished)return@setOnCompletionListener
                Log.i("PocketSpeech","Completed audio $id/$index")
                handler.removeCallbacks(checkpoint);handler.removeCallbacks(timeout);media.release();player=null;prepared=false;started=false
                if(completeSpokenChunk(id,index)){PocketSpeech.save(true);file.delete()}
                next()
            }
            armTimeout();media.prepareAsync()
        }catch(_:Exception){pause("Playback interrupted · Tap Resume to retry")}
    }
    private fun startPlayer(media:MediaPlayer,id:Long,index:Int){
        handler.removeCallbacks(timeout);media.start();started=true
        queue.current?.let{PocketSpeechCaptions.update(it.id,it.title,spokenChunk,queue.chunkIndex,renderedChunks.size)}
        handler.postDelayed(timeout,(media.duration-media.currentPosition).toLong().coerceAtLeast(0)+15000)
        Log.i("PocketSpeech","Playing audio $id/$index from ${media.currentPosition} ms")
        updateControls(PlaybackState.STATE_PLAYING);handler.removeCallbacks(checkpoint);handler.post(checkpoint)
    }
    private fun pauseTransient(){
        if(!started||transientPaused)return
        player?.let{try{it.pause();queue.positionMs=it.currentPosition}catch(_:IllegalStateException){return}}
        transientPaused=true;handler.removeCallbacks(timeout);handler.removeCallbacks(checkpoint);PocketSpeech.save(true);updateControls(PlaybackState.STATE_PAUSED)
    }
    private fun resumeTransient(){
        if(!transientPaused)return
        val media=player?:return
        try{
            media.start();transientPaused=false
            handler.postDelayed(timeout,(media.duration-media.currentPosition).toLong().coerceAtLeast(0)+15000)
            updateControls(PlaybackState.STATE_PLAYING);handler.removeCallbacks(checkpoint);handler.post(checkpoint)
        }catch(_:IllegalStateException){pause("Playback interrupted · Tap Resume to retry")}
    }
    private fun updateControls(state:Int){
        PocketSpeech.publish()
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
            .setState(state,queue.positionMs.toLong(),if(state==PlaybackState.STATE_PLAYING)1f else 0f).build())
        getSystemService(NotificationManager::class.java).notify(998,PocketSpeech.notification(session))
    }
    internal fun pause(reason:String){
        if(finished)return
        if(started)player?.let{try{it.pause();queue.positionMs=it.currentPosition}catch(_:IllegalStateException){}}
        queue.pause(queue.positionMs,reason);PocketSpeech.save(true)
        Log.i("PocketSpeech","Paused audio ${queue.current?.id}/${queue.chunkIndex} at ${queue.positionMs} ms: $reason")
        finished=true;releasePlayback();stopForeground(STOP_FOREGROUND_DETACH);PocketSpeechCaptions.pause();PocketSpeech.showPaused();stopSelf()
    }
    internal fun discard(){finished=true;releasePlayback();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun finish(){finished=true;releasePlayback();queue.positionMs=0;PocketSpeech.save(true);PocketSpeech.directory.deleteRecursively();stopForeground(STOP_FOREGROUND_REMOVE);PocketSpeechCaptions.complete();stopSelf()}
    private fun releasePlayback(){
        handler.removeCallbacksAndMessages(null);scope.cancel();player?.release();player=null;prepared=false;started=false;transientPaused=false;engine?.stop();engine?.shutdown();engine=null
        focus?.let{getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)};focus=null
        session.isActive=false;session.release()
    }
    override fun onDestroy(){
        if(!finished){
            if(started)player?.let{try{queue.positionMs=it.currentPosition}catch(_:IllegalStateException){}}
            PocketSpeech.hold("Saved for later");releasePlayback();stopForeground(STOP_FOREGROUND_DETACH)
        }
        unregisterReceiver(noisy)
        closed=true;if(PocketSpeech.service===this)PocketSpeech.service=null
        super.onDestroy()
    }
}
