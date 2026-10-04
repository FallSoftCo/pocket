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
    var state by mutableStateOf("Ready"); internal set
    var heard by mutableStateOf(""); internal set
    var response by mutableStateOf(""); internal set
    var problem by mutableStateOf(""); internal set
    var keysEnabled by mutableStateOf(false); internal set
    private var lastKeyTime=-1L
    private var lastKeyCode=-1
    val messages=mutableStateListOf<Pair<String,String>>()
    var historyEarlier by mutableStateOf(false);internal set
    var historyLoading by mutableStateOf(false);internal set
    var historyProblem by mutableStateOf("");internal set
    fun olderHistory(){service?.loadHistory(true)}
    var foreground=false
    var service:PocketVoiceService?=null
    var targetThread by mutableStateOf<String?>(null);internal set
    fun start(c:Context,threadId:String?=null,recordImmediately:Boolean=true){
        if(ContextCompat.checkSelfPermission(c,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){problem="Allow microphone access to use voice.";return}
        targetThread=threadId;problem="";try{ContextCompat.startForegroundService(c,Intent(c,PocketVoiceService::class.java).setAction("start").putExtra("recordImmediately",recordImmediately))}catch(e:RuntimeException){problem="Open NextComp to start voice mode."}
    }
    fun stop(){service?.leave()}
    fun record(){service?.toggleRecord()}
    fun playback(){service?.togglePlayback()}
    fun sendText(text:String){service?.sendText(text)}
    fun retry(){service?.retry()}
    fun key(event:KeyEvent):Boolean{
        if(!active||event.keyCode !in listOf(KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_VOLUME_UP))return false
        if(event.action==KeyEvent.ACTION_DOWN&&event.repeatCount==0){if(lastKeyTime==event.downTime&&lastKeyCode==event.keyCode)return true;lastKeyTime=event.downTime;lastKeyCode=event.keyCode;Pocket.scope.launch{if(event.keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)record() else playback()}}
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
    @Volatile private var stopRecording=false
    private var coordinatorReady=false
    private var historyBefore:Long?=null
    private var work:Job?=null
    private var player:MediaPlayer?=null
    private var playing=false
    private var starting=false
    private var nativeAudio:NativeVoiceAudio?=null
    private var nativeId:String?=null
    private var nativeReady:CompletableDeferred<Unit>?=null
    private var nativeHeartbeat:Job?=null
    private var idleDisconnect:Job?=null
    private var transportGeneration=0L
    private var nativeSpeaking=false
    private var speechGeneration=0L
    private var local=false
    private var endpoint=""
    private var credential=""
    private var focus:AudioFocusRequest?=null
    private var lock:PowerManager.WakeLock?=null
    private var afterSpeech:(()->Unit)?=null
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
        manager.createNotificationChannel(NotificationChannel("voice-mode","Voice mode",NotificationManager.IMPORTANCE_LOW).apply{setSound(null,null)})
        val open=PendingIntent.getActivity(this,1120,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop=PendingIntent.getService(this,1121,Intent(this,PocketVoiceService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n=NotificationCompat.Builder(this,"voice-mode").setSmallIcon(R.drawable.ic_notification).setContentTitle("NextComp voice is on").setContentText("Volume down: talk · Volume up: pause or replay").setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0,"End voice",stop).build()
        if(Build.VERSION.SDK_INT>=29)startForeground(1120,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1120,n)
        lock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"NextComp:Voice").apply{setReferenceCounted(false)}
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){leave();return START_NOT_STICKY}
        if(!PocketVoice.active){PocketVoice.active=true;if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0)audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/3).coerceAtLeast(1),0);local=Pocket.prefs.getBoolean("voicePendingLocal",Pocket.local).takeIf{turnId!=null}?:Pocket.local;captureProfile();loadHistory();PocketSpeech.control("pause");PocketLive.start();if(turnId!=null)boot()else if(intent?.getBooleanExtra("recordImmediately",true)!=false)toggleRecord()else state("Ready")}
        else if(intent?.action=="start"&&intent.getBooleanExtra("recordImmediately",true))toggleRecord()
        return START_NOT_STICKY
    }
    fun loadHistory(older:Boolean=false){
        if(PocketVoice.historyLoading)return
        PocketVoice.historyLoading=true;PocketVoice.historyProblem=""
        if(!older){PocketVoice.messages.clear();PocketVoice.historyEarlier=false;historyBefore=null}
        scope.launch{try{
            val result=api("/api/voice/history"+if(older&&historyBefore!=null)"?before=$historyBefore" else "")
            val turns=result.optJSONArray("turns")?.objects().orEmpty().mapNotNull{row->val heard=row.s("transcript");val response=row.s("response").ifBlank{row.s("error")};if(heard.isBlank()&&response.isBlank())null else Pair(heard,response)}
            val fresh=if(older)turns else turns.filter{it !in PocketVoice.messages};PocketVoice.messages.addAll(0,fresh)
            historyBefore=result.optLong("before").takeIf{it>0};PocketVoice.historyEarlier=result.optBoolean("hasEarlier")
        }catch(e:Exception){if(e is CancellationException)throw e;PocketVoice.historyProblem=PocketNetwork.error(e)}finally{PocketVoice.historyLoading=false}}
    }
    private fun captureProfile(){endpoint=Pocket.savedBase(local).trimEnd('/');credential=Pocket.savedToken(local)}
    private fun wake(){lock?.acquire(10*60*1000L)}
    private fun beep(ok:Boolean=true){if(!PocketVoice.active)return;tones.startTone(if(ok)ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK,110)}
    private fun state(s:String){PocketVoice.state=s;android.util.Log.i("PocketVoice","State: $s");idleDisconnect?.cancel();if(s=="Ready")idleDisconnect=scope.launch{delay(30000);if(PocketVoice.state=="Ready"&&turnId==null&&recorder==null)disconnectNative()}}
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
        nativeHeartbeat?.cancel();nativeAudio?.close();nativeId=null
        val ready=CompletableDeferred<Unit>();nativeReady=ready;val ticket=++transportGeneration
        nativeAudio=NativeVoiceAudio(this) audio@{kind,data->if(!PocketVoice.active||ticket!=transportGeneration)return@audio;when(kind){
            "offer"->scope.launch{try{val result=api("/api/voice/native/start",JSONObject().put("sdp",data));nativeId=result.s("id");nativeAudio?.answer(result.s("sdp"))}catch(e:Exception){ready.completeExceptionally(e)}}
            "connected"->ready.complete(Unit)
            "audio"->scope.launch{try{val payload=JSONObject(data);val ticket=payload.optLong("token");if(ticket!=speechGeneration)return@launch;val bytes=android.util.Base64.decode(payload.s("data"),android.util.Base64.DEFAULT);withContext(Dispatchers.IO){spokenFile.writeBytes(bytes)};if(ticket!=speechGeneration)return@launch;play(spokenFile,true);clearPending()}catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
            "preparing"->{state("Preparing speech")}
            "speaking"->{nativeSpeaking=true;clearPending();state("Speaking")}
            "paused"->{nativeSpeaking=false;state("Paused")}
            "finished"->{nativeSpeaking=false;abandonFocus();state("Ready");val done=afterSpeech;afterSpeech=null;done?.invoke()}
            "error"->{if(!ready.isCompleted)ready.completeExceptionally(IllegalStateException(data))else{PocketVoice.problem=data;recording?.cancel();fail(IllegalStateException(data))}}
        }}
        withTimeout(35000){ready.await()};nativeReady=null
        nativeHeartbeat=scope.launch{while(isActive){delay(30000);try{api("/api/voice/native/heartbeat",JSONObject().put("connectionId",nativeId))}catch(e:Exception){if(e is CancellationException)throw e;PocketVoice.problem=PocketNetwork.error(e);state("Retry needed");break}}}
    }
    fun toggleRecord(){
        if(!PocketVoice.active)return
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){PocketVoice.problem="Microphone access was revoked. Enable it in Android app settings.";beep(false);leave();return}
        if(recorder!=null){stopRecording=true;state("Finishing recording");return}
        if(starting||work?.isActive==true&&PocketVoice.state !in listOf("Speaking","Paused","Ready")){beep(false);return}
        if(turnId!=null||PocketVoice.problem.isNotBlank()){retry();return}
        work?.cancel();disconnectNative();speechGeneration++;nativeSpeaking=false;stopPlayer();PocketVoice.problem="";PocketVoice.heard="";PocketVoice.response="";wake()
        if(!requestFocus()){state("Paused");PocketVoice.problem="Another app is using audio. Try again when it finishes.";beep(false);return}
        try{
            val size=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);require(size>0)
            val r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size.coerceAtLeast(8192));check(r.state==AudioRecord.STATE_INITIALIZED){"Microphone is unavailable."}
            stopRecording=false;recorder=r;beep();state("Starting microphone")
            recording=scope.launch{val bytes=ByteArrayOutputStream();try{
                delay(140);r.startRecording();state("Listening")
                withContext(Dispatchers.IO){val buf=ByteArray(4096);while(isActive&&!stopRecording&&bytes.size()<3840000){val n=r.read(buf,0,buf.size,AudioRecord.READ_NON_BLOCKING);if(n<0)throw IllegalStateException("Microphone stopped.");if(n>0)bytes.write(buf,0,n.coerceAtMost(3840000-bytes.size()))else delay(10)}}
            }catch(e:CancellationException){/* Manual stop commits this recording. */}catch(e:Exception){PocketVoice.problem=e.message?:"Microphone stopped."}
            finally{try{r.stop()}catch(_:Exception){};r.release();recorder=null;abandonFocus()}
            if(!PocketVoice.active)return@launch
            val pcm=bytes.toByteArray();if(!VoiceRecording.hasSpeech(pcm)||PocketVoice.problem.isNotBlank()){beep(false);state("Ready");nextUpdate();return@launch}
            val id=UUID.randomUUID().toString();withContext(NonCancellable+Dispatchers.IO){pendingFile.writeBytes(VoiceRecording.wav(pcm));Pocket.prefs.edit().putString("voicePendingId",id).putBoolean("voicePendingLocal",local).commit()}
            beep();deliver()
        }}catch(e:Exception){recorder?.release();recorder=null;abandonFocus();fail(e)}
    }
    fun sendText(text:String){
        if(text.isBlank()||recorder!=null||starting||work?.isActive==true||turnId!=null)return
        stopPlayer();work=scope.launch{try{
            state("Sending")
            if(!coordinatorReady){api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",PocketVoice.targetThread));coordinatorReady=true}
            val id=UUID.randomUUID().toString()
            api("/api/voice/text",JSONObject().put("turnId",id).put("text",text))
            Pocket.prefs.edit().putString("voicePendingId",id).putBoolean("voicePendingLocal",local).commit()
            PocketVoice.heard=text;PocketVoice.response="";deliver()
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}
    }
    private fun deliver(){work=scope.launch{
        wake();val id=turnId?:return@launch
        try{
            state("Sending")
            if(!coordinatorReady){api("/api/voice/start",JSONObject().put("fullPermissions",Pocket.fullPermissions).put("threadId",PocketVoice.targetThread));coordinatorReady=true}
            var result:JSONObject?=try{api("/api/voice/turns/$id")}catch(e:PocketApiException){if(e.status!=404)throw e;null}
            if(result==null){
                if(nativeId==null)connectNative()
                api("/api/voice/native/input",JSONObject().put("connectionId",nativeId).put("turnId",id))
                nativeAudio!!.send(withContext(Dispatchers.IO){pendingFile.readBytes()})
                result=api("/api/voice/native/commit",JSONObject().put("connectionId",nativeId).put("turnId",id))
            }

            val deadline=SystemClock.elapsedRealtime()+240000
            while(true){result=api("/api/voice/turns/$id");PocketVoice.heard=result.s("transcript");state(if(result.s("state")=="transcribing")"Transcribing" else "Thinking")
                if(result.s("state") in listOf("completed","failed","unknown"))break
                if(SystemClock.elapsedRealtime()>deadline)throw IllegalStateException("Your turn is still running. Press volume down to check it again.")
                delay(1000)
            }
            if(result!!.s("state")!="completed"){
                val message=result.s("error","The voice turn stopped.");clearPending();PocketVoice.problem=message;speak(message);return@launch
            }
            PocketVoice.response=result.s("response");if(PocketVoice.messages.lastOrNull()!=Pair(PocketVoice.heard,PocketVoice.response))PocketVoice.messages.add(Pair(PocketVoice.heard,PocketVoice.response));val actions=result.optJSONArray("actions")?.objects()?:emptyList()
            // Keep the completed ID until audio is fetched, so a lost speech request never reruns Codex.
            speak(PocketVoice.response){applyActions(actions.filter{it.s("type") in listOf("profile","exit")});nextUpdate()}
            applyActions(actions.filter{it.s("type") !in listOf("profile","exit")})
        }catch(e:Exception){if(e is CancellationException)throw e;fail(e)}
    }}
    private fun clearPending(){Pocket.prefs.edit().remove("voicePendingId").remove("voicePendingLocal").commit();pendingFile.delete()}
    private fun applyActions(actions:List<JSONObject>){for(a in actions)when(a.s("type")){
        "select"->{PocketVoice.targetThread=a.s("threadId").takeIf{it.isNotBlank()};if(Pocket.local==local){if(PocketVoice.targetThread==null)Pocket.closeTask()else Pocket.open(PocketVoice.targetThread!!) }}
        "permissions"->{Pocket.updateFullPermissions(a.optBoolean("full"))}
        "profile"->{stopNativeRemote();nativeAudio?.close();nativeAudio=null;nativeId=null;nativeHeartbeat?.cancel();val next=a.optBoolean("local");if(Pocket.savedToken(next).isBlank()){PocketVoice.problem="That device is not paired. Pair it in NextComp first.";beep(false)}else{Pocket.activate(next);local=next;coordinatorReady=false;captureProfile();loadHistory();boot()}}
        "exit"->leave()
    }}
    fun retry(){if(starting||recorder!=null)return;PocketVoice.problem="";work?.cancel();if(turnId!=null){nativeId=null;deliver()}else boot()}
    private fun fail(e:Exception){if(!PocketVoice.active)return;PocketVoice.problem=PocketNetwork.error(e);state("Retry needed");beep(false)
        val name=if(turnId!=null)"voice-turn-error" else "voice-setup-error";val cached=File(filesDir,"$name.wav");try{if(!cached.exists())assets.open("$name.wav").use{input->cached.outputStream().use{input.copyTo(it)}};play(cached)}catch(_:Exception){state("Retry needed")}
    }
    private suspend fun speak(text:String,done:(()->Unit)?=null){
        if(text.isBlank()){done?.invoke();state("Ready");return}
        state("Preparing speech")
        if(nativeId==null)connectNative()
        stopPlayer();if(!requestFocus())throw IllegalStateException("Another app is using audio. Retry when it finishes.")
        afterSpeech=done;speechGeneration++;nativeAudio?.speak(speechGeneration)
        api("/api/voice/native/speak",JSONObject().put("connectionId",nativeId).put("text",text))
    }

    private fun requestFocus():Boolean{
        abandonFocus();focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener{change->if(change<0){if(recorder!=null){PocketVoice.problem="Recording interrupted by another app. Please repeat your turn.";recording?.cancel()}else if(playing){player?.pause();playing=false;state("Paused")}}}.build()
        return audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonFocus(){focus?.let{audio.abandonAudioFocusRequest(it)};focus=null}
    private fun play(file:File,part:Boolean=false){player?.release();player=null;playing=false;abandonFocus();if(!part){speechFiles=listOf(file);speechIndex=0;afterSpeech=null};if(!requestFocus()){state("Paused");return};player=MediaPlayer().apply{setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());setDataSource(file.absolutePath);setOnCompletionListener{playing=false;abandonFocus();if(speechIndex+1<speechFiles.size){speechIndex++;play(speechFiles[speechIndex],true)}else{state("Ready");val done=afterSpeech;afterSpeech=null;done?.invoke()}};setOnErrorListener{_,_,_->playing=false;state("Retry needed");PocketVoice.problem="Unable to play speech. Press volume up to replay.";abandonFocus();true};prepare();start()};playing=true;state("Speaking")}
    fun togglePlayback(){
        if(recorder!=null){beep(false);return}
        player?.let{if(playing){it.pause();playing=false;state("Paused");abandonFocus()}else if(requestFocus()){if(it.currentPosition>=it.duration){speechIndex=0;play(speechFiles.firstOrNull()?:spokenFile,true)}else{it.start();playing=true;state("Speaking")}};return}
        if(spokenFile.exists())play(spokenFile)else beep(false)
    }
    private fun stopPlayer(){player?.release();player=null;playing=false;afterSpeech=null;speechFiles=emptyList();speechIndex=0;abandonFocus()}
    fun enqueueUpdate(n:JSONObject){if(updates.none{it.optLong("id")==n.optLong("id")})updates.addLast(n);while(updates.size>20)updates.removeFirst();nextUpdate()}
    private fun nextUpdate(){if(!PocketVoice.active||recorder!=null||playing||nativeSpeaking||work?.isActive==true||turnId!=null||updates.isEmpty())return;val n=updates.removeFirst();work=scope.launch{try{val full=if(n.s("speech_pending")=="1")api("/api/notifications/${n.optLong("id")}").optJSONObject("notification")?:n else n;speak(full.s("spoken_text",full.s("body"))){scope.launch{delay(50);nextUpdate()}}}catch(e:Exception){if(e is CancellationException)throw e;fail(e)}}}
    private fun stopNativeRemote(){if(nativeId!=null){val request=Request.Builder().url("$endpoint/api/voice/native/stop").header("Authorization","Bearer $credential").post(JSONObject().put("connectionId",nativeId).toString().toRequestBody("application/json".toMediaType())).build();http.newCall(request).enqueue(object:Callback{override fun onFailure(call:Call,e:java.io.IOException){};override fun onResponse(call:Call,response:Response){response.close()}})}}
    private fun disconnectNative(){transportGeneration++;stopNativeRemote();nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;nativeReady?.cancel();nativeReady=null}
    fun leave(){PocketVoice.active=false;disconnectNative();recording?.cancel();work?.cancel();stopPlayer();state("Ready");stopSelf()}
    override fun onDestroy(){transportGeneration++;nativeHeartbeat?.cancel();nativeAudio?.close();nativeAudio=null;nativeId=null;PocketVoice.active=false;PocketVoice.service=null;recording?.cancel();scope.cancel();stopPlayer();tones.release();if(lock?.isHeld==true)lock?.release();if(!PocketVoice.foreground&&!Pocket.local)PocketLive.stop();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
