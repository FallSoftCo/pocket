package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.*
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
    private val displayed=DisplayedSpeechSession()
    var displayedOwner by mutableStateOf<String?>(null);private set
    var displayedRunning by mutableStateOf(false);private set
    var displayedProblem by mutableStateOf("");private set
    var displayedText by mutableStateOf("");private set
    var displayedTitle by mutableStateOf("");private set
    private var displayedGeneration=0L
    private var parkedRendering:String?=null
    private var displayedProfile=""
    private fun ownerProfile()=MessageDigest.getInstance("SHA-256").digest("${Pocket.local}:${Pocket.base}:${Pocket.token}".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun speakDisplayed(owner:String,text:String,title:String){
        if(owner.isBlank()||text.isBlank())return
        if(displayed.owner!=null&&displayedProfile!=ownerProfile()){stopDisplayed(displayed.owner!!);init()}
        val expectedProfile=ownerProfile()
        val old=service
        old?.pause("Saved for later")
        PocketSpeechCaptions.dismiss()
        if(displayed.owner==null){
            displayedProfile=ownerProfile()
            parkedRendering=storage.getString("currentRendering",null)
            storage.edit().putBoolean("displayedParked",true).putString("displayedParkedRendering",parkedRendering).commit()
        }
        val generation=++displayedGeneration
        queue=displayed.start(owner,queue,SpokenMessage(-generation,title,"displayed",SpeechText.clean(text)))
        displayedOwner=owner;displayedRunning=true;displayedProblem="";displayedText=text;displayedTitle=title;publish();save(true)
        Pocket.scope.launch {
            var attempts=0
            while(service===old&&old!=null){
                kotlinx.coroutines.delay(20);if(generation!=displayedGeneration)return@launch
                if(++attempts>=150){hold("Could not start speech · Tap Speak to retry");return@launch}
            }
            if(generation==displayedGeneration){if(expectedProfile==ownerProfile())start(Pocket.context,"displayed") else stopDisplayed(owner)}
        }
    }
    fun stopDisplayed(owner:String){
        if(displayed.owner!=owner)return
        ++displayedGeneration
        service?.discard()
        val sameProfile=displayedProfile==ownerProfile()
        if(sameProfile)PocketSpeechCaptions.dismiss()
        queue=displayed.stop(owner)?:return
        val edit=storage.edit();parkedRendering?.let{edit.putString("currentRendering",it)}?:edit.remove("currentRendering");edit.remove("displayedParked").remove("displayedParkedRendering").commit();parkedRendering=null
        displayedOwner=null;displayedRunning=false;displayedProblem="";displayedText="";displayedTitle="";if(sameProfile)save(true) else publish()
        Pocket.context.getSystemService(NotificationManager::class.java).cancel(998)
        if(sameProfile&&queue.current!=null)showPaused()
        if(!sameProfile){queue=SpeechQueue();publish()}
        displayedProfile=""
    }
    internal fun displayedCompleted(){displayedOwner?.let{stopDisplayed(it)}}
    internal var service:PocketSpeechService?=null
    var count by mutableIntStateOf(0); private set
    var paused by mutableStateOf(false); private set
    var title by mutableStateOf(""); private set
    var status by mutableStateOf(""); private set
    private val storage get()=Pocket.context.getSharedPreferences("speech-playback",Context.MODE_PRIVATE)
    internal val directory get()=File(Pocket.context.filesDir,"speech-playback").apply{mkdirs()}
    fun text(n:JSONObject)=SpeechText.clean(n.s("spoken_text").ifBlank{n.s("spoken_summary").ifBlank{n.s("title")+". "+n.s("body")}})
    fun init(){
        displayedOwner?.let{stopDisplayed(it)}
        if(storage.getBoolean("displayedParked",false)){
            val original=storage.getString("displayedParkedRendering",null);val edit=storage.edit()
            original?.let{edit.putString("currentRendering",it)}?:edit.remove("currentRendering")
            edit.remove("displayedParked").remove("displayedParkedRendering").commit()
        }
        val currentProfile=ownerProfile()
        val legacyProfile=storage.getString("queueProfile",null)
        val rendering=storage.getString("rendering:"+currentProfile,null)
        if(rendering!=null)storage.edit().putString("currentRendering",rendering).commit()
        else if(legacyProfile!=null&&legacyProfile!=currentProfile)storage.edit().remove("currentRendering").commit()
        PocketSpeechCaptions.init()
        queue=try{
            val raw=storage.getString("queue:"+currentProfile,null) ?: if(legacyProfile==null||legacyProfile==currentProfile)storage.getString("queue","{}") else "{}"
            val s=JSONObject(raw!!)
            SpeechQueue(s.optJSONArray("messages")?.objects()?.map{SpokenMessage(it.getLong("id"),it.s("title"),it.s("kind"),it.s("text"),it.optBoolean("needsFetch"))}?.toMutableList()?:mutableListOf(),s.optInt("chunk"),s.optInt("position"),true,"Saved for later")
        }catch(_:Exception){SpeechQueue()}
        if(Pocket.token.isBlank())clear() else {if(legacyProfile==null)save(true);publish();if(count>0)showPaused()}
    }
    internal fun publish(){if(displayedOwner!=null){displayedRunning=!queue.paused&&queue.current!=null;displayedProblem=if(queue.paused)queue.reason else ""};count=queue.messages.size;paused=queue.paused;title=queue.current?.title.orEmpty().ifBlank{"NextComp update"};status=queue.reason}
    internal fun save(durable:Boolean=false){
        val savedQueue=displayed.parked?:queue
        val messages=JSONArray();savedQueue.messages.forEach{messages.put(JSONObject().put("id",it.id).put("title",it.title).put("kind",it.kind).put("text",it.text).put("needsFetch",it.needsFetch))}
        val profile=if(displayed.parked!=null)displayedProfile else ownerProfile()
        val encoded=JSONObject().put("messages",messages).put("chunk",savedQueue.chunkIndex).put("position",savedQueue.positionMs).toString()
        val edit=storage.edit().putString("queue",encoded).putString("queueProfile",profile).putString("queue:"+profile,encoded)
        val rendering=if(displayed.parked!=null)parkedRendering else storage.getString("currentRendering",null)
        rendering?.let{edit.putString("rendering:"+profile,it)}
        if(durable)edit.commit() else edit.apply()
        publish()
    }
    fun clear(){
        displayedOwner?.let{stopDisplayed(it)}
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
        displayed.parked?.let{parked->
            parked.enqueue(SpokenMessage(n.optLong("id"),n.s("title"),n.s("kind"),text(n),n.s("speech_pending")=="1"));save(true);return@launch
        }
        val hadSaved=queue.messages.isNotEmpty()&&queue.paused
        queue.enqueue(SpokenMessage(n.optLong("id"),n.s("title"),n.s("kind"),text(n),n.s("speech_pending")=="1"));save(true)
        if(hadSaved){showPaused();return@launch}
        if(service==null)queue.resume()
        if(service==null&&c.getSystemService(AudioManager::class.java).isMusicActive){hold("Waiting while other audio plays");return@launch}
        service?.next()?:start(c,"play")
    }}
    fun control(action:String){
        if(action=="pause"){service?.pause("Paused by you")?:hold("Paused by you");return}
        if(queue.current!=null)start(Pocket.context,"resume")
    }
    private fun start(c:Context,action:String){
        try{ContextCompat.startForegroundService(c,Intent(c,PocketSpeechService::class.java).setAction(action))}
        catch(_:RuntimeException){hold("Ready when you are")}
    }
    internal fun hold(reason:String){queue.pause(queue.positionMs,reason);save(true);showPaused()}
    internal fun notification(session:MediaSession?=null):Notification {
        val c=Pocket.context;val manager=c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("spoken-playback",PocketImmersion.label("Spoken message controls"),NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val action=if(queue.paused)"resume" else "pause"
        val intent=PendingIntent.getForegroundService(c,998,Intent(c,PocketSpeechService::class.java).setAction(action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=PendingIntent.getActivity(c,998,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val label=PocketImmersion.label(if(queue.paused)"Resume" else "Pause")
        val detail=if(queue.paused)PocketImmersion.label(queue.reason)+" · "+PocketImmersion.label("Your place is saved") else if(PocketImmersion.enabled)"${queue.messages.size} ${if(queue.messages.size==1)"messaggio" else "messaggi"} · "+PocketImmersion.label("Pause any time") else "${queue.messages.size} message${if(queue.messages.size==1)"" else "s"} · Pause any time"
        val builder=Notification.Builder(c,"spoken-playback").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PocketImmersion.label(if(queue.paused)"NextComp speech paused" else "Listening to NextComp")).setContentText(detail)
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(!queue.paused)
            .addAction(Notification.Action.Builder(if(queue.paused)android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,label,intent).build())
        if(session!=null)builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
        PocketSpeechCaptions.decorate(builder)
        return builder.build()
    }
    internal fun showPaused(){if(queue.current!=null)try{Pocket.context.getSystemService(NotificationManager::class.java).notify(998,notification())}catch(_:SecurityException){}}
}

/** Native account audio renders one private chunk; MediaPlayer preserves real pause/seek. */
class PocketSpeechService:Service(){
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var closed=false
    private var finished=false
    private var loading=false
    private var explicitPlayback=false
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){pause("Headphones disconnected")}}
    private fun speechProfile()="${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}"
    private val speakingProfile=speechProfile()
    private val speechEndpoint=Pocket.base
    private val speechCredential=Pocket.token
    private var renderedKey=""
    private var renderedText=""
    private var renderedLanguage="en"
    private val renderingStorage get()=getSharedPreferences("speech-playback",Context.MODE_PRIVATE)
    private val renderedChunks get()=SpeechText.chunks(renderedText)
    private val spokenChunk get()=renderedChunks.getOrNull(queue.chunkIndex)
    private fun renderKey(message:SpokenMessage):String {
        val source=MessageDigest.getInstance("SHA-256").digest(message.text.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        return "native-inline-v2:${PocketImmersion.enabled}:${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}:${message.id}:$source"
    }
    private fun completeSpokenChunk(id:Long,index:Int):Boolean {
        val accepted=completeRenderedSpeechChunk(queue,id,index,renderedChunks,speakingProfile,speechProfile())
        if(accepted&&queue.current?.id!=id){renderedKey="";renderedText="";renderingStorage.edit().remove("currentRendering").apply()}
        return accepted
    }
    private var synthesizing:String?=null
    private var freshlyGeneratedFile:String?=null
    private var player:MediaPlayer?=null
    private var prepared=false
    private var started=false
    private val playbackMeter=SpeechPlaybackMeter()
    private var transientPaused=false
    private var focus:AudioFocusRequest?=null
    private lateinit var session:MediaSession
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val queue get()=PocketSpeech.queue
    private val timeout=Runnable{pause("Speech stalled · Tap Resume to retry")}
    private val checkpoint=object:Runnable{override fun run(){
        if(closed||queue.paused)return
        SpeechUsage.playback(playbackMeter)
        if(prepared)player?.let{queue.positionMs=it.currentPosition;PocketSpeech.save();queue.current?.let{m->PocketSpeechCaptions.update(m.id,m.title,spokenChunk,queue.chunkIndex,renderedChunks.size,speakingProfile)}}
        handler.postDelayed(this,2000)
    }}
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){
        super.onCreate();PocketSpeech.service=this
        ContextCompat.registerReceiver(this,noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),ContextCompat.RECEIVER_NOT_EXPORTED)
        session=MediaSession(this,"NextComp speech").apply{
            setCallback(object:MediaSession.Callback(){
                override fun onPause(){pause("Paused by you")}
                override fun onStop(){if(PocketSpeech.displayedOwner!=null)PocketSpeech.displayedCompleted() else pause("Saved for later")}
                override fun onPlay(){queue.resume();PocketSpeech.save();next()}
            },handler)
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,PocketImmersion.label("NextComp spoken updates")).putString(MediaMetadata.METADATA_KEY_ARTIST,"NextComp").build())
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
        if(intent?.action in listOf("resume","displayed")){explicitPlayback=true;queue.resume()}
        if(queue.paused){pause(queue.reason);return START_NOT_STICKY}
        PocketSpeech.save();next()
        return START_NOT_STICKY
    }
    private fun armTimeout(){handler.removeCallbacks(timeout);handler.postDelayed(timeout,90000)}
    private fun audioFile():File?=spokenChunk?.let{chunk->
        val hash=MessageDigest.getInstance("SHA-256").digest(("native-account-v1:"+renderedLanguage+":"+chunk).toByteArray()).joinToString(""){"%02x".format(it)}
        File(PocketSpeech.directory,"$hash.wav")
    }
    internal fun next(){
        if(closed||finished||queue.paused)return
        if(speechProfile()!=speakingProfile){if(PocketSpeech.displayedOwner!=null)PocketSpeech.displayedCompleted() else pause("Profile changed · Your place is saved");return}
        if(player!=null){if(prepared)updateControls(PlaybackState.STATE_PLAYING);return}
        if(loading||synthesizing!=null)return
        val message=queue.current?:run{finish();return}
        if(!explicitPlayback&&!PocketSpeech.allowed(this,message.kind)){pause("Waiting for sound to be enabled");return}
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
        val sourceKey=renderKey(message)
        if(renderedKey!=sourceKey){
            loading=true;armTimeout()
            scope.launch {
                val saved=try{JSONObject(renderingStorage.getString("currentRendering","{}")!!)}catch(_:Exception){JSONObject()}
                val cached=saved.s("key")==sourceKey
                if(!cached&&(queue.chunkIndex>0||queue.positionMs>0)){queue.chunkIndex=0;queue.positionMs=0;PocketSpeech.save(true)}
                var language=if(cached)saved.s("language","en") else "en"
                var rendering=if(cached)saved.s("text",message.text) else message.text
                if(!cached&&message.kind!="displayed"&&PocketImmersion.enabled){
                    val translated=PocketImmersion.spoken("notification-speech:${message.id}",message.text,waitMs=12000)
                    if(translated!=message.text){rendering=translated;language="it"}
                }
                if(closed||finished)return@launch
                if(speechProfile()!=speakingProfile){loading=false;if(PocketSpeech.displayedOwner!=null)PocketSpeech.displayedCompleted() else pause("Profile changed · Your place is saved");return@launch}
                if(queue.current?.id!=message.id||renderKey(message)!=sourceKey){loading=false;handler.removeCallbacks(timeout);next();return@launch}
                loading=false;handler.removeCallbacks(timeout)
                renderedKey=sourceKey;renderedText=rendering;renderedLanguage=language
                renderingStorage.edit().putString("currentRendering",JSONObject().put("key",sourceKey).put("text",rendering).put("language",language).toString()).apply()
                next()
            }
            return
        }
        val text=spokenChunk
        if(text==null){completeSpokenChunk(message.id,queue.chunkIndex);PocketSpeech.save(true);next();return}
        val file=audioFile()!!
        if(file.length()>44){if(freshlyGeneratedFile!=file.path)SpeechUsage.add("cacheHits");freshlyGeneratedFile=null;play(file,message.id,queue.chunkIndex);return}
        // Only the current chunk is retained. Text for the rest stays in the private queue.
        PocketSpeech.directory.listFiles()?.forEach{it.delete()}
        if(!acquireSpeechFocus())return
        val identity="${message.id}/${queue.chunkIndex}";synthesizing=identity;armTimeout()
        scope.launch {
            try {
                val bytes=NativeSpeechRenderer(this@PocketSpeechService,speechEndpoint,speechCredential).render(text)
                if(closed||finished||queue.paused||synthesizing!=identity||speechProfile()!=speakingProfile)return@launch
                val target=audioFile()?:return@launch
                withContext(Dispatchers.IO){val temp=File(PocketSpeech.directory,"pending-native");temp.writeBytes(bytes);check(temp.renameTo(target)){"Could not save native speech"}}
                freshlyGeneratedFile=target.path
                synthesizing=null;handler.removeCallbacks(timeout);next()
            }catch(e:CancellationException){throw e}catch(_:Exception){if(!closed&&!finished)pause("Native speech unavailable · Tap Resume to retry")}
        }
    }
    private fun acquireSpeechFocus():Boolean {
        if(!explicitPlayback&&getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)==0){pause("Waiting for media volume");return false}
        if(focus==null){
            if(!explicitPlayback&&getSystemService(AudioManager::class.java).isMusicActive){pause("Waiting while other audio plays");return false}
            val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener({change->when(change){
                    AudioManager.AUDIOFOCUS_GAIN->{player?.setVolume(1f,1f);resumeTransient()}
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK->player?.setVolume(.25f,.25f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT->if(started)pauseTransient() else pause("Waiting for other audio")
                    AudioManager.AUDIOFOCUS_LOSS->pause("Paused for other audio")
                }},handler).build()
            if(getSystemService(AudioManager::class.java).requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){pause("Waiting for other audio to finish");return false}
            focus=request
        }
        return true
    }
    private fun play(file:File,id:Long,index:Int){
        if(!acquireSpeechFocus())return
        try{
            val media=MediaPlayer();player=media
            media.setAudioAttributes(attributes);media.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);media.setDataSource(file.path)
            media.setOnErrorListener{_,_,_->file.delete();pause("Playback interrupted · Tap Resume to retry");true}
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
                SpeechUsage.playback(playbackMeter,stop=true)
                Log.i("PocketSpeech","Completed audio $id/$index")
                handler.removeCallbacks(checkpoint);handler.removeCallbacks(timeout);media.release();player=null;prepared=false;started=false
                if(completeSpokenChunk(id,index)){PocketSpeech.save(true);file.delete()}
                next()
            }
            armTimeout();media.prepareAsync()
        }catch(_:Exception){file.delete();pause("Playback interrupted · Tap Resume to retry")}
    }
    private fun startPlayer(media:MediaPlayer,id:Long,index:Int){
        handler.removeCallbacks(timeout);media.start();started=true;playbackMeter.start(SystemClock.elapsedRealtime())
        queue.current?.let{PocketSpeechCaptions.update(it.id,it.title,spokenChunk,queue.chunkIndex,renderedChunks.size,speakingProfile)}
        handler.postDelayed(timeout,(media.duration-media.currentPosition).toLong().coerceAtLeast(0)+15000)
        Log.i("PocketSpeech","Playing audio $id/$index from ${media.currentPosition} ms")
        updateControls(PlaybackState.STATE_PLAYING);handler.removeCallbacks(checkpoint);handler.post(checkpoint)
    }
    private fun pauseTransient(){
        if(!started||transientPaused)return
        player?.let{try{it.pause();queue.positionMs=it.currentPosition}catch(_:IllegalStateException){return}}
        SpeechUsage.playback(playbackMeter,stop=true)
        transientPaused=true;handler.removeCallbacks(timeout);handler.removeCallbacks(checkpoint);PocketSpeech.save(true);updateControls(PlaybackState.STATE_PAUSED)
    }
    private fun resumeTransient(){
        if(!transientPaused)return
        val media=player?:return
        try{
            media.start();transientPaused=false;playbackMeter.start(SystemClock.elapsedRealtime())
            handler.postDelayed(timeout,(media.duration-media.currentPosition).toLong().coerceAtLeast(0)+15000)
            updateControls(PlaybackState.STATE_PLAYING);handler.removeCallbacks(checkpoint);handler.post(checkpoint)
        }catch(_:IllegalStateException){pause("Playback interrupted · Tap Resume to retry")}
    }
    internal fun refreshCaptionNotification(){getSystemService(NotificationManager::class.java).notify(998,PocketSpeech.notification(session))}
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
        finished=true;releasePlayback();stopForeground(STOP_FOREGROUND_DETACH);PocketSpeechCaptions.pause(speakingProfile);PocketSpeech.showPaused();stopSelf()
    }
    internal fun discard(){finished=true;releasePlayback();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun finish(){if(PocketSpeech.displayedOwner!=null){PocketSpeech.displayedCompleted();return};finished=true;releasePlayback();queue.positionMs=0;PocketSpeech.save(true);PocketSpeech.directory.deleteRecursively();stopForeground(STOP_FOREGROUND_REMOVE);PocketSpeechCaptions.complete(speakingProfile);stopSelf()}
    private fun releasePlayback(){
        SpeechUsage.playback(playbackMeter,stop=true)
        handler.removeCallbacksAndMessages(null);scope.cancel();player?.release();player=null;prepared=false;started=false;transientPaused=false;
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
