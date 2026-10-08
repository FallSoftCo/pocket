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
    var active by mutableStateOf(false); internal set
    var inPlace by mutableStateOf(false);private set
    var launching by mutableStateOf(false);internal set
    var recordingStartedAt by mutableLongStateOf(0);internal set
    var captureLevel by mutableFloatStateOf(0f);internal set
    val captureFeedbackVisible get()=captureFeedbackVisible(active,launching,state,problem)
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
    fun start(c:Context,threadId:String?=null,recordImmediately:Boolean=true,inPlace:Boolean=false,checkSaved:Boolean=false){
        if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){problem="Allow microphone access to use voice.";return}
        if(active&&captureControl(true,state,Pocket.prefs.getString("voicePendingId",null)!=null)==CaptureControl.PROCESSING)return
        if(Pocket.prefs.getString("voicePendingId",null)!=null&&!checkSaved&&!(active&&state in setOf("Listening","Starting microphone"))){captureFailure("A saved voice turn needs checking. Tap Check saved turn; it has not been resent.",inPlace);return}
        if(active){service?.beginCapture(threadId);return}
        if(launching)return
        this.inPlace=inPlace;launching=true;state="Starting voice";targetThread=if(checkSaved)Pocket.prefs.getString("voicePendingTarget",null)?.takeUnless{it=="coordinator"} else threadId;problem="";try{ContextCompat.startForegroundService(c,Intent(c,PocketVoiceService::class.java).setAction("start").putExtra("recordImmediately",recordImmediately))}catch(e:RuntimeException){launching=false;state="Ready";val reason=(e.message?:e.javaClass.simpleName).replace('\n',' ').let{if(Pocket.token.isNotBlank())it.replace(Pocket.token,"[credential]")else it}.take(180);captureFailure("Android could not start recording: $reason",inPlace)}
    }
    fun stop(){service?.leave();launching=false;problem=""}
    fun record(){service?.toggleRecord()}
    fun playback(){service?.togglePlayback()}
    fun sendText(text:String):Boolean=service?.sendText(text)?:false.also{problem="Voice is not ready to send text. Your draft is kept."}
    fun retry(){service?.retry()}
    fun key(event:KeyEvent):Boolean{
        if(event.keyCode !in listOf(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_VOLUME_UP))return false
        if(event.action==KeyEvent.ACTION_UP)captureKeys.up(event.keyCode)
        val destination=currentForegroundCaptureTarget()
        if(!foregroundCaptureKeys(foreground,Pocket.token.isNotBlank()&&!Pocket.pairingMode,BackendNavigation.teamSelected(),destination.eligible)||!active)return false
        if(event.action==KeyEvent.ACTION_DOWN&&captureKeys.down(event.keyCode,event.repeatCount))Pocket.scope.launch{start(Pocket.context,destination.threadId,inPlace=inPlace)}
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
    private var nativeAudio:NativeVoiceAudio?=null
    private val nativeUsage=SpeechPlaybackMeter()
    private fun checkpointNativeUsage(stop:Boolean=false){SpeechUsage.add("voiceConnectionMs",if(stop)nativeUsage.stop(SystemClock.elapsedRealtime()) else nativeUsage.sample(SystemClock.elapsedRealtime()))}
    private var nativeId:String?=null
    private var nativeReady:CompletableDeferred<Unit>?=null
    private var nativeHeartbeat:Job?=null
    private var idleDisconnect:Job?=null
    private var transportGeneration=0L
    private var nativeSpeaking=false
    private var nativeRendering=false
    private var nativePaused=false
    private var nativeCacheReady=false
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
    private val tones=ToneGenerator(AudioManager.STREAM_MUSIC,55)
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
        if(intent?.action=="stop"){leave();return START_NOT_STICKY}
        if(!PocketVoice.active){PocketVoice.launching=false;PocketVoice.active=true;local=Pocket.prefs.getBoolean("voicePendingLocal",Pocket.local).takeIf{turnId!=null}?:Pocket.local;captureProfile();loadHistory();PocketSpeech.control("pause");PocketLive.start();if(turnId!=null)boot()else if(intent?.getBooleanExtra("recordImmediately",true)!=false)toggleRecord()else state("Ready")}
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
    private fun captureProfile(){endpoint=Pocket.savedBase(local).trimEnd('/');credential=Pocket.savedToken(local);captionProfile="${local}:${Pocket.savedBase(local)}:${Pocket.prefs.getString(Pocket.key("deviceId",local),"")}";caption=null}
    private fun wake(){lock?.acquire(10*60*1000L)}
    private fun beep(ok:Boolean=true){if(!PocketVoice.active)return;tones.startTone(if(ok)ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK,110)}
    private fun state(s:String){PocketVoice.state=s;if(s=="Listening")PocketVoice.recordingStartedAt=SystemClock.elapsedRealtime();if(s!="Listening")PocketVoice.captureLevel=0f;if(s=="Paused")caption?.let{PocketSpeechCaptions.pause(it.profile);refreshCaptionNotification()};android.util.Log.i("PocketVoice","State: $s");idleDisconnect?.cancel();if(s=="Ready")idleDisconnect=scope.launch{delay(30000);if(PocketVoice.state=="Ready"&&turnId==null&&recorder==null)disconnectNative()}}
    private suspend fun api(path:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
        val b=Request.Builder().url(endpoint+path).header("Authorization","Bearer $credential")
        if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use{r->val j=try{JSONObject(r.body?.string()?:"{}")}catch(_:Exception){JSONObject()};if(!r.isSuccessful)throw PocketApiException(r.code,j.s("error","Server returned ${r.code}"));j}
    }
    private fun boot(){if(starting)return;starting=true;work=scope.launch{
        wake();state("Connecting")
        try{api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",PocketVoice.targetThread));coordinatorReady=true;PocketVoice.problem=""
            if(turnId!=null){starting=false;deliver();return@launch}
            state("Ready")

        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}finally{starting=false}
    }}
    private suspend fun connectNative(){
        checkpointNativeUsage(stop=true)
        nativeHeartbeat?.cancel();nativeAudio?.close();nativeId=null
        val ready=CompletableDeferred<Unit>();nativeReady=ready;val ticket=++transportGeneration
        nativeAudio=NativeVoiceAudio(this,livePlayback=true) audio@{kind,data->if(!PocketVoice.active||ticket!=transportGeneration)return@audio;when(kind){
            "offer"->scope.launch{try{val result=api("/api/voice/native/start",JSONObject().put("sdp",data));nativeId=result.s("id");nativeAudio?.answer(result.s("sdp"))}catch(e:Exception){ready.completeExceptionally(e)}}
            "connected"->{SpeechUsage.add("voiceConnections");nativeUsage.start(SystemClock.elapsedRealtime());ready.complete(Unit)}
            "audio"->scope.launch{try{
                val payload=JSONObject(data);val generation=payload.optLong("token");if(generation!=speechGeneration)return@launch
                val bytes=android.util.Base64.decode(payload.s("data"),android.util.Base64.DEFAULT)
                val cached=withContext(Dispatchers.IO){File.createTempFile("voice-spoken-",".tmp",filesDir).apply{writeBytes(bytes)}}
                if(generation!=speechGeneration||ticket!=transportGeneration){cached.delete();return@launch}
                check(cached.renameTo(spokenFile)){"Unable to save spoken response."};clearPending()
                if(payload.optBoolean("live")){
                    nativeRendering=false;nativeSpeaking=false;nativeCacheReady=true
                    if(nativePaused){if(nativeResumeRequested){nativePaused=false;play(spokenFile,true,nativePausedPositionMs)}else state("Paused")}
                    else finishNativePlayback()
                }else play(spokenFile,true)
            }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
            "timing","network"->{android.util.Log.i("PocketVoiceTiming","Native $kind $data")}
            "preparing"->{state("Preparing speech")}
            "speaking"->{nativePaused=false;nativeSpeaking=true;nativeAudioBeganAt=SystemClock.elapsedRealtime();clearPending();state("Speaking");captionStarted()}
            "paused"->{nativePausedPositionMs=if(nativeAudioCacheStartedAt>0)(SystemClock.elapsedRealtime()-nativeAudioCacheStartedAt).toInt().coerceAtLeast(0) else 0;nativePaused=true;nativeSpeaking=false;state("Paused");caption?.let{PocketSpeechCaptions.pause(it.profile);refreshCaptionNotification()}}
            "finished"->{/* Cache callback completes this generation after its atomic file commit. */}
            "error"->{if((recorder!=null||(turnId==captureTurnId&&captureStreaming&&!nativeRendering))&&ready.isCompleted){captureStreamFailed=true;captureStreaming=false;capturePrefix.clear();disconnectNative();return@audio};nativeRendering=false;nativeSpeaking=false;if(!ready.isCompleted)ready.completeExceptionally(IllegalStateException(data))else{PocketVoice.problem=data;recording?.cancel();fail(IllegalStateException(data))}}
        }}
        withTimeout(35000){ready.await()};nativeReady=null
        nativeHeartbeat=scope.launch{while(isActive){delay(30000);checkpointNativeUsage();try{api("/api/voice/native/heartbeat",JSONObject().put("connectionId",nativeId))}catch(e:Exception){if(e is CancellationException)throw e;PocketVoice.problem=PocketNetwork.error(e);state("Retry needed");break}}}
    }
    fun beginCapture(threadId:String?){
        if(recorder!=null){toggleRecord();return}
        if(PocketVoice.targetThread!=threadId){
            if(recorder!=null||starting||work?.isActive==true||turnId!=null){PocketVoice.problem="Finish or check the current voice turn before changing its conversation.";return}
            PocketVoice.targetThread=threadId;coordinatorReady=false
        }
        toggleRecord()
    }
    fun toggleRecord(){
        if(!PocketVoice.active)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){PocketVoice.problem="Microphone access was revoked. Enable it in Android app settings.";beep(false);leave();return}
        when(voiceRecordIntent(recorder!=null,starting,work?.isActive==true,PocketVoice.state,turnId!=null)){
            VoiceRecordIntent.STOP_AND_SEND->{stopRecording=true;state("Finishing recording");return}
            VoiceRecordIntent.BUSY->{PocketVoice.problem="${PocketVoice.state}. Your current turn is still being processed; recording has not started.";beep(false);return}
            VoiceRecordIntent.RECOVER_PENDING->{PocketVoice.problem="A saved turn needs checking. Use Check saved turn before recording again.";beep(false);return}
            VoiceRecordIntent.START->Unit
        }
        val interruptedProviderSpeech=nativeRendering||nativeSpeaking
        work?.cancel();idleDisconnect?.cancel();if(interruptedProviderSpeech)disconnectNative()else nativeAudio?.cancelInput();captureStreamJob?.cancel();capturePrefix.clear();speechGeneration++;nativeSpeaking=false;stopPlayer();recordingProblem=null;PocketVoice.problem="";PocketVoice.heard="";PocketVoice.response="";wake()
        if(!requestFocus()){state("Paused");PocketVoice.problem="Another app is using audio. Try again when it finishes.";beep(false);return}
        try{
            val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);require(size>0)
            val r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size.coerceAtLeast(8192));check(r.state==AudioRecord.STATE_INITIALIZED){"Microphone is unavailable."}
            stopRecording=false;recorder=r;beep();state("Starting microphone")
            val id=UUID.randomUUID().toString();val origin=PocketVoice.targetThread?:"coordinator";val generation=++captureGeneration
            captureTurnId=id;captureConnection=null;captureStreaming=false;captureStreamFailed=false
            captureStreamJob=scope.launch{try{
                if(!coordinatorReady){api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",origin.takeIf{it!="coordinator"}));coordinatorReady=true}
                if(nativeId==null)connectNative()
                if(generation!=captureGeneration)return@launch
                api("/api/voice/native/input",JSONObject().put("connectionId",nativeId).put("turnId",id))
                if(generation!=captureGeneration)return@launch
                nativeAudio!!.beginInput(generation);captureConnection=nativeId;captureStreaming=true
                for(chunk in capturePrefix)nativeAudio!!.appendInput(generation,chunk);capturePrefix.clear()
                android.util.Log.i("PocketVoiceTiming","Turn $id live input armed")
            }catch(e:Exception){if(e is CancellationException)throw e;captureStreamFailed=true;captureStreaming=false;capturePrefix.clear();android.util.Log.i("PocketVoiceTiming","Turn $id using saved capture fallback")}}
            fun feed(chunk:ByteArray){if(generation!=captureGeneration||captureStreamFailed)return;if(captureStreaming)nativeAudio?.appendInput(generation,chunk)else capturePrefix.add(chunk)}
            recording=scope.launch{val bytes=ByteArrayOutputStream();try{
                delay(140);r.startRecording();check(r.recordingState==AudioRecord.RECORDSTATE_RECORDING){"Android could not start the microphone. Check microphone privacy and app permissions."};state("Listening")
                withContext(Dispatchers.IO){val buf=ByteArray(4096);val chunk=ByteArrayOutputStream();var lastLevel=0L;while(isActive&&!stopRecording&&bytes.size()<3840000){val n=r.read(buf,0,buf.size,AudioRecord.READ_NON_BLOCKING);if(n<0)throw IllegalStateException("Microphone stopped.");if(n>0){val accepted=n.coerceAtMost(3840000-bytes.size());bytes.write(buf,0,accepted);chunk.write(buf,0,accepted);if(chunk.size()>=3200){val data=chunk.toByteArray();chunk.reset();withContext(Dispatchers.Main){feed(data)}};val now=SystemClock.elapsedRealtime();if(now-lastLevel>=100){lastLevel=now;val level=capturePcmLevel(buf,n);withContext(Dispatchers.Main){PocketVoice.captureLevel=level}}}else delay(10)};if(chunk.size()>0){val data=chunk.toByteArray();withContext(Dispatchers.Main){feed(data)}}}
            }catch(e:CancellationException){/* Leaving mode or an audio interruption is handled below. */}catch(e:Exception){recordingProblem=e.message?:"Microphone stopped.";PocketVoice.problem=recordingProblem.orEmpty()}
            finally{try{r.stop()}catch(_:Exception){};r.release();recorder=null;abandonFocus()}
            if(!PocketVoice.active)return@launch
            android.util.Log.i("PocketVoiceTiming","Capture complete")
            val pcm=bytes.toByteArray();voiceRecordingProblem(VoiceRecording.hasSpeech(pcm),recordingProblem)?.let{captureGeneration++;disconnectNative();captureTurnId=null;PocketVoice.problem=it;beep(false);state("Ready");nextUpdate();return@launch}
            PocketVoice.problem=""
            withContext(NonCancellable+Dispatchers.IO){pendingFile.writeBytes(VoiceRecording.wav(pcm));Pocket.prefs.edit().putString("voicePendingId",id).putBoolean("voicePendingLocal",local).putString("voicePendingTarget",origin).commit()}
            beep();deliver()
        }}catch(e:Exception){recorder?.release();recorder=null;abandonFocus();fail(e)}
    }
    fun sendText(text:String):Boolean{
        if(text.isBlank())return false
        voiceTextBlock(recorder!=null,starting,work?.isActive==true,turnId!=null)?.let{PocketVoice.problem=it;return false}
        stopPlayer();work=scope.launch{try{
            state("Sending")
            if(!coordinatorReady){api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",PocketVoice.targetThread));coordinatorReady=true}
            val id=UUID.randomUUID().toString()
            val correction=PocketVoice.correctionOf
            val request=JSONObject().put("turnId",id).put("text",text)
            correction?.let{request.put("correctionOf",JSONObject().put("turnId",it.turnId).put("threadId",it.threadId))}
            api("/api/voice/text",request)
            if(PocketVoice.correctionOf===correction)PocketVoice.correctionOf=null
            Pocket.prefs.edit().putString("voicePendingId",id).putBoolean("voicePendingLocal",local).putString("voicePendingTarget",PocketVoice.targetThread?:"coordinator").commit()
            PocketVoice.heard=text;PocketVoice.response="";deliver()
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
        return true
    }
    private fun deliver(){work=scope.launch{
        wake();val id=turnId?:return@launch
        try{
            state("Sending")
            val recordedOrigin=Pocket.prefs.getString("voicePendingTarget",null)?.takeIf{it!="coordinator"}
            if(!coordinatorReady||recordedOrigin!=PocketVoice.targetThread){api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",recordedOrigin));coordinatorReady=true}
            var result:JSONObject?=try{api("/api/voice/turns/$id")}catch(e:PocketApiException){if(e.status!=404)throw e;null}
            if(result==null){
                captureStreamJob?.join()
                var streamed=false
                if(captureTurnId==id&&captureStreaming&&!captureStreamFailed&&captureConnection==nativeId){
                    try{android.util.Log.i("PocketVoiceTiming","Turn $id live input finish");nativeAudio!!.finishInput(captureGeneration);streamed=true}
                    catch(e:Exception){if(!isActive)throw e;disconnectNative();android.util.Log.i("PocketVoiceTiming","Turn $id pre-commit saved capture fallback")}
                }
                if(!streamed){
                    if(nativeId==null)connectNative()
                    api("/api/voice/native/input",JSONObject().put("connectionId",nativeId).put("turnId",id))
                    android.util.Log.i("PocketVoiceTiming","Turn $id saved upload begin")
                    nativeAudio!!.send(withContext(Dispatchers.IO){pendingFile.readBytes()})
                    android.util.Log.i("PocketVoiceTiming","Turn $id saved upload complete")
                }
                result=api("/api/voice/native/commit",JSONObject().put("connectionId",nativeId).put("turnId",id))
                captureStreaming=false;captureTurnId=null;capturePrefix.clear()
            }

            val deadline=SystemClock.elapsedRealtime()+240000
            while(true){result=api("/api/voice/turns/$id");PocketVoice.heard=result.s("transcript");state(if(result.s("state")=="transcribing")"Transcribing" else "Thinking")
                if(result.s("state") in listOf("completed","failed","unknown"))break
                if(SystemClock.elapsedRealtime()>deadline)throw IllegalStateException("Your turn is still running. Use Check saved turn to check it again.")
                delay(1000)
            }
            if(result!!.s("state")!="completed"){
                val message=result.s("error","The voice turn stopped.");clearPending();PocketVoice.problem=message;speak(message);return@launch
            }
            android.util.Log.i("PocketVoiceTiming","Turn $id response received")
            PocketVoice.response=result.s("response")
            if(PocketVoice.inPlace&&PocketCoordinator.visible)PocketCoordinator.loadHistory()
            val message=CoordinatorMessage(id,PocketVoice.heard,PocketVoice.response)
            val existing=PocketVoice.messages.indexOfFirst{it.id==id};if(existing<0)PocketVoice.messages.add(message)else PocketVoice.messages[existing]=message
            val actions=result.optJSONArray("actions")?.objects().orEmpty();PocketVoice.routes[id]=routeReceipts(actions,id);rememberPresentation(id,actions)
            // Keep the completed ID until audio is fetched, so a lost speech request never reruns Codex.
            val deliveredPresentation=PocketVoice.presentationTurns[id]
            speak(PocketVoice.response){deliveredPresentation?.let{presented(it)};applyActions(actions.filter{it.s("type") in listOf("profile","exit")});nextUpdate()}
            applyActions(actions.filter{it.s("type") !in listOf("profile","exit")})
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}
    }}
    private fun clearPending(){Pocket.prefs.edit().remove("voicePendingId").remove("voicePendingLocal").remove("voicePendingTarget").commit();pendingFile.delete()}
    private fun applyActions(actions:List<JSONObject>){for(a in actions)when(a.s("type")){
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
        val voiceTurnId=turnId
        val speechId="speech:"+text.hashCode()
        PocketImmersion.offer(speechId,text,"speech urgent")
        // Use the rendition already available; translation planning continues asynchronously.
        val spoken=PocketImmersion.target(speechId,text)
        caption=notification?.let{NativeCaption(it.optLong("id"),it.s("title"),spoken,captionProfile)}
        if(text.isBlank()){done?.invoke();state("Ready");return}
        state("Preparing speech")
        if(nativeId==null)connectNative()
        stopPlayer();if(!requestFocus())throw IllegalStateException("Another app is using audio. Retry when it finishes.")
        afterSpeech=done;speechGeneration++;nativeRendering=true;nativePaused=false;nativeCacheReady=false;nativeResumeRequested=false;nativeAudioBeganAt=0L;nativeAudioCacheStartedAt=SystemClock.elapsedRealtime();nativePausedPositionMs=0;nativeAudio?.speak(speechGeneration)
        android.util.Log.i("PocketVoiceTiming","Speech $speechGeneration requested")
        api("/api/voice/native/speak",JSONObject().put("connectionId",nativeId).put("text",spoken).put("voiceTurnId",voiceTurnId))
    }

    private fun requestFocus():Boolean{
        abandonFocus();focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener{change->if(change<0){if(recorder!=null){recordingProblem="Recording interrupted by another app. Please repeat your turn.";PocketVoice.problem=recordingProblem.orEmpty();recording?.cancel()}else if(nativeSpeaking){nativeAudio?.pause();state("Paused")}else if(playing){player?.pause();playing=false;state("Paused")}}}.build()
        return audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonFocus(){focus?.let{audio.abandonAudioFocusRequest(it)};focus=null}
    private fun finishNativePlayback(){abandonFocus();state("Ready");captionFinished();val done=afterSpeech;afterSpeech=null;done?.invoke()}
    private fun play(file:File,part:Boolean=false,startAt:Int=0,isError:Boolean=false){nativeAudio?.silence();nativeRendering=false;nativeSpeaking=false;player?.release();player=null;playing=false;abandonFocus();if(!part){speechFiles=listOf(file);speechIndex=0;afterSpeech=null};if(!requestFocus()){state("Paused");return};player=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());setDataSource(file.absolutePath);setOnCompletionListener{playing=false;abandonFocus();if(speechIndex+1<speechFiles.size){speechIndex++;play(speechFiles[speechIndex],true)}else{state(if(isError)"Retry needed" else "Ready");if(!isError)captionFinished();val done=afterSpeech;afterSpeech=null;done?.invoke()}};setOnErrorListener{_,_,_->playing=false;state("Retry needed");PocketVoice.problem="Unable to play speech. Use Replay to try the audio again.";abandonFocus();true};prepare();if(startAt>0)seekTo(startAt.coerceAtMost((duration-1).coerceAtLeast(0)));start()};playing=true;state(if(isError)"Retry needed" else "Speaking");if(!isError)captionStarted()}
    fun togglePlayback(){
        if(recorder!=null){beep(false);return}
        if(nativeRendering){if(nativePaused){nativeResumeRequested=true;state("Preparing speech")}else{nativeAudio?.pause();abandonFocus()};return}
        if(nativePaused&&nativeCacheReady){nativePaused=false;play(spokenFile,true,nativePausedPositionMs);return}
        player?.let{if(playing){it.pause();playing=false;state("Paused");abandonFocus()}else if(requestFocus()){if(it.currentPosition>=it.duration){speechIndex=0;play(speechFiles.firstOrNull()?:spokenFile,true)}else{it.start();playing=true;state("Speaking");captionStarted()}};return}
        if(spokenFile.exists())play(spokenFile)else beep(false)
    }
    private fun stopPlayer(){nativeAudio?.silence();nativeRendering=false;nativeSpeaking=false;nativePaused=false;nativeCacheReady=false;nativeResumeRequested=false;player?.release();player=null;playing=false;afterSpeech=null;speechFiles=emptyList();speechIndex=0;abandonFocus()}
    fun enqueueUpdate(n:JSONObject){if(updates.none{it.optLong("id")==n.optLong("id")})updates.addLast(n);while(updates.size>20)updates.removeFirst();nextUpdate()}
    private fun nextUpdate(){if(!PocketVoice.active||recorder!=null||playing||nativeRendering||nativePaused||nativeSpeaking||work?.isActive==true||turnId!=null||updates.isEmpty())return;val n=updates.removeFirst();work=scope.launch{try{val full=if(n.s("speech_pending")=="1")api("/api/notifications/${n.optLong("id")}").optJSONObject("notification")?:n else n;speak(full.s("spoken_text",full.s("body")),full){scope.launch{delay(50);nextUpdate()}}}catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}}
    private fun stopNativeRemote(){if(nativeId!=null){val request=Request.Builder().url("$endpoint/api/voice/native/stop").header("Authorization","Bearer $credential").post(JSONObject().put("connectionId",nativeId).toString().toRequestBody("application/json".toMediaType())).build();http.newCall(request).enqueue(object:Callback{override fun onFailure(call:Call,e:java.io.IOException){};override fun onResponse(call:Call,response:Response){response.close()}})}}
    private fun disconnectNative(){captureStreamFailed=true;captureStreaming=false;captureStreamJob?.cancel();capturePrefix.clear();nativeAudio?.cancelInput();checkpointNativeUsage(stop=true);transportGeneration++;stopNativeRemote();nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;nativeReady?.cancel();nativeReady=null}
    fun leave(){PocketVoice.launching=false;PocketVoice.active=false;disconnectNative();recording?.cancel();work?.cancel();stopPlayer();state("Ready");stopSelf()}
    override fun onDestroy(){checkpointNativeUsage(stop=true);transportGeneration++;nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;PocketVoice.active=false;PocketVoice.service=null;recording?.cancel();scope.cancel();stopPlayer();tones.release();if(lock?.isHeld==true)lock?.release();if(!PocketVoice.foreground&&!Pocket.local)PocketLive.stop();stopForeground(STOP_FOREGROUND_REMOVE);if(caption!=null&&PocketSpeechCaptions.state.phase=="Speaking")PocketSpeechCaptions.pause(captionProfile);PocketSpeechCaptions.retain();super.onDestroy()}
}

internal fun routeReceipts(actions:List<JSONObject>,turnId:String=""):List<CoordinatorRoute> = actions.filter{it.s("type")=="route"&&it.s("threadId").isNotBlank()}.map{CoordinatorRoute(it.s("threadId"),it.s("name"),it.s("operation"),it.s("mode"),it.s("state"),it.s("turnId").ifBlank{turnId},it.s("reason"))}.distinct()

internal data class CoordinatorPresentation(val id:String,val endpoint:String,val credential:String)
