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
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    internal var sessions=SessionSpeechQueues()
    var sources by mutableStateOf<List<Pair<String?,Pair<String,Int>>>>(emptyList());private set
    var activeSource by mutableStateOf<String?>(null);private set
    private val displayed=DisplayedSpeechSession()
    var displayedOwner by mutableStateOf<String?>(null);private set
    var displayedRunning by mutableStateOf(false);private set
    var displayedProblem by mutableStateOf("");private set
    var displayedText by mutableStateOf("");private set
    var displayedTitle by mutableStateOf("");private set
    private var displayedGeneration=0L
    private var parkedRendering:String?=null
    private var displayedProfile=""
    internal fun ownerProfile()=MessageDigest.getInstance("SHA-256").digest("${Pocket.local}:${Pocket.base}:${Pocket.token}".toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
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
    var currentMessageId by mutableStateOf<Long?>(null);private set
    var activeCount by mutableIntStateOf(0);private set
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
            SpeechQueue(s.optJSONArray("messages")?.objects()?.map{SpokenMessage(it.getLong("id"),it.s("title"),it.s("kind"),it.s("text"),it.optBoolean("needsFetch"),it.s("threadId").takeIf{value->value.isNotBlank()}?:PocketNotificationTitles.threadForId(it.optLong("id")))}?.toMutableList()?:mutableListOf(),s.optInt("chunk"),s.optInt("position"),true,"Saved for later")
        }catch(_:Exception){SpeechQueue()}
        sessions=SessionSpeechQueues()
        try{
            val grouped=JSONObject(storage.getString("sessions:"+currentProfile,"{}")!!)
            grouped.optJSONArray("groups")?.objects()?.forEach{group->
                val source=group.s("threadId").takeIf{it.isNotBlank()}
                val messages=group.optJSONArray("messages")?.objects().orEmpty().map{SpokenMessage(it.optLong("id"),it.s("title"),it.s("kind"),it.s("text"),it.optBoolean("needsFetch"),it.s("threadId").takeIf{value->value.isNotBlank()})}.toMutableList()
                sessions.queues[source]=SpeechQueue(messages,group.optInt("chunk"),group.optInt("position"),true,"Saved for later")
            }
            if(sessions.queues.isEmpty()){
                queue.messages.forEach{sessions.enqueue(it)}
                sessions.activeSource=queue.current?.sourceKey
                sessions.current.chunkIndex=queue.chunkIndex;sessions.current.positionMs=queue.positionMs;sessions.current.pause(queue.positionMs,"Saved for later")
            }else sessions.activeSource=grouped.s("activeSource").takeIf{it.isNotBlank()}
            sessions.explicitFocus=grouped.optBoolean("explicitFocus")
            queue=sessions.current
        }catch(_:Exception){sessions.queues[null]=queue}
        if(sessions.count>0&&PocketSpeechCaptions.state.id!=queue.current?.id)PocketSpeechCaptions.dismiss()
        if(Pocket.token.isBlank())clearAll() else {if(legacyProfile==null)save(true);publish();if(count>0)showPaused()}
    }
    internal fun publish(){currentMessageId=queue.current?.id;activeCount=queue.messages.size;if(displayedOwner!=null){displayedRunning=!queue.paused&&queue.current!=null;displayedProblem=if(queue.paused)queue.reason else ""};sources=sessions.queues.filterValues{it.messages.isNotEmpty()}.map{(source,q)->source to ((if(source=="@audio-preview")"Audio preview" else if(source==null)"Unknown session" else PocketNotificationTitles.title(source,q.current?.title.orEmpty().ifBlank{"Session"})) to q.messages.size)};activeSource=sessions.activeSource;count=if(displayedOwner!=null)queue.messages.size else sessions.count;paused=queue.paused;title=if(displayedOwner!=null)displayedTitle else queue.current?.let{m->if(m.kind=="preview")"Audio preview" else m.threadId?.let{PocketNotificationTitles.title(it,m.title)}?:"Unknown session"}?:"Choose a speech session";status=queue.reason}
    internal fun save(durable:Boolean=false){
        val savedQueue=displayed.parked?:queue
        val messages=JSONArray();savedQueue.messages.forEach{messages.put(JSONObject().put("id",it.id).put("title",it.title).put("kind",it.kind).put("text",it.text).put("needsFetch",it.needsFetch).put("threadId",it.threadId))}
        val profile=if(displayed.parked!=null)displayedProfile else ownerProfile()
        val encoded=JSONObject().put("messages",messages).put("chunk",savedQueue.chunkIndex).put("position",savedQueue.positionMs).toString()
        val groups=JSONArray();sessions.queues.forEach{(source,q)->val rows=JSONArray();q.messages.forEach{m->rows.put(JSONObject().put("id",m.id).put("title",m.title).put("kind",m.kind).put("text",m.text).put("needsFetch",m.needsFetch).put("threadId",m.threadId))};groups.put(JSONObject().put("threadId",source).put("messages",rows).put("chunk",q.chunkIndex).put("position",q.positionMs))}
        val grouped=JSONObject().put("activeSource",sessions.activeSource).put("explicitFocus",sessions.explicitFocus).put("groups",groups).toString()
        val edit=storage.edit().putString("sessions:"+profile,grouped).putString("queue",encoded).putString("queueProfile",profile).putString("queue:"+profile,encoded)
        val rendering=if(displayed.parked!=null)parkedRendering else storage.getString("currentRendering",null)
        rendering?.let{edit.putString("rendering:"+profile,it)}
        if(durable)edit.commit() else edit.apply()
        publish()
    }
    /** Called while the departing profile is still active; late service callbacks cannot save into the next profile. */
    fun profileLeaving(){
        ++selectionGeneration;++displayedGeneration
        displayedOwner?.let{stopDisplayed(it)}
        val old=service
        old?.pause("Profile changed · Your place is saved")
        if(service===old)service=null
        queue.pause(queue.positionMs,"Saved for later");save(true)
    }
    private fun heard(profile:String):CompletedSpeechLedger {
        val ids=try{JSONArray(storage.getString("heard:"+profile,"[]")!!).let{a->(0 until a.length()).map{a.optLong(it)}}}catch(_:Exception){emptyList()}
        return CompletedSpeechLedger(ids)
    }
    internal fun completedNotification(id:Long,profile:String){
        val ledger=heard(profile);ledger.completed(id);storage.edit().putString("heard:"+profile,JSONArray(ledger.snapshot()).toString()).commit()
    }
    fun clearAll(){
        displayedOwner?.let{stopDisplayed(it)}
        service?.discard();sessions=SessionSpeechQueues();queue=sessions.current;val remembered=storage.all.filterKeys{it.startsWith("heard:")};val edit=storage.edit().clear();remembered.forEach{(key,value)->if(value is String)edit.putString(key,value)};edit.commit();directory.deleteRecursively()
        Pocket.context.getSystemService(NotificationManager::class.java).cancel(998);publish()
    }
    fun clear(){
        if(displayedOwner!=null){stopDisplayed(displayedOwner!!);return}
        service?.discard();sessions.clearActive();queue=sessions.current;queue.pause(0,"Choose a speech session");save(true)
        PocketSpeechCaptions.dismiss();Pocket.context.getSystemService(NotificationManager::class.java).cancel(998)
    }
    private var selectionGeneration=0L
    fun selectSource(source:String?,explicit:Boolean=true){
        val generation=++selectionGeneration
        if(displayedOwner!=null)stopDisplayed(displayedOwner!!)
        val old=service;old?.pause("Saved for later")
        val rendering=storage.getString("currentRendering",null)
        rendering?.let{storage.edit().putString("source-rendering:"+ownerProfile()+":"+(sessions.activeSource?:"unknown"),it).commit()}
        queue=sessions.select(source,explicit);val saved=storage.getString("source-rendering:"+ownerProfile()+":"+(source?:"unknown"),null)
        val edit=storage.edit();if(saved==null)edit.remove("currentRendering")else edit.putString("currentRendering",saved);edit.commit()
        PocketSpeechCaptions.dismiss();save(true)
        Pocket.scope.launch{var waits=0;while(service===old&&old!=null&&waits++<150)kotlinx.coroutines.delay(20);if(generation!=selectionGeneration||service===old&&old!=null)return@launch;if(queue.current!=null)start(Pocket.context,"resume")}
    }
    internal fun sourceFinished(){
        if(sessions.explicitFocus||PocketVoice.foreground&&Pocket.selected!=null)return
        val urgent=sessions.queues.entries.firstOrNull{(_,q)->q.messages.any{it.kind in listOf("question","approval","input_required")}}?:return
        selectSource(urgent.key,explicit=false)
    }
    internal fun trimCache(){
        val files=directory.listFiles()?.filter{it.extension=="wav"}?.sortedByDescending{it.lastModified()}.orEmpty()
        var bytes=0L;files.forEachIndexed{i,file->bytes+=file.length();if(i>=16||bytes>32L*1024*1024)file.delete()}
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
    fun request(c:Context,n:JSONObject){Pocket.scope.launch{
        if(n.has("_environment")&&n.s("_environment")!=Pocket.environmentId)return@launch
        if(!speechOriginMatches(if(n.has("_local"))n.optBoolean("_local")else null,Pocket.local))return@launch
        if(n.s("kind")=="coordinator_report")return@launch
        if(!allowed(c,n.s("kind")))return@launch
        val preview=n.optBoolean("_speechPreview")
        val incoming=SpokenMessage(if(preview)-n.optLong("id").coerceAtLeast(1)else n.optLong("id"),if(preview)"Audio preview" else n.s("title"),if(preview)"preview" else n.s("kind"),text(n),n.s("speech_pending")=="1",n.s("thread_id").ifBlank{n.s("threadId")}.takeIf{it.isNotBlank()})
        if(heard(ownerProfile()).contains(incoming.id))return@launch
        val wasEmpty=sessions.count==0
        sessions.enqueue(incoming)
        if(preview&&displayed.parked==null&&!PocketVoice.active){selectSource(incoming.sourceKey);return@launch}
        if(displayed.parked!=null||PocketVoice.active){save(true);return@launch}
        val focused=Pocket.selected.takeIf{PocketVoice.foreground}
        if((wasEmpty||queue.current==null)&&!sessions.explicitFocus){
            if(focused==null||focused==incoming.threadId){sessions.activeSource=incoming.threadId;queue=sessions.current}else{save(true);return@launch}
        }
        val hadSaved=queue.messages.isNotEmpty()&&queue.paused
        save(true)
        if(incoming.threadId!=sessions.activeSource||hadSaved){showPaused();return@launch}
        if(service==null)queue.resume()
        if(service==null&&c.getSystemService(AudioManager::class.java).isMusicActive){hold("Waiting while other audio plays");return@launch}
        service?.next()?:start(c,"play")
    }}
    fun control(action:String){
        if(displayedOwner==null)sessions.explicitFocus=true
        if(action=="pause"){service?.pause("Paused by you")?:hold("Paused by you");return}
        if(queue.current!=null)start(Pocket.context,"resume")
    }
    private fun start(c:Context,action:String){
        notificationSpeechHoldReason(PocketVoice.active)?.let{hold(it);return}
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
    private val ledgerProfile=PocketSpeech.ownerProfile()
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
    private fun completeSpokenChunk(id:Long,index:Int,played:Boolean=false):Boolean {
        val completedMessage=queue.current
        val accepted=completeRenderedSpeechChunk(queue,id,index,renderedChunks,speakingProfile,speechProfile())
        if(accepted&&queue.current?.id!=id){
            if(played&&completedMessage!=null&&completedMessage.id>0&&completedMessage.kind!="displayed"){
                PocketSpeech.completedNotification(completedMessage.id,ledgerProfile)
                if(completedMessage.threadId!=null)acknowledgeSpokenNotification(completedMessage)
            }
            renderedKey="";renderedText="";renderingStorage.edit().remove("currentRendering").apply()}
        return accepted
    }
    private fun acknowledgeSpokenNotification(message:SpokenMessage){
        val endpoint=speechEndpoint;val credential=speechCredential
        Pocket.scope.launch{try{
            withContext(Dispatchers.IO){
                val request=Request.Builder().url(endpoint+"/api/notifications/${message.id}/presented").header("Authorization","Bearer $credential").post(JSONObject().put("threadId",message.threadId).toString().toRequestBody("application/json".toMediaType())).build()
                Pocket.http.newCall(request).execute().use{it.body?.close()}
            }
        }catch(e:Exception){if(e is CancellationException)throw e}}
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
        notificationSpeechHoldReason(PocketVoice.active)?.let{pause(it);return START_NOT_STICKY}
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
    private val checkedAttention=mutableSetOf<Long>()
    private suspend fun notificationApi(path:String):JSONObject=withContext(Dispatchers.IO){
        val request=Request.Builder().url(speechEndpoint+path).header("Authorization","Bearer $speechCredential").build()
        Pocket.http.newCall(request).execute().use{response->
            check(response.isSuccessful){"Notification unavailable"}
            JSONObject(response.body?.string()?:"{}")
        }
    }
    internal fun next(){
        if(closed||finished||queue.paused)return
        notificationSpeechHoldReason(PocketVoice.active)?.let{pause(it);return}
        if(speechProfile()!=speakingProfile){if(PocketSpeech.displayedOwner!=null)PocketSpeech.displayedCompleted() else pause("Profile changed · Your place is saved");return}
        if(player!=null){if(prepared)updateControls(PlaybackState.STATE_PLAYING);return}
        if(loading||synthesizing!=null)return
        val message=queue.current?:run{finish();return}
        if(!explicitPlayback&&!PocketSpeech.allowed(this,message.kind)){pause("Waiting for sound to be enabled");return}
        updateControls(PlaybackState.STATE_BUFFERING)
        if(message.kind in listOf("question","approval","input_required")&&message.id !in checkedAttention){
            loading=true;armTimeout()
            scope.launch{
                val attention=try{notificationApi("/api/notifications/${message.id}/attention")}catch(e:CancellationException){throw e}catch(_:Exception){null}
                if(closed||finished)return@launch
                loading=false;handler.removeCallbacks(timeout)
                if(speechProfile()!=speakingProfile||queue.current?.id!=message.id)return@launch
                if(attention==null){pause("Reconnect to check whether this question still needs you");return@launch}
                checkedAttention.add(message.id)
                if(attention.has("needsAttention")&&!attention.optBoolean("needsAttention")){
                    queue.messages.removeAt(0);queue.chunkIndex=0;queue.positionMs=0;renderedKey="";renderedText="";PocketSpeech.save(true)
                }
                next()
            }
            return
        }
        if(message.needsFetch){
            loading=true;armTimeout()
            scope.launch{
                val full=try{notificationApi("/api/notifications/${message.id}").getJSONObject("notification")}catch(e:CancellationException){throw e}catch(_:Exception){null}
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
        if(file.length()>44){file.setLastModified(System.currentTimeMillis());if(freshlyGeneratedFile!=file.path)SpeechUsage.add("cacheHits");freshlyGeneratedFile=null;play(file,message.id,queue.chunkIndex);return}
        // Only the current chunk is retained. Text for the rest stays in the private queue.
        PocketSpeech.trimCache()
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
                if(completeSpokenChunk(id,index,played=true)){PocketSpeech.save(true);file.delete()}
                next()
            }
            armTimeout();media.prepareAsync()
        }catch(_:Exception){file.delete();pause("Playback interrupted · Tap Resume to retry")}
    }
    private fun startPlayer(media:MediaPlayer,id:Long,index:Int){
        notificationSpeechHoldReason(PocketVoice.active)?.let{pause(it);return}
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
        notificationSpeechHoldReason(PocketVoice.active)?.let{pause(it);return}
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
    private fun finish(){if(PocketSpeech.displayedOwner!=null){PocketSpeech.displayedCompleted();return};finished=true;releasePlayback();queue.positionMs=0;queue.pause(0,"Choose a speech session");PocketSpeech.save(true);PocketSpeech.trimCache();stopForeground(STOP_FOREGROUND_REMOVE);PocketSpeechCaptions.complete(speakingProfile);stopSelf();PocketSpeech.sourceFinished()}
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
