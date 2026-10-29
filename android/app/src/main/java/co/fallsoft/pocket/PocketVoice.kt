package co.fallsoft.pocket

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.view.KeyEvent
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A manual turn has one durable ID from recording through delivery and speech. */
object PocketVoice {
    var captureBusy by mutableStateOf(false); internal set
    internal var latestRecordingKey:String?=null
    var deliveryStatus by mutableStateOf("");private set
    internal fun captureStarted(key:String){latestRecordingKey=key;deliveryStatus=""}
    internal fun captureQueued(key:String){latestRecordingKey=key;deliveryStatus="Recording saved"}
    internal fun recordingProgress(turn:VoiceTurnStore.Turn,receipt:JSONObject){
        service?.offerResponse(turn,receipt,complete=false)
        if(turn.key!=latestRecordingKey||Pocket.environmentId!=turn.origin.environment||
            backendOwner(Pocket.base.trimEnd('/'),Pocket.token)!=turn.origin.pairing)return
        deliveryStatus=when(receipt.optString("state")){
            "uploading"->"Uploading recording";"queued"->if(receipt.optString("phase")=="accepted")"Request queued" else "Recording queued";"transcribing"->"Transcribing"
            "submitting"->"Submitting request";"thinking","running"->"Working";"completed"->"Response ready"
            "unknown"->"Checking original delivery";"blocked","failed"->receipt.optString("error","Recording needs attention")
            else->"Recording saved"
        }
    }
    internal fun deliveredRecording(turn:VoiceTurnStore.Turn,receipt:JSONObject){
        service?.offerResponse(turn,receipt,complete=true)
        if(turn.key!=latestRecordingKey||!foreground||captureBusy||Pocket.environmentId!=turn.origin.environment||
            backendOwner(Pocket.base.trimEnd('/'),Pocket.token)!=turn.origin.pairing)return
        val destination=JSONObject(turn.origin.destination)
        val current=currentForegroundCaptureTarget()
        if(destination.optJSONObject("room")?.toString()!=current.roomDestination ||
            destination.optString("threadId").takeIf{it.isNotBlank()}!=current.threadId)return
        deliveryStatus=""
        heard=receipt.optString("transcript");response=receipt.optString("response")
        val message=CoordinatorMessage(turn.id,heard,response)
        val existing=messages.indexOfFirst{it.id==turn.id}
        if(existing<0)messages.add(message)else messages[existing]=message
        val actions=receipt.optJSONArray("actions")?.objects().orEmpty()
        routes[turn.id]=routeReceipts(actions,turn.id)
        if(targetRoom==null&&inPlace&&PocketCoordinator.visible)PocketCoordinator.loadHistory()
        val audio=Pocket.context.getSystemService(android.media.AudioManager::class.java)
        // Active voice responses use the single live output owner, never a second buffered player.
    }
    var active by mutableStateOf(false); internal set
    var inPlace by mutableStateOf(false);private set
    var launching by mutableStateOf(false);internal set
    var recordingStartedAt by mutableLongStateOf(0);internal set
    var captureLevel by mutableFloatStateOf(0f);internal set
    val captureFeedbackVisible get()=captureFeedbackVisible(active,launching,state,problem)||active&&deliveryStatus.isNotBlank()
    fun showInPlace(){inPlace=true}
    fun captureFailure(message:String,inPlace:Boolean=true){this.inPlace=inPlace;problem=message}
    var state by mutableStateOf("Ready"); internal set
    var heard by mutableStateOf(""); internal set
    var response by mutableStateOf(""); internal set
    var problem by mutableStateOf(""); internal set
    var keysEnabled by mutableStateOf(false); internal set
    internal val captureKeys=CaptureKeyGesture()
    val messages=mutableStateListOf<CoordinatorMessage>()
    internal val presentationTurns=mutableStateMapOf<String,CoordinatorPresentation>()
    fun presented(id:String){if(foreground)presentationTurns[id]?.let{service?.presented(it)}}
    internal var correctionOf:CoordinatorRoute?=null
    internal val routes=mutableStateMapOf<String,List<CoordinatorRoute>>()
    var historyEarlier by mutableStateOf(false);internal set
    var historyLoading by mutableStateOf(false);internal set
    var historyProblem by mutableStateOf("");internal set
    fun olderHistory(){service?.loadHistory(true)}
    var foreground=false
    var service:PocketVoiceService?=null
    var targetThread by mutableStateOf<String?>(null);internal set
    internal var targetRoom:JSONObject?=null
    internal var targetSourceLabel by mutableStateOf<String?>(null)
    internal fun startRequest()=JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",targetThread).apply{targetRoom?.let{put("room",JSONObject(it.toString()))}}
    internal fun savedTurnMatchesCurrentEnvironment()=voicePendingMatchesEnvironment(
        Pocket.prefs.getString("voicePendingId",null)!=null,
        Pocket.prefs.getString("voicePendingEnvironment",null),Pocket.prefs.getString("voicePendingOwner",null),
        Pocket.environmentId,backendOwner(Pocket.base,Pocket.token))
    private var resolvingSavedOwner=false
    private fun resolveLegacySavedOwner(inPlace:Boolean){
        if(resolvingSavedOwner)return
        val id=Pocket.prefs.getString("voicePendingId",null)?:return
        val destinations=Pocket.environmentProfiles.all().mapNotNull{Pocket.captureEnvironment(it.id)}
        if(destinations.isEmpty()||destinations.size>16){captureFailure("Select the original paired environment to resolve this saved recording.",inPlace);return}
        resolvingSavedOwner=true;this.inPlace=inPlace;problem="";state="Checking saved turn"
        Pocket.scope.launch{
            try{
                val found=mutableListOf<EnvironmentRequest>();var unavailable=false
                for(destination in destinations){
                    try{
                        val result=withTimeout(4000){Pocket.apiEnvironment(destination,"/api/voice/turns/${android.net.Uri.encode(id)}")}
                        if(result.s("id")==id&&result.s("state") in setOf("completed","failed","unknown","transcribing","thinking","running"))found.add(destination)else unavailable=true
                    }catch(e:CancellationException){if(e !is TimeoutCancellationException)throw e;unavailable=true}
                    catch(e:Exception){if(e !is PocketApiException||e.status!=404)unavailable=true}
                }
                if(Pocket.prefs.getString("voicePendingId",null)!=id)return@launch
                val owner=uniqueVoiceReceiptOwner(found.map{it.environment.id},unavailable)
                val destination=found.singleOrNull()?.takeIf{it.environment.id==owner&&Pocket.captureEnvironment(owner!!)?.matches(it)==true}
                if(destination==null){captureFailure(if(unavailable)"Some environments could not confirm the saved turn. It remains saved; check again when connected." else "No unique environment confirmed this recording. It remains saved and has not been resent.",inPlace);return@launch}
                if(!Pocket.prefs.edit().putString("voicePendingEnvironment",destination.environment.id).putString("voicePendingOwner",backendOwner(destination.endpoint,destination.token)).commit()){captureFailure("Could not save recording ownership. The recording is kept.",inPlace);return@launch}
                captureFailure("Saved turn found in ${destination.environment.name}. Check it there; the recording has not been resent.",inPlace)
            }finally{resolvingSavedOwner=false;if(Pocket.prefs.getString("voicePendingId",null)==id)state="Ready"}
        }
    }
    internal var pendingLaunchId:String?=null;private set
    fun start(c:Context,threadId:String?=null,recordImmediately:Boolean=true,inPlace:Boolean=false,checkSaved:Boolean=false,roomDestination:JSONObject?=null,sourceLabel:String?=null){
        if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){problem="Allow microphone access to use voice.";return}
        if(checkSaved&&Pocket.prefs.getString("voicePendingId",null)!=null&&Pocket.prefs.getString("voicePendingEnvironment",null).isNullOrBlank()){resolveLegacySavedOwner(inPlace);return}
        if(checkSaved&&!savedTurnMatchesCurrentEnvironment()){captureFailure("Switch to the recording’s original environment and pairing before checking it. Legacy recordings without verified ownership remain saved; they have not been resent.",inPlace);return}
        if(active){
            if(checkSaved){service?.retry();return}
            service?.beginCapture(threadId,roomDestination,sourceLabel);return
        }
        if(launching)return
        val launchId=UUID.randomUUID().toString();pendingLaunchId=launchId
        this.inPlace=inPlace;targetSourceLabel=if(checkSaved)Pocket.prefs.getString("voicePendingSourceLabel",null)?:Pocket.prefs.getString("voicePendingRoomLabel",null)?:"Saved recording" else captureSourceLabel(threadId,roomDestination?.toString(),sourceLabel);targetRoom=(if(checkSaved)Pocket.prefs.getString("voicePendingRoom",null)?.let{JSONObject(it)} else roomDestination)?.let{JSONObject(it.toString())};launching=true;state="Starting voice";targetThread=if(checkSaved)Pocket.prefs.getString("voicePendingTarget",null)?.takeUnless{it=="coordinator"} else threadId;problem="";try{ContextCompat.startForegroundService(c,Intent(c,PocketVoiceService::class.java).setAction("start").putExtra("launchId",launchId).putExtra("recordImmediately",recordImmediately).putExtra("checkSaved",checkSaved).putExtra("captureEnvironment",Pocket.environmentId).putExtra("captureEndpoint",Pocket.base.trimEnd('/')).putExtra("capturePairing",backendOwner(Pocket.base.trimEnd('/'),Pocket.token)))}catch(e:RuntimeException){launching=false;state="Ready";val reason=(e.message?:e.javaClass.simpleName).replace('\n',' ').let{if(Pocket.token.isNotBlank())it.replace(Pocket.token,"[credential]")else it}.take(180);captureFailure("Android could not start recording: $reason",inPlace)}
    }
    fun stop(){pendingLaunchId=null;service?.leave();launching=false;problem=""}
    fun record(){service?.toggleRecord()}
    fun playback(){service?.togglePlayback()}
    fun sendText(text:String):Boolean=service?.sendText(text)?:false.also{problem="Voice is not ready to send text. Your draft is kept."}
    fun retry(){service?.retry()}
    fun key(event:KeyEvent):Boolean{
        if(event.keyCode !in listOf(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_VOLUME_UP))return false
        if(event.action==KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
        val destination=currentForegroundCaptureTarget()
        if(!foregroundCaptureKeys(foreground,Pocket.token.isNotBlank()&&!Pocket.pairingMode,BackendNavigation.teamSelected(),destination.eligible,nativeRoom=destination.roomDestination!=null,recording=captureBusy)||!active)return false
        if(event.action==KeyEvent.ACTION_DOWN&&captureKeys.down(event.keyCode,event.repeatCount))Pocket.scope.launch{CaptureDestination.capture(destination.threadId,destination.roomDestination,destination.sourceLabel,inPlace=inPlace)?.start(Pocket.context)}
        return true
    }
    fun notification(n:JSONObject){service?.enqueueUpdate(n)}
}

class PocketVoiceService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val http=Pocket.http.newBuilder().readTimeout(70,TimeUnit.SECONDS).callTimeout(85,TimeUnit.SECONDS).build()
    private val audio get()=getSystemService(AudioManager::class.java)
    private var recorder:AudioRecord?=null
    private var recording:Job?=null
    private var recordingProblem:String?=null
    @Volatile private var stopRecording=false
    private var coordinatorReady=false
    private var historyBefore:Long?=null
    private var work:Job?=null
    private var player:MediaPlayer?=null
    private var playing=false
    private var starting=false
    private val outputs=CoordinatorOutputQueue()
    private var outputWork:Job?=null
    private var listeningWork:Job?=null
    private var nativeConnecting:Deferred<Unit>?=null
    private val completedResponses=mutableMapOf<String,Int>()
    private val responseSegments=mutableMapOf<String,MutableSet<String>>()
    private var activeSpeechVoiceTurnId:String?=null
    private var nativeAudio:NativeVoiceAudio?=null
    private val nativeUsage=SpeechPlaybackMeter()
    private fun checkpointNativeUsage(stop:Boolean=false){SpeechUsage.add("voiceConnectionMs",if(stop)nativeUsage.stop(SystemClock.elapsedRealtime()) else nativeUsage.sample(SystemClock.elapsedRealtime()))}
    private var nativeId:String?=null
    private var nativeReady:CompletableDeferred<Unit>?=null
    private var nativeHeartbeat:Job?=null
    private var idleDisconnect:Job?=null
    private var transportGeneration=0L
    private var completedSpeechTurnId:String?=null
    private var nativeSpeaking=false
    private var nativeRendering=false
    private var nativePaused=false
    private var nativeCacheReady=false
    private var spokenOwner:String?=null
    private var nativeResumeRequested=false
    private var nativeAudioBeganAt=0L
    private var nativeAudioCacheStartedAt=0L
    private var captureGeneration=0L
    private var captureTurnId:String?=null
    private var captureConnection:String?=null
    private var captureStreaming=false
    private var captureStreamFailed=false
    private var captureStreamJob:Job?=null
    private val capturePrefix=mutableListOf<ByteArray>()
    private var nativePausedPositionMs=0
    private var speechGeneration=0L
    private var local=false
    private var endpoint=""
    private var credential=""
    private var focus:AudioFocusRequest?=null
    private var lock:PowerManager.WakeLock?=null
    private var afterSpeech:(()->Unit)?=null
    private data class NativeCaption(val id:Long,val title:String,val text:String,val profile:String)
    private var caption:NativeCaption?=null
    private var captionProfile=""
    private fun captionStarted(){caption?.let{PocketSpeechCaptions.update(it.id,it.title,it.text,0,1,it.profile);refreshCaptionNotification()}}
    private fun captionFinished(){caption?.let{PocketSpeechCaptions.complete(it.profile,retain=false);refreshCaptionNotification()}}
    private fun voiceNotification():Notification {
        val open=PendingIntent.getActivity(this,1120,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1121,Intent(this,PocketVoiceService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=NotificationCompat.Builder(this,"voice-mode").setSmallIcon(R.drawable.ic_notification).setContentTitle(PocketImmersion.label("NextComp voice is on")).setContentText(PocketImmersion.label("In NextComp: either volume button starts or stops recording")).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0,PocketImmersion.label("End voice"),stop)
        return PocketSpeechCaptions.decorate(b).build()
    }
    internal fun refreshCaptionNotification(){try{getSystemService(NotificationManager::class.java).notify(1120,voiceNotification())}catch(_:SecurityException){}}
    private var speechFiles=emptyList<File>()
    private var speechIndex=0
    private val updates=ArrayDeque<JSONObject>()
    private val feedback=VoiceFeedback.open()
    private val pendingFile get()=File(filesDir,"voice-pending.wav")
    private val spokenFile get()=File(filesDir,"voice-spoken.wav")
    private val turnId get()=Pocket.prefs.getString("voicePendingId",null)
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate();PocketVoice.service=this
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("voice-mode",PocketImmersion.label("Voice mode"),NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val n=voiceNotification()
        if(Build.VERSION.SDK_INT>=29)startForeground(1120,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1120,n)
        lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NextComp:Voice").apply{setReferenceCounted(false)}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent==null){stopSelf();return START_NOT_STICKY}
        if(intent.action=="stop"){leave();return START_NOT_STICKY}
        // Service delivery may arrive after the user cancelled the launch. A stale
        // Android start command must never reacquire the microphone.
        if(intent.action=="start" && (intent.getStringExtra("launchId")==null || intent.getStringExtra("launchId")!=PocketVoice.pendingLaunchId)){
            if(!PocketVoice.active)stopSelf(startId)
            return START_NOT_STICKY
        }
        if(intent?.action=="start"&&intent.getBooleanExtra("recordImmediately",true)&&!intent.getBooleanExtra("checkSaved",false)&&
            (intent.getStringExtra("captureEnvironment")!=Pocket.environmentId || intent.getStringExtra("captureEndpoint")!=Pocket.base.trimEnd('/') ||
             intent.getStringExtra("capturePairing")!=backendOwner(Pocket.base.trimEnd('/'),Pocket.token))){
            PocketVoice.launching=false;PocketVoice.captureFailure("Environment changed before microphone start. Nothing was recorded or sent.");stopSelf();return START_NOT_STICKY
        }
        if(intent?.getBooleanExtra("checkSaved",false)==true&&!PocketVoice.savedTurnMatchesCurrentEnvironment()){PocketVoice.captureFailure("Saved recording belongs to another environment or pairing. Switch back before checking it; it remains saved.");leave();return START_NOT_STICKY}
        if(!PocketVoice.active){PocketVoice.launching=false;PocketVoice.active=true;local=if(intent?.getBooleanExtra("checkSaved",false)==true)Pocket.prefs.getBoolean("voicePendingLocal",Pocket.local) else Pocket.local;captureProfile();startCoordinatorListening();loadHistory();PocketSpeech.control("pause");PocketLive.start();if(intent?.getBooleanExtra("checkSaved",false)==true&&turnId!=null)boot()else if(intent?.getBooleanExtra("recordImmediately",true)!=false)toggleRecord()else state("Ready")}
        else if(intent?.action=="start"&&intent.getBooleanExtra("recordImmediately",true))toggleRecord()
        return START_NOT_STICKY
    }
    fun loadHistory(older:Boolean=false){
        if(PocketVoice.historyLoading)return
        PocketVoice.historyLoading=true;PocketVoice.historyProblem=""
        if(!older){PocketVoice.messages.clear();PocketVoice.routes.clear();PocketVoice.presentationTurns.clear();PocketVoice.historyEarlier=false;historyBefore=null}
        scope.launch{try{
            val result=api("/api/voice/history?conversationOnly=1"+if(older&&historyBefore!=null)"&before=$historyBefore" else "")
            val turns=result.optJSONArray("turns")?.objects().orEmpty().mapNotNull{row->val heard=row.s("transcript");val response=row.s("response").ifBlank{row.s("error")};if(heard.isBlank()&&response.isBlank())null else CoordinatorMessage(row.s("id").ifBlank{"history:${row.optLong("cursor")}:${heard.hashCode()}:${response.hashCode()}"},heard,response).also{val actions=row.optJSONArray("actions")?.objects().orEmpty();PocketVoice.routes[it.id]=routeReceipts(actions,it.id);rememberPresentation(it.id,actions)}}
            val fresh=turns.filter{turn->PocketVoice.messages.none{it.id==turn.id}};PocketVoice.messages.addAll(0,fresh)
            historyBefore=result.optLong("before").takeIf{it>0};PocketVoice.historyEarlier=result.optBoolean("hasEarlier")
        }catch(e:Exception){if(e is CancellationException)throw e;PocketVoice.historyProblem=PocketNetwork.error(e)}finally{PocketVoice.historyLoading=false}}
    }
    private val presentedTurns=mutableSetOf<String>()
    private val presentingTurns=mutableSetOf<String>()
    private fun rememberPresentation(id:String,actions:List<JSONObject>){
        if(id.isNotBlank()&&actions.any{it.s("type")=="catchup"})PocketVoice.presentationTurns[id]=CoordinatorPresentation(id,endpoint,credential)
    }
    internal fun presented(turn:CoordinatorPresentation){
        val key=turn.endpoint+":"+turn.credential+":"+turn.id
        if(key in presentedTurns||!presentingTurns.add(key))return
        scope.launch{try{
            val request=Request.Builder().url(turn.endpoint+"/api/voice/turns/${turn.id}/presented").header("Authorization","Bearer ${turn.credential}").post(JSONObject().toString().toRequestBody("application/json".toMediaType())).build()
            val ok=withContext(Dispatchers.IO){http.newCall(request).execute().use{response->response.isSuccessful&&JSONObject(response.body?.string().orEmpty()).optBoolean("ok")}}
            if(ok)presentedTurns.add(key)
        }catch(e:Exception){if(e is CancellationException)throw e}finally{presentingTurns.remove(key)}}
    }
    private var capturedEnvironment=""
    internal fun matchesSelectedCaptureEnvironment()=capturedEnvironment==Pocket.environmentId&&endpoint==Pocket.base.trimEnd('/')&&credential==Pocket.token
    private fun captureProfile(){local=Pocket.local;capturedEnvironment=Pocket.environmentId;endpoint=Pocket.savedBase(local).trimEnd('/');credential=Pocket.savedToken(local);captionProfile="${local}:${Pocket.savedBase(local)}:${Pocket.prefs.getString(Pocket.key("deviceId",local),"")}";caption=null}
    private fun wake(){lock?.acquire(10*60*1000L)}
    private fun beep(ok:Boolean=true){if(!PocketVoice.active)return;feedback.beep(ok)}
    private fun state(s:String){if(PocketVoice.service!==this)return;PocketVoice.state=s;if(s=="Listening")PocketVoice.recordingStartedAt=SystemClock.elapsedRealtime();if(s!="Listening")PocketVoice.captureLevel=0f;if(s=="Paused")caption?.let{PocketSpeechCaptions.pause(it.profile);refreshCaptionNotification()};android.util.Log.i("PocketVoice","State: $s");idleDisconnect?.cancel();if(s=="Ready")idleDisconnect=scope.launch{delay(30000);if(PocketVoice.state=="Ready"&&turnId==null&&recorder==null)disconnectNative()}}
    private suspend fun api(path:String,body:JSONObject?=null,base:String=endpoint,token:String=credential):JSONObject{
        val requestEnvironment=capturedEnvironment
        val result=withContext(Dispatchers.IO){
            val b=Request.Builder().url(base+path).header("Authorization","Bearer $token")
            if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use{r->val j=try{JSONObject(r.body?.string()?:"{}")}catch(_:Exception){JSONObject()};if(!r.isSuccessful)throw PocketApiException(r.code,j.s("error","Server returned ${r.code}"));j}
        }
        if(capturedEnvironment!=requestEnvironment||Pocket.environmentId!=requestEnvironment||
            Pocket.base.trimEnd('/')!=base||Pocket.token!=token)
            throw CancellationException("Voice response belongs to a previous environment or pairing")
        return result
    }
    private fun boot(){if(starting)return;starting=true;work=scope.launch{
        wake();state("Connecting")
        try{PocketVoice.problem=""
            if(turnId!=null){starting=false;deliver();return@launch}
            state("Ready")

        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}finally{starting=false}
    }}
    private suspend fun connectNative(purpose:String="speech"){
        val pending=nativeConnecting?:scope.async(start=CoroutineStart.LAZY){connectNativeOnce(purpose)}.also{nativeConnecting=it;it.start()}
        try{pending.await()}finally{if(nativeConnecting===pending)nativeConnecting=null}
    }
    private suspend fun connectNativeOnce(purpose:String){
        checkpointNativeUsage(stop=true)
        nativeHeartbeat?.cancel();nativeAudio?.close();nativeId=null
        val ready=CompletableDeferred<Unit>();nativeReady=ready;val ticket=++transportGeneration;val audioEndpoint=endpoint;val audioCredential=credential
        nativeAudio=NativeVoiceAudio(this,explicitCompletion=true,livePlayback=true) audio@{kind,data->if(!PocketVoice.active||ticket!=transportGeneration)return@audio;when(kind){
            "offer"->scope.launch{try{
                val result=api("/api/voice/native/start",JSONObject().put("sdp",data).put("purpose",purpose),audioEndpoint,audioCredential)
                if(!nativeNegotiationCurrent(ticket,transportGeneration,PocketVoice.active,nativeAudio!=null)){
                    android.util.Log.i("PocketVoiceTiming","Discarded stale audio negotiation $ticket")
                    api("/api/voice/native/stop",JSONObject().put("connectionId",result.s("id")),audioEndpoint,audioCredential)
                    return@launch
                }
                nativeId=result.s("id");requireNotNull(nativeAudio).answer(result.s("sdp"))
            }catch(e:Exception){if(e is CancellationException)throw e;ready.completeExceptionally(e)}}
            "connected"->{SpeechUsage.add("voiceConnections");nativeUsage.start(SystemClock.elapsedRealtime());ready.complete(Unit)}
            "audio"->scope.launch{try{
                val payload=JSONObject(data);val generation=payload.optLong("token");if(generation!=speechGeneration)return@launch
                val bytes=android.util.Base64.decode(payload.s("data"),android.util.Base64.DEFAULT)
                val cached=withContext(Dispatchers.IO){File.createTempFile("voice-spoken-",".tmp",filesDir).apply{writeBytes(bytes)}}
                if(generation!=speechGeneration||ticket!=transportGeneration){cached.delete();return@launch}
                check(cached.renameTo(spokenFile)){"Unable to save spoken response."};spokenOwner=outputs.current?.id;clearCompletedSpeechPending()
                if(payload.optBoolean("live")){
                    nativeRendering=false;nativeSpeaking=false;nativeCacheReady=true
                    if(nativePaused){if(nativeResumeRequested){nativePaused=false;play(spokenFile,true,nativePausedPositionMs)}else state("Paused")}
                    else finishNativePlayback()
                }else play(spokenFile,true)
            }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
            "timing","network"->{android.util.Log.i("PocketVoiceTiming","Native $kind $data")}
            "preparing"->{state("Preparing speech")}
            "speaking"->{activeSpeechVoiceTurnId?.let{id->scope.launch{runCatching{api("/api/voice/turns/$id/timing",JSONObject().put("stage","handset_first_audio"))}}};nativePaused=false;nativeSpeaking=true;nativeAudioBeganAt=SystemClock.elapsedRealtime();clearCompletedSpeechPending();state("Speaking");captionStarted()}
            "paused"->{nativePausedPositionMs=if(nativeAudioCacheStartedAt>0)(SystemClock.elapsedRealtime()-nativeAudioCacheStartedAt).toInt().coerceAtLeast(0) else 0;nativePaused=true;nativeSpeaking=false;state("Paused");caption?.let{PocketSpeechCaptions.pause(it.profile);refreshCaptionNotification()}}
            "finished"->{/* Cache callback completes this generation after its atomic file commit. */}
            "error"->{if((recorder!=null||(turnId==captureTurnId&&captureStreaming&&!nativeRendering))&&ready.isCompleted){captureStreamFailed=true;captureStreaming=false;capturePrefix.clear();disconnectNative();return@audio};nativeRendering=false;nativeSpeaking=false;if(!ready.isCompleted)ready.completeExceptionally(IllegalStateException(data))else{PocketVoice.problem=data;recording?.cancel();fail(IllegalStateException(data))}}
        }}
        withTimeout(35000){ready.await()};nativeReady=null
        nativeHeartbeat=scope.launch{while(isActive){delay(30000);checkpointNativeUsage();try{api("/api/voice/native/heartbeat",JSONObject().put("connectionId",nativeId))}catch(e:Exception){if(e is CancellationException)throw e;PocketVoice.problem=PocketNetwork.error(e);state("Retry needed");break}}}
    }
    private fun startLiveInput(id:String,token:Long){
        captureTurnId=id;captureStreaming=false;captureStreamFailed=false;capturePrefix.clear()
        captureStreamJob=scope.launch{
            try{
                val capability=api("/api/voice/native/capabilities")
                if(capability.optInt("liveTranscriptionVersion")!=1)return@launch
                connectNative("transcription")
                check(token==captureGeneration&&PocketVoice.captureBusy&&recorder!=null){"Capture already stopped."}
                val connection=requireNotNull(nativeId)
                api("/api/voice/native/input/begin",JSONObject().put("id",id).put("connectionId",connection))
                check(token==captureGeneration&&PocketVoice.captureBusy&&recorder!=null){"Capture already stopped."}
                captureConnection=connection;requireNotNull(nativeAudio).beginInput(token);captureStreaming=true
                for(part in capturePrefix)requireNotNull(nativeAudio).appendInput(token,part)
                capturePrefix.clear()
            }catch(e:Exception){
                if(token==captureGeneration){captureStreamFailed=true;captureStreaming=false;capturePrefix.clear()}
                // This path has audio authority only; the sealed recording remains deliverable.
                if(e is CancellationException)throw e
            }
        }
    }
    private fun streamCapturedPcm(token:Long,pcm:ByteArray){
        if(token!=captureGeneration||captureStreamFailed)return
        if(captureStreaming)nativeAudio?.appendInput(token,pcm)
        else if(capturePrefix.sumOf{it.size}+pcm.size<=1200000)capturePrefix.add(pcm)
        else {captureStreamFailed=true;capturePrefix.clear()}
    }
    private fun finishLiveRecording(turn:VoiceTurnStore.Turn,token:Long){
        val connection=captureConnection;val audio=nativeAudio
        val canFinalize=token==captureGeneration&&captureStreaming&&!captureStreamFailed&&connection!=null&&audio!=null
        captureStreaming=false
        scope.launch{
            try{
                if(canFinalize){
                    val proof=requireNotNull(audio).finalizeInput(token)
                    check(proof.optBoolean("confirmed")){"Live input did not close cleanly."}
                    api("/api/voice/native/input/finalize",JSONObject().put("id",turn.id).put("connectionId",connection)
                        .put("audioSha256",turn.sha256).put("bytes",turn.bytes).put("finalization",proof))
                }
            }catch(_:Exception){/* No task dispatch occurred. The sealed WAV uses the regular transcription path. */}
            finally{
                if(token==captureGeneration){captureStreamJob?.cancel();disconnectNative();captureConnection=null}
                DurableVoiceWork.enqueue(this@PocketVoiceService,turn.key)
            }
        }
    }
    fun beginCapture(threadId:String?,roomDestination:JSONObject?=null,sourceLabel:String?=null){
        if(PocketVoice.captureBusy){toggleRecord();return}
        PocketVoice.targetThread=threadId
        PocketVoice.targetRoom=roomDestination?.let{JSONObject(it.toString())}
        PocketVoice.targetSourceLabel=captureSourceLabel(threadId,roomDestination?.toString(),sourceLabel)
        val previous=endpoint+"\u0000"+credential
        captureProfile()
        if(previous!=endpoint+"\u0000"+credential||PocketVoice.targetThread!=null||PocketVoice.targetRoom!=null){listeningWork?.cancel();listeningWork=null}
        startCoordinatorListening()
        toggleRecord()
    }
    fun toggleRecord(){
        if(!PocketVoice.active)return
        if(PocketVoice.captureBusy){stopRecording=true;state("Finishing recording");return}
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            PocketVoice.problem="Microphone access was revoked. Enable it in Android app settings.";return
        }
        // Only capture owns the microphone. Old task receipts and playback never gate this boundary.
        val source=try {
            check(Pocket.token.isNotBlank()&&Pocket.base.isNotBlank()) {"Connect to an environment before recording."}
            VoiceTurnStore.Origin(Pocket.environmentId,backendOwner(Pocket.base.trimEnd('/'),Pocket.token),
                Pocket.base.trimEnd('/'),PocketVoice.startRequest().toString())
        }catch(e:Exception){PocketVoice.problem=e.message?:"Recording destination is unavailable.";return}
        PocketVoice.captureBusy=true;stopRecording=false;state("Starting microphone");PocketVoice.problem=""
        val id=UUID.randomUUID().toString()
        PocketSpeech.control("pause");outputWork?.cancel();outputWork=null;outputs.bargeIn();activeSpeechVoiceTurnId=null;work?.cancel();disconnectNative();stopPlayer();recordingProblem=null;wake()
        if(!requestFocus()){if(lock?.isHeld==true)lock?.release();PocketVoice.captureBusy=false;state("Ready");PocketVoice.problem="Another app is using audio. Recording has not started.";return}
        val inputToken=++captureGeneration
        recording=scope.launch {
            var sealedTurn:VoiceTurnStore.Turn?=null
            var key:String?=null
            var audio:AudioRecord?=null
            try {
                DurableVoiceWork.initialize(this@PocketVoiceService)
                key=withContext(Dispatchers.IO){VoiceTurnStore(this@PocketVoiceService,DurableVoiceWork.JOURNAL).use{it.begin(id,source).key}}
                PocketVoice.captureStarted(requireNotNull(key))
                val capture=withContext(NonCancellable+Dispatchers.IO) {
                    val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
                    check(size>0){"Android cannot initialize microphone audio."}
                    val created=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,size.coerceAtLeast(8192))
                    if(created.state!=AudioRecord.STATE_INITIALIZED){created.release();error("Microphone is unavailable.")}
                    created
                }
                audio=capture;currentCoroutineContext().ensureActive();recorder=capture
                val pcm=withContext(Dispatchers.IO) {
                    val bytes=ByteArrayOutputStream();val buf=ByteArray(4096);var lastLevel=0L;var lastSync=0L
                    val partial=VoiceTurnStore(this@PocketVoiceService,DurableVoiceWork.JOURNAL).use{it.captureFile(requireNotNull(key))}
                    val durable=java.io.FileOutputStream(partial)
                    try {
                    currentCoroutineContext().ensureActive()
                    capture.startRecording()
                    check(capture.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Android could not start the microphone. Check microphone privacy."}
                    withContext(Dispatchers.Main){state("Listening");startLiveInput(id,inputToken)}
                    while(isActive&&!stopRecording&&bytes.size()<VoiceRecording.MAX_BYTES) {
                        val n=capture.read(buf,0,buf.size,AudioRecord.READ_NON_BLOCKING)
                        check(n>=0){"Android microphone capture failed ($n)."}
                        if(n>0){
                            val accepted=n.coerceAtMost(VoiceRecording.MAX_BYTES-bytes.size())
                            bytes.write(buf,0,accepted);durable.write(buf,0,accepted)
                            val part=buf.copyOf(accepted);withContext(Dispatchers.Main){streamCapturedPcm(inputToken,part)}
                            val now=SystemClock.elapsedRealtime()
                            if(now-lastSync>=1000){durable.fd.sync();lastSync=now}
                            if(now-lastLevel>=100){lastLevel=now;val level=capturePcmLevel(buf,n);withContext(Dispatchers.Main){PocketVoice.captureLevel=level}}
                        }else delay(10)
                    }
                    currentCoroutineContext().ensureActive()
                    check(stopRecording){"Recording reached its ten-minute limit. Audio is retained locally; nothing was sent."}
                    bytes.toByteArray()
                    }finally{try{durable.fd.sync()}finally{durable.close()}}
                }
                withContext(Dispatchers.IO){voiceRecordingProblem(VoiceRecording.hasSpeech(pcm),recordingProblem)}?.let{throw IllegalStateException(it)}
                // Finishing the file is local IO only; no task or speech job holds capture ownership.
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val sealed=VoiceTurnStore(this@PocketVoiceService,DurableVoiceWork.JOURNAL).use{it.seal(requireNotNull(key),VoiceRecording.wav(pcm))}
                    withContext(Dispatchers.Main){PocketVoice.captureQueued(sealed.key)}
                    sealedTurn=sealed
                }
                beep()
            }catch(e:CancellationException){
                key?.let{saved->withContext(NonCancellable+Dispatchers.IO){VoiceTurnStore(this@PocketVoiceService,DurableVoiceWork.JOURNAL).use{it.cancelUnsubmittedCapture(saved,"Capture cancelled before sending")}}}
                throw e
            }catch(e:Exception){
                val message=e.message?:"Recording failed. No task was submitted."
                key?.let{saved->withContext(NonCancellable+Dispatchers.IO){VoiceTurnStore(this@PocketVoiceService,DurableVoiceWork.JOURNAL).use{it.interruptCapture(saved,message)}}}
                if(PocketVoice.service===this@PocketVoiceService){PocketVoice.problem=message;beep(false)}
            }
            finally {
                withContext(NonCancellable+Dispatchers.IO){try{audio?.stop()}catch(_:Exception){};audio?.release()};recorder=null
                abandonFocus();if(lock?.isHeld==true)lock?.release();if(PocketVoice.service===this@PocketVoiceService){PocketVoice.captureBusy=false;state("Ready");sealedTurn?.let{finishLiveRecording(it,inputToken)}?:run{if(inputToken==captureGeneration)disconnectNative()};drainOutput()}
            }
        }
    }
    fun sendText(text:String):Boolean{
        if(text.isBlank())return false
        voiceTextBlock(recorder!=null,starting,work?.isActive==true,turnId!=null)?.let{PocketVoice.problem=it;return false}
        stopPlayer();work=scope.launch{try{
            state("Sending")
            if(!coordinatorReady){api("/api/voice/start",PocketVoice.startRequest());coordinatorReady=true}
            val id=UUID.randomUUID().toString()
            val correction=PocketVoice.correctionOf
            val request=JSONObject().put("turnId",id).put("text",text)
            correction?.let{request.put("correctionOf",JSONObject().put("turnId",it.turnId).put("threadId",it.threadId).put("environmentId",it.environmentId))}
            check(withContext(Dispatchers.IO){Pocket.prefs.edit().putString("voicePendingId",id).putBoolean("voicePendingLocal",local).putString("voicePendingEnvironment",capturedEnvironment).putString("voicePendingOwner",backendOwner(endpoint,credential)).putString("voicePendingTarget",PocketVoice.targetThread?:"coordinator").putString("voicePendingRoom",PocketVoice.targetRoom?.toString()).putString("voicePendingSourceLabel",PocketVoice.targetSourceLabel).putString("voicePendingTextRequest",request.toString()).commit()}){"Could not save this request; nothing submitted."}
            api("/api/voice/text",request)
            if(PocketVoice.correctionOf===correction)PocketVoice.correctionOf=null
            PocketVoice.heard=text;PocketVoice.response="";deliver()
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
        return true
    }
    private fun deliver(){work=scope.launch{
        wake();val id=turnId?:return@launch
        try{
            state("Sending")
            var result:JSONObject?=try{api("/api/voice/turns/$id")}catch(e:PocketApiException){if(e.status!=404)throw e;null}
            if(result==null)throw IllegalStateException("The original saved turn has no confirmed receipt. It has not been resent; you can record a new turn.")

            val deadline=SystemClock.elapsedRealtime()+240000
            while(true){result=api("/api/voice/turns/$id");PocketVoice.heard=result.s("transcript");state(if(result.s("state")=="transcribing")"Transcribing" else "Thinking")
                if(result.s("state") in listOf("completed","failed","unknown"))break
                if(SystemClock.elapsedRealtime()>deadline)throw IllegalStateException("Your turn is still running. Use Check saved turn to check it again.")
                delay(1000)
            }
            if(result!!.s("state")!="completed"){
                val message=result.s("error","The voice turn stopped.")
                if(result.s("state")=="unknown"){
                    fail(IllegalStateException(message+" Your recording remains saved and has not been resent. You can record a new turn."));return@launch
                }
                clearPending();PocketVoice.problem=message;speak(message);return@launch
            }
            completedSpeechTurnId=id
            android.util.Log.i("PocketVoiceTiming","Turn $id response received")
            PocketVoice.response=result.s("response")
            if(PocketVoice.targetRoom==null&&PocketVoice.inPlace&&PocketCoordinator.visible)PocketCoordinator.loadHistory()
            val message=CoordinatorMessage(id,PocketVoice.heard,PocketVoice.response)
            val existing=PocketVoice.messages.indexOfFirst{it.id==id};if(existing<0)PocketVoice.messages.add(message)else PocketVoice.messages[existing]=message
            val actions=result.optJSONArray("actions")?.objects().orEmpty();PocketVoice.routes[id]=routeReceipts(actions,id);rememberPresentation(id,actions)
            // Keep the completed ID until audio is fetched, so a lost speech request never reruns Codex.
            val deliveredPresentation=PocketVoice.presentationTurns[id]
            speak(PocketVoice.response){deliveredPresentation?.let{presented(it)};applyActions(actions.filter{it.s("type") in listOf("profile","exit")});nextUpdate()}
            applyActions(actions.filter{it.s("type") !in listOf("profile","exit")})
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}
    }}
    private fun clearCompletedSpeechPending(){
        if(voiceSpeechMayClearPending(completedSpeechTurnId,Pocket.prefs.getString("voicePendingId",null)))clearPending()
    }
    private fun clearPending(){Pocket.prefs.edit().remove("voicePendingId").remove("voicePendingLocal").remove("voicePendingTarget").remove("voicePendingRoom").remove("voicePendingTextRequest").remove("voicePendingEnvironment").remove("voicePendingOwner").commit();pendingFile.delete()}
    private fun applyActions(actions:List<JSONObject>){
        // A completed turn belongs to its captured backend. User navigation or
        // re-pairing must not let late UI actions mutate another environment.
        if(Pocket.environmentId!=capturedEnvironment||Pocket.base.trimEnd('/')!=endpoint||Pocket.token!=credential)return
        for(a in actions)when(a.s("type")){
        "select"->{PocketVoice.targetThread=a.s("threadId").takeIf{it.isNotBlank()};if(Pocket.local==local){if(PocketVoice.targetThread==null)Pocket.closeTask()else Pocket.open(PocketVoice.targetThread!!) }}
        "permissions"->{Pocket.updateFullPermissions(a.optBoolean("full"))}
        "profile"->{checkpointNativeUsage(stop=true);stopNativeRemote();nativeAudio?.close();nativeAudio=null;nativeId=null;nativeHeartbeat?.cancel();val next=a.optBoolean("local");if(Pocket.savedToken(next).isBlank()){PocketVoice.problem="That device is not paired. Pair it in NextComp first.";beep(false)}else{Pocket.activate(next);local=next;coordinatorReady=false;captureProfile();loadHistory();boot()}}
        "exit"->leave()
    }}
    fun retry(){if(starting||recorder!=null)return;PocketVoice.problem="";work?.cancel();if(turnId!=null){nativeId=null;deliver()}else boot()}
    private fun fail(e:Exception){if(!PocketVoice.active)return;caption?.let{PocketSpeechCaptions.pause(it.profile)};caption=null;PocketVoice.problem=PocketNetwork.error(e);state("Retry needed");beep(false)
        val name=if(turnId!=null)"voice-turn-error" else "voice-setup-error";val cached=File(filesDir,"$name.wav");try{if(!cached.exists())assets.open("$name.wav").use{input->cached.outputStream().use{input.copyTo(it)}};play(cached,isError=true)}catch(_:Exception){state("Retry needed")}
    }
    private suspend fun speak(text:String,notification:JSONObject?=null,done:(()->Unit)?=null){
        val voiceTurnId=activeSpeechVoiceTurnId?:turnId
        val speechId="speech:"+text.hashCode()
        PocketImmersion.offer(speechId,text,"speech urgent")
        // Use the rendition already available; translation planning continues asynchronously.
        val spoken=PocketImmersion.target(speechId,text)
        caption=notification?.let{NativeCaption(it.optLong("id"),it.s("title"),spoken,captionProfile)}
        if(text.isBlank()){done?.invoke();state("Ready");return}
        state("Preparing speech")
        if(nativeTransportNeedsConnection(nativeId,nativeAudio!=null))connectNative()
        stopPlayer();if(!requestFocus())throw IllegalStateException("Another app is using audio. Retry when it finishes.")
        afterSpeech=done;spokenOwner=null;speechGeneration++;nativeRendering=true;nativePaused=false;nativeCacheReady=false;nativeResumeRequested=false;nativeAudioBeganAt=0L;nativeAudioCacheStartedAt=SystemClock.elapsedRealtime();nativePausedPositionMs=0;requireNotNull(nativeAudio){"Voice transport is unavailable."}.speak(speechGeneration,spoken)
        android.util.Log.i("PocketVoiceTiming","Speech $speechGeneration requested")
        api("/api/voice/native/speak",JSONObject().put("connectionId",nativeId).put("text",spoken).put("voiceTurnId",voiceTurnId))
    }

    internal fun ownsOrigin(origin:VoiceTurnStore.Origin)=origin.environment==capturedEnvironment&&origin.endpoint==endpoint&&origin.pairing==backendOwner(endpoint,credential)
    internal fun offerResponse(turn:VoiceTurnStore.Turn,receipt:JSONObject,complete:Boolean){
        if(!PocketVoice.active||turn.key!=PocketVoice.latestRecordingKey||!ownsOrigin(turn.origin))return
        val segments=receipt.optJSONArray("speechSegments")?.objects().orEmpty()
        if(receipt.s("speech_error").isNotBlank()){
            if(outputs.discardResponse(turn.key)){outputWork?.cancel();outputWork=null;disconnectNative();stopPlayer()}
            PocketVoice.problem=receipt.s("speech_error");return
        }
        if(receipt.s("state") in setOf("thinking","running")&&nativeId==null&&!PocketVoice.captureBusy&&nativeConnecting==null){
            scope.launch{try{connectNative();if(!nativeRendering&&!nativeSpeaking)state("Ready")}catch(e:CancellationException){throw e}catch(_:Exception){/* A real passage retries setup and surfaces the error. */}}
        }
        val ids=responseSegments.getOrPut(turn.key){linkedSetOf()}
        val title=if(JSONObject(turn.origin.destination).optString("threadId").isBlank())"Coordinator" else PocketVoice.targetSourceLabel?:"Conversation"
        for(segment in segments){val id=segment.s("id");if(id.isBlank()||!ids.add(id))continue;outputs.response(CoordinatorUtterance(turn.key+":"+id,segment.s("text"),title,turn.id,turn.key))}
        if(complete){
            if(segments.isEmpty()&&receipt.s("response").isNotBlank()){ids.add("whole");outputs.response(CoordinatorUtterance(turn.key+":whole",receipt.s("response"),title,turn.id,turn.key))}
            completedResponses[turn.key]=ids.size
            acknowledgeResponse(turn.key)
        }
        while(responseSegments.size>64){val old=responseSegments.keys.first();responseSegments.remove(old);completedResponses.remove(old);responseHeard.remove(old);responseAcknowledged.remove(old)}
        drainOutput()
    }
    private fun startCoordinatorListening(){
        if(PocketVoice.targetThread!=null||PocketVoice.targetRoom!=null||listeningWork?.isActive==true)return
        val base=endpoint;val token=credential
        listeningWork=scope.launch{
            var enabled=false
            try{
                try{CoordinatorVoiceLink.register(base,token)}catch(e:CancellationException){throw e}catch(_:Exception){}
                api("/api/coordinator/listening",JSONObject().put("enabled",true),base,token);enabled=true
                while(isActive&&PocketVoice.active&&base==endpoint&&token==credential){
                    try{
                        val result=api("/api/coordinator/voice-updates",null,base,token)
                        if(!result.optBoolean("enabled"))outputs.quietUpdates()
                        for(item in result.optJSONArray("items")?.objects().orEmpty()){
                            val environment=item.s("environmentId","current");val seq=item.optLong("seq")
                            if(!item.optBoolean("pending")&&System.currentTimeMillis()-item.optLong("at",System.currentTimeMillis())>120000)continue
                            if(seq>0)outputs.update(CoordinatorUtterance("update:"+environment+":"+seq,item.s("text"),listOf(item.s("environmentName"),item.s("name")).filter{it.isNotBlank()}.joinToString(" · "),environmentId=environment,threadId=item.s("threadId"),seq=seq,urgent=item.optBoolean("pending")))
                        }
                        drainOutput()
                    }catch(e:CancellationException){throw e}catch(_:Exception){/* Stored unseen outcomes remain at their source. */}
                    delay(5000)
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){PocketVoice.problem="Spoken work updates are unavailable; recording still works."}
            finally{if(enabled)withContext(NonCancellable){withTimeoutOrNull(5000){try{api("/api/coordinator/listening",JSONObject().put("enabled",false),base,token)}catch(_:Exception){}}}}
        }
    }
    private val responseHeard=mutableMapOf<String,Int>()
    private val responseAcknowledged=mutableSetOf<String>()
    private fun acknowledgeResponse(key:String){
        val expected=completedResponses[key]?:return
        if(expected>0&&(responseHeard[key]?:0)>=expected&&responseAcknowledged.add(key))CoordinatorReceiptWork.response(this,key)
    }
    private fun drainOutput(){
        if(!PocketVoice.active||PocketVoice.captureBusy||recorder!=null||playing||nativeRendering||nativeSpeaking||nativePaused||outputWork?.isActive==true||PocketSpeech.displayedRunning)return
        if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0)return
        val next=outputs.next()?:return
        activeSpeechVoiceTurnId=next.turnId
        val source=JSONObject().put("id",-kotlin.math.abs(next.id.hashCode().toLong()).coerceAtLeast(1)).put("title",next.title)
        outputWork=scope.launch{
            try{speak(next.text,source){
                val finished=outputs.complete(next.id)?:return@speak
                outputWork=null;activeSpeechVoiceTurnId=null
                if(finished.seq!=null)CoordinatorReceiptWork.update(this@PocketVoiceService,capturedEnvironment,backendOwner(endpoint,credential),finished.environmentId,finished.seq)
                finished.recordingKey?.let{key->responseHeard[key]=(responseHeard[key]?:0)+1;acknowledgeResponse(key)}
                drainOutput()
            }}catch(e:CancellationException){throw e}catch(e:Exception){nativeRendering=false;nativeSpeaking=false;disconnectNative();PocketVoice.problem=PocketNetwork.error(e);beep(false);state("Paused")}
        }
    }

    private fun requestFocus():Boolean{
        abandonFocus();focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener{change->if(change<0&&PocketVoice.service===this@PocketVoiceService){if(PocketVoice.captureBusy){recordingProblem="Recording interrupted by another app. Please repeat your turn.";PocketVoice.problem=recordingProblem.orEmpty();recording?.cancel()}else if(nativeSpeaking){nativeAudio?.pause();state("Paused")}else if(playing){player?.pause();playing=false;state("Paused")}}}.build()
        return audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonFocus(){focus?.let{audio.abandonAudioFocusRequest(it)};focus=null}
    private fun finishNativePlayback(){abandonFocus();state("Ready");captionFinished();val done=afterSpeech;afterSpeech=null;done?.invoke()}
    private fun play(file:File,part:Boolean=false,startAt:Int=0,isError:Boolean=false){nativeAudio?.silence();nativeRendering=false;nativeSpeaking=false;player?.release();player=null;playing=false;abandonFocus();if(!part){speechFiles=listOf(file);speechIndex=0;afterSpeech=null};if(!requestFocus()){state("Paused");return};player=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());setDataSource(file.absolutePath);setOnCompletionListener{playing=false;abandonFocus();if(speechIndex+1<speechFiles.size){speechIndex++;play(speechFiles[speechIndex],true)}else{state(if(isError)"Retry needed" else "Ready");if(!isError)captionFinished();val done=afterSpeech;afterSpeech=null;done?.invoke()}};setOnErrorListener{_,_,_->playing=false;state("Retry needed");PocketVoice.problem="Unable to play speech. Use Replay to try the audio again.";abandonFocus();true};prepare();if(startAt>0)seekTo(startAt.coerceAtMost((duration-1).coerceAtLeast(0)));start()};playing=true;state(if(isError)"Retry needed" else "Speaking");if(!isError)captionStarted()}
    fun playbackVolumeChanged(level:Int){
        if(level==0){
            if(nativeRendering||nativeSpeaking){nativePaused=true;nativeAudio?.pause();state("Paused")}
            if(playing){player?.pause();playing=false;state("Paused")}
        }else if(nativePaused||outputs.current!=null&&PocketVoice.state=="Paused")togglePlayback()
        else drainOutput()
    }
    fun togglePlayback(){
        val passage=outputs.current
        if(passage!=null&&spokenOwner!=passage.id&&!nativeRendering&&!nativeSpeaking){
            val done=afterSpeech;outputWork?.cancel()
            outputWork=scope.launch{try{disconnectNative();speak(passage.text,JSONObject().put("id",-kotlin.math.abs(passage.id.hashCode().toLong()).coerceAtLeast(1)).put("title",passage.title),done)}catch(e:CancellationException){throw e}catch(e:Exception){nativeRendering=false;nativeSpeaking=false;disconnectNative();PocketVoice.problem=PocketNetwork.error(e);state("Paused")}}
            return
        }

        if(recorder!=null){beep(false);return}
        if(nativeRendering){if(nativePaused){nativeResumeRequested=true;state("Preparing speech")}else{nativeAudio?.pause();abandonFocus()};return}
        if(nativePaused&&nativeCacheReady){nativePaused=false;play(spokenFile,true,nativePausedPositionMs);return}
        player?.let{if(playing){it.pause();playing=false;state("Paused");abandonFocus()}else if(requestFocus()){if(it.currentPosition>=it.duration){speechIndex=0;play(speechFiles.firstOrNull()?:spokenFile,true)}else{it.start();playing=true;state("Speaking");captionStarted()}};return}
        if(spokenFile.exists())play(spokenFile)else beep(false)
    }
    private fun stopPlayer(){nativeAudio?.silence();nativeRendering=false;nativeSpeaking=false;nativePaused=false;nativeCacheReady=false;nativeResumeRequested=false;player?.release();player=null;playing=false;afterSpeech=null;speechFiles=emptyList();speechIndex=0;abandonFocus()}
    fun enqueueUpdate(n:JSONObject){if(listeningWork?.isActive==true)return;if(updates.none{it.optLong("id")==n.optLong("id")})updates.addLast(n);while(updates.size>20)updates.removeFirst();nextUpdate()}
    private fun nextUpdate(){if(!PocketVoice.active||recorder!=null||playing||nativeRendering||nativePaused||nativeSpeaking||work?.isActive==true||turnId!=null||updates.isEmpty())return;val n=updates.removeFirst();work=scope.launch{try{val full=if(n.s("speech_pending")=="1")api("/api/notifications/${n.optLong("id")}").optJSONObject("notification")?:n else n;speak(full.s("spoken_text",full.s("body")),full){scope.launch{delay(50);nextUpdate()}}}catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}}
    private fun stopNativeRemote(){if(nativeId!=null){val request=Request.Builder().url("$endpoint/api/voice/native/stop").header("Authorization","Bearer $credential").post(JSONObject().put("connectionId",nativeId).toString().toRequestBody("application/json".toMediaType())).build();http.newCall(request).enqueue(object:Callback{override fun onFailure(call:Call,e:java.io.IOException){};override fun onResponse(call:Call,response:Response){response.close()}})}}
    private fun disconnectNative(){nativeConnecting?.cancel();nativeConnecting=null;captureStreamFailed=true;captureStreaming=false;captureStreamJob?.cancel();capturePrefix.clear();nativeAudio?.cancelInput();checkpointNativeUsage(stop=true);transportGeneration++;stopNativeRemote();nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;nativeReady?.cancel();nativeReady=null}
    fun leave(){stopRecording=true;PocketVoice.launching=false;PocketVoice.active=false;disconnectNative();recording?.cancel();work?.cancel();stopPlayer();state("Ready");stopSelf()}
    override fun onDestroy(){val owned=PocketVoice.service===this;checkpointNativeUsage(stop=true);transportGeneration++;nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;if(owned){PocketVoice.active=false;PocketVoice.captureBusy=false;state("Ready");PocketVoice.service=null};recording?.cancel();scope.cancel();stopPlayer();feedback.close();if(lock?.isHeld==true)lock?.release();if(owned&&!PocketVoice.foreground&&!Pocket.local)PocketLive.stop();stopForeground(STOP_FOREGROUND_REMOVE);if(caption!=null&&PocketSpeechCaptions.state.phase=="Speaking")PocketSpeechCaptions.pause(captionProfile);PocketSpeechCaptions.retain();super.onDestroy()}
}

internal fun routeReceipts(actions:List<JSONObject>,turnId:String=""):List<CoordinatorRoute> = actions.filter{it.s("type")=="route"&&it.s("threadId").isNotBlank()}.map{CoordinatorRoute(it.s("threadId"),it.s("name"),it.s("operation"),it.s("mode"),it.s("state"),it.s("turnId").ifBlank{turnId},it.s("reason"),it.s("environmentId").ifBlank{null})}.distinct()

internal data class CoordinatorPresentation(val id:String,val endpoint:String,val credential:String)
