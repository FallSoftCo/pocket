package co.fallsoft.pocket

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class PocketApplication: Application(), coil.ImageLoaderFactory {
    override fun onCreate(){super.onCreate();Pocket.init(this)}
    override fun onTrimMemory(level:Int){super.onTrimMemory(level);if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)PocketTranscript.release()}
    override fun newImageLoader()=coil.ImageLoader.Builder(this).okHttpClient(Pocket.http).build()
}
fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.s(key:String, fallback:String="") = if (isNull(key)) fallback else optString(key,fallback)
data class Task(val id:String,val title:String,val cwd:String,val status:String,val updated:Long,val watched:Boolean,val archived:Boolean=false,val preview:String="",val previewRole:String="context",val previewKind:String="message")
data class Message(val id:String,val role:String,val text:String)
class PocketApiException(val status:Int,message:String):Exception(message)

object Pocket {
    lateinit var context:Context
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    val http=PocketNetwork.client()
    var base by mutableStateOf(""); var token by mutableStateOf("")
    var local by mutableStateOf(false); private set
    var connected by mutableStateOf(false); var codexOnline by mutableStateOf(false)
    var connectionError by mutableStateOf("")
    var refreshError by mutableStateOf("")
    private var backgroundRefreshJob:Job?=null
    private var discoveryRefreshJob:Job?=null
    private var lastDiscoveryRefreshAt=0L
    private var statusRevision=0L
    var codexConnectionMessage by mutableStateOf("")
    var weeklyUsage by mutableStateOf(WeeklyUsage())
    var openWithKeyboard by mutableStateOf(false)
    var usageSamples by mutableStateOf(emptyList<UsageSample>())
    var error by mutableStateOf(""); var busy by mutableStateOf(false)
    var showArchived by mutableStateOf(false)
    var tasks by mutableStateOf(listOf<Task>())
    var sessionSnapshotComplete=false; private set
    var sessionOrderRequest=0L; private set
    var sessionOrderHandled=0L; private set
    var activities by mutableStateOf(listOf<JSONObject>())
    var notifications by mutableStateOf(listOf<JSONObject>())
    var attention by mutableStateOf(listOf<JSONObject>())
    var selected by mutableStateOf<String?>(null)
    var newTask by mutableStateOf(false)
    var projects by mutableStateOf(listOf<JSONObject>())
    var starting by mutableStateOf(false)
    var startStatus by mutableStateOf("")
    var detail by mutableStateOf<JSONObject?>(null)
    var tab by mutableIntStateOf(0)
    var host by mutableStateOf("Your workstation")
    var defaultCwd by mutableStateOf("")
    var sending by mutableStateOf(false)
    var pairingMode by mutableStateOf(false)
    var pushStatus by mutableStateOf("Setting up notifications…")
    var refreshJob:Job?=null
    var lastNotification:Long=0
    val prefs get()=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
    fun key(name:String,forLocal:Boolean=local)=if(forLocal)"local:$name" else name
    fun savedBase(forLocal:Boolean)=prefs.getString(key("server",forLocal),"")!!
    fun savedToken(forLocal:Boolean)=prefs.getString(key("token",forLocal),"")!!
    private fun promotionState(forLocal:Boolean=local):SessionPromotionState=try{
        val json=JSONObject(prefs.getString(key("sessionPromotions",forLocal),"{}")!!)
        fun ids(name:String)=json.optJSONArray(name)?.let{a->(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}}?:emptyList()
        SessionPromotionState(ids("pending"),ids("seen"))
    }catch(_:Exception){SessionPromotionState()}
    private fun savePromotionState(state:SessionPromotionState,forLocal:Boolean=local){prefs.edit().putString(key("sessionPromotions",forLocal),JSONObject().put("pending",JSONArray(state.pending)).put("seen",JSONArray(state.seen)).toString()).apply()}
    @Synchronized fun rememberSessionInteraction(id:String,eventId:String,forLocal:Boolean=local){val previous=promotionState(forLocal);val next=queueSessionPromotion(previous,id,eventId);if(next!=previous)savePromotionState(next,forLocal)}
    @Synchronized fun pendingSessionPromotions()=promotionState().pending.toSet()
    fun requestSessionOrder(){sessionOrderRequest++}
    @Synchronized fun finishSessionOrderRequest(request:Long,visible:Set<String>){
        sessionOrderHandled=request
        if(!showArchived)savePromotionState(acknowledgeSessionPromotions(promotionState(),visible))
    }
    private fun rememberReplyIntent(path:String,body:JSONObject?,response:JSONObject,forLocal:Boolean){
        val parts=path.substringBefore('?').split('/')
        if(body!=null&&parts.size==5&&parts[1]=="api"&&parts[2]=="threads"&&parts[4]=="reply"&&response.s("state") in listOf("queued","sending","accepted"))rememberSessionInteraction(parts[3],"reply:"+body.s("id"),forLocal)
    }
    fun init(c:Context){
        context=c.applicationContext;local=prefs.getBoolean("activeLocal",false);base=savedBase(local);token=savedToken(local);lastNotification=prefs.getLong(key("lastNotification"),0);restoreUsage()
        if(!prefs.contains(key("seenIds")))prefs.edit().putStringSet(key("seenIds"),((lastNotification-511).coerceAtLeast(1)..lastNotification).map{it.toString()}.toSet()).apply()
        context.getSystemService(android.app.NotificationManager::class.java).apply{cancel(1);deleteNotificationChannel("connection")}
        fullPermissions=prefs.getBoolean("fullPermissions",true)
        PocketAudio.init()
        PocketSpeech.init()
        PocketAttention.init()
        PocketImmersion.restore()
        if(token.isNotBlank())try{PocketPush.initialize()}catch(_:Exception){}
        if(local)pushStatus="Notifications stay on this phone" else if(prefs.getBoolean("pushReady",false))pushStatus="Firebase push is ready"
        if(local&&token.isNotBlank())LocalMonitorService.start(context)
    }
    suspend fun apiFor(forLocal:Boolean,path:String,body:JSONObject?=null,authorized:Boolean=true):JSONObject=withContext(Dispatchers.IO){
        val endpoint=savedBase(forLocal);val credential=savedToken(forLocal)
        val request=Request.Builder().url(endpoint.trimEnd('/')+path)
        if(authorized)request.header("Authorization","Bearer $credential")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use{r->val raw=r.body?.string()?:"{}";val json=try{JSONObject(raw)}catch(_:Exception){JSONObject().put("error","Unexpected server response (${r.code})")};if(!r.isSuccessful)throw PocketApiException(r.code,ConnectionMessages.server(json.s("error","Request failed (${r.code})")));rememberReplyIntent(path,body,json,forLocal);json}
    }
    suspend fun api(path:String, body:JSONObject?=null, authorized:Boolean=true):JSONObject=withContext(Dispatchers.IO){
        val profileLocal=local
        val request=Request.Builder().url(base.trimEnd('/')+path)
        if(authorized)request.header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { r ->
            val raw=r.body?.string()?:"{}"; val json=try{JSONObject(raw)}catch(e:Exception){JSONObject().put("error","Unexpected server response (${r.code})")}
            if(!r.isSuccessful)throw PocketApiException(r.code,ConnectionMessages.server(json.s("error","Request failed (${r.code})")));rememberReplyIntent(path,body,json,profileLocal);json
        }
    }
    fun pair(server:String,code:String){scope.launch{
        busy=true;error=""
        try {
            val url=server.trim().trimEnd('/');val pairingLocal=url in listOf("http://127.0.0.1:18880","http://localhost:18880")
            require(url.startsWith("https://")||pairingLocal){"Use your server’s HTTPS address, or NextComp’s local phone address."}
            base=url
            val r=api("/api/pair",JSONObject().put("code",code).put("name",Build.MODEL),false)
            token=r.getString("token");host=r.s("host")
            local=pairingLocal||r.optBoolean("local");prefs.edit().putBoolean("activeLocal",local).putString(key("server"),base).putString(key("token"),token).putString(key("deviceId"),r.s("id")).apply()
            if(local&&r.s("automationSecret").isNotBlank())prefs.edit().putString(key("automationSecret",true),r.s("automationSecret")).apply()
            weeklyUsage=WeeklyUsage();prefs.edit().remove(key("weeklyUsage")).apply()
            PocketPush.configure(r.optJSONObject("firebase"));if(local)LocalMonitorService.start(context);pairingMode=false;PocketImmersion.restore();PocketSpeechCaptions.init();PocketLive.start();refresh()
        }catch(e:Exception){error=PocketNetwork.error(e)}finally{busy=false}
    }}
    fun activate(forLocal:Boolean){
        if(local==forLocal)return
        val nextBase=savedBase(forLocal);val nextToken=savedToken(forLocal);if(nextBase.isBlank()||nextToken.isBlank())return
        backgroundRefreshJob?.cancel();backgroundRefreshJob=null;discoveryRefreshJob?.cancel();discoveryRefreshJob=null;lastDiscoveryRefreshAt=0L;refreshError="";statusRevision++
        sessionSnapshotComplete=false;sessionOrderRequest=0;sessionOrderHandled=0
        PocketLive.stop();PocketTranscript.clear();local=forLocal;base=nextBase;token=nextToken;host=if(local)"This phone" else "Your workstation"
        connected=false;codexOnline=false;connectionError="";codexConnectionMessage="";defaultCwd="";tasks=emptyList();activities=emptyList();notifications=emptyList();attention=emptyList();selected=null;detail=null;newTask=false
        lastNotification=prefs.getLong(key("lastNotification"),0);prefs.edit().putBoolean("activeLocal",local).apply()
        pushStatus=if(local)"Notifications stay on this phone" else if(prefs.getBoolean("pushReady",false))"Firebase push is ready" else "Setting up notifications…"
        if(local)LocalMonitorService.start(context) else LocalMonitorService.stop(context)
        restoreUsage();PocketImmersion.restore();PocketSpeechCaptions.init();PocketLive.start();refresh()
    }
    fun disconnect(){
        val wasLocal=local;val oldBase=base;val oldToken=token
        scope.launch(Dispatchers.IO){try{
            val req=Request.Builder().url(oldBase+"/api/device/disconnect").header("Authorization","Bearer $oldToken").post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().close()
        }catch(_:Exception){}}
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("pocket-push-registration")
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("pocket-attention")
        PocketSpeech.clear()
        val edit=prefs.edit().remove(key("server",wasLocal)).remove(key("token",wasLocal)).remove(key("deviceId",wasLocal)).remove(key("seenIds",wasLocal)).remove(key("lastNotification",wasLocal)).remove(key("weeklyUsage",wasLocal)).remove(key("usageSamples",wasLocal))
        if(wasLocal)edit.remove(key("automationSecret",true)).putBoolean("automationAllowed",false)
        edit.apply();weeklyUsage=WeeklyUsage();PocketTranscript.clear();PocketLive.stop();connected=false;tasks=emptyList();detail=null;selected=null;notifications=emptyList();lastNotification=0
        val fallback=if(wasLocal)false else true
        if(savedToken(fallback).isNotBlank()){local=wasLocal;activate(fallback)}else{token="";base="";pushStatus="Not paired";local=false;prefs.edit().putBoolean("activeLocal",false).apply();LocalMonitorService.stop(context)}
    }
    private fun restoreUsage(){usageSamples=try{JSONArray(prefs.getString(key("usageSamples"),"[]")).objects().map{UsageSample(it.optLong("at"),it.optDouble("remaining"),it.optLong("reset"))}.filter{it.at>0&&it.reset>0&&it.remaining.isFinite()&&it.remaining in 0.0..100.0}}catch(_:Exception){emptyList()};weeklyUsage=try{prefs.getString(key("weeklyUsage"),null)?.let{WeeklyUsage.fromJson(JSONObject(it)).copy(stale=true)}?:WeeklyUsage()}catch(_:Exception){WeeklyUsage()}}
    private fun acceptUsage(json:JSONObject?){
        weeklyUsage=WeeklyUsage.fromJson(json)
        usageSamples=UsageForecast.record(usageSamples,weeklyUsage)
        prefs.edit().putString(key("usageSamples"),JSONArray().apply{usageSamples.forEach{put(JSONObject().put("at",it.at).put("remaining",it.remaining).put("reset",it.reset))}}.toString()).apply()
        val edit=prefs.edit();if(json==null)edit.remove(key("weeklyUsage"))else edit.putString(key("weeklyUsage"),json.toString());edit.apply()
    }
    fun refresh(){if(backgroundRefreshJob?.isActive==true)return;val profileLocal=local;val profileToken=token;val profileArchived=showArchived;backgroundRefreshJob=scope.launch{
        try{
            val revision=statusRevision
            val r=api("/api/status");if(local!=profileLocal||token!=profileToken)return@launch
            acceptUsage(r.optJSONObject("usage"));if(statusRevision==revision){codexOnline=r.optBoolean("connected");codexConnectionMessage=ConnectionMessages.server(r.optJSONObject("problem")?.s("message")?:"")};host=r.s("host");defaultCwd=r.s("defaultCwd")
            val deviceChanged=prefs.getString(key("deviceId"),"")!=r.s("deviceId")
            prefs.edit().putString(key("deviceId"),r.s("deviceId")).apply();if(deviceChanged){PocketImmersion.restore();PocketSpeechCaptions.init()}
            PocketPush.configure(r.optJSONObject("firebase"),r.optJSONObject("push")?.optBoolean("registered")==true)
            val taskResult=api(if(profileArchived)"/api/threads?archived=true" else "/api/threads")
            if(local!=profileLocal||token!=profileToken||showArchived!=profileArchived)return@launch
            activities=try{api("/api/activity").optJSONArray("items")?.objects()?:emptyList()}catch(e:PocketApiException){if(e.status==404)emptyList() else throw e}
            val fetched=taskResult.optJSONArray("threads")?.objects()?.map{Task(it.s("id"),it.s("name"),it.s("cwd"),it.optJSONObject("status")?.s("type")?:"idle",it.optLong("updatedAt"),it.optBoolean("watched"),it.optBoolean("archived"),it.s("preview"),it.s("previewRole","context"),it.s("previewKind","message"))}?:emptyList()
            tasks=mergeSessionSnapshot(tasks,fetched,taskResult.optBoolean("refreshPending")){it.id}
            sessionSnapshotComplete=!taskResult.optBoolean("refreshPending")
            notifications=api("/api/notifications").optJSONArray("notifications")?.objects()?.reversed()?:emptyList()
            if(local==profileLocal&&token==profileToken)notifications.forEach{PocketNotificationTitles.remember(it.put("_local",profileLocal))}
            val latestAttention=api("/api/attention").optJSONArray("notifications")?.objects()?:emptyList()
            val activeIds=latestAttention.map{it.optLong("id")}.toSet()
            prefs.all.keys.filter{it.startsWith(key("attention:"))}.mapNotNull{it.substringAfterLast(':').toLongOrNull()}.filter{it !in activeIds}.forEach{PocketAttention.dismiss(it)}
            attention=latestAttention
            refreshError=""
        }catch(e:CancellationException){throw e}catch(e:Exception){if(local==profileLocal&&token==profileToken)refreshError=PocketNetwork.error(e)}
    }}
    fun open(id:String,keyboard:Boolean=false){if(BackendNavigation.open(id))return;openWithKeyboard=keyboard;selected=id;newTask=false;detail=null;error="";tab=0;PocketTranscript.reset(id);refreshDetail()}
    fun refreshDetail(){scope.launch{PocketTranscript.load()}}
    fun closeTask(){selected=null;detail=null;PocketTranscript.clear()}
    fun retryConnection(){if(!connected)PocketLive.retryNow();refresh();PocketTranscript.latest()}
    /** External stock sessions arrive through live previews; never refetch on every token. */
    private fun discoverSession(id:String){
        if(id.isBlank()||showArchived||tasks.any{it.id==id}||discoveryRefreshJob?.isActive==true)return
        val profileLocal=local;val profileToken=token
        discoveryRefreshJob=scope.launch {
            val now=android.os.SystemClock.elapsedRealtime()
            delay(maxOf(400L,3000L-(now-lastDiscoveryRefreshAt)))
            if(local!=profileLocal||token!=profileToken||showArchived||tasks.any{it.id==id})return@launch
            lastDiscoveryRefreshAt=android.os.SystemClock.elapsedRealtime()
            refresh()
        }
    }
    fun scheduleRefresh(){if(refreshJob?.isActive==true)return;refreshJob=scope.launch{delay(400);PocketTranscript.load()}}
    fun composeTask(){newTask=true;error="";startStatus=if(prefs.contains(key("newTaskRequest")))"A task request is saved. Check its status to continue." else "";scope.launch{try{projects=api("/api/projects").optJSONArray("projects")?.objects()?:emptyList()}catch(e:Exception){error=e.message?:"Could not load projects"}}}
    var fullPermissions by mutableStateOf(true); private set
    fun updateFullPermissions(value:Boolean){fullPermissions=value;prefs.edit().putBoolean("fullPermissions",value).apply()}
    fun startTask(cwd:String,prompt:String){if(starting)return;scope.launch{
        starting=true;error="";startStatus=if(local)"Starting on this phone…" else "Starting on your workstation…"
        val requestKey=key("newTaskRequest");val promptKey=key("newTaskPrompt");val projectKey=key("lastProject")
        val previous=prefs.getString(requestKey,null)?.let{JSONObject(it)}
        val request=if(previous?.s("cwd")==cwd&&previous.s("prompt")==prompt)previous else JSONObject().put("id",UUID.randomUUID().toString()).put("cwd",cwd).put("prompt",prompt).put("permissions",if(fullPermissions)"full" else "review")
        prefs.edit().putString(requestKey,request.toString()).commit()
        var submitted=false
        try{
            var result=api("/api/threads",request)
            submitted=true
            var attempts=0
            while(result.s("state") in listOf("queued","creating")&&attempts++<45){
                startStatus=if(result.s("state")=="queued"){
                    "Waiting for Codex…"
                }else{
                    "Creating your session…"
                }
                delay(1000)
                result=api("/api/session-starts/${request.s("id")}")
            }
            when(result.s("state")){
                "started"->{prefs.edit().remove(requestKey).remove(promptKey).putString(projectKey,cwd).apply();open(result.getString("thread_id"));refresh()}
                "failed"->{prefs.edit().remove(requestKey).apply();error=result.s("error","Session creation failed. You can correct the request and try again.");startStatus=""}
                "unknown"->{error=result.s("error","Could not confirm session creation. Check recent tasks.");startStatus="Check recent tasks before trying again. This request may already have started."}
                else->startStatus="Still queued. Check status to continue without creating a duplicate."
            }
        }catch(e:Exception){
            error=e.message?:"Could not reach the workstation"
            if(!submitted&&e is PocketApiException&&e.status in listOf(400,401,403,413)){
                prefs.edit().remove(requestKey).apply();startStatus=""
            }else startStatus="Your request is saved. Check status before starting again."
        }finally{starting=false}
    }}
    fun interrupt(){val id=selected?:return;scope.launch{try{api("/api/threads/$id/interrupt",JSONObject());scheduleRefresh()}catch(e:Exception){error=e.message?:"Could not stop task"}}}
    fun messages():List<Message>{
        val thread=detail?.optJSONObject("thread")?:return emptyList()
        return thread.optJSONArray("turns")?.objects()?.flatMap { turn -> turn.optJSONArray("items")?.objects()?.mapNotNull {item->
            when(item.s("type")){
                "agentMessage" -> Message(item.s("id"),"codex",item.s("text"))
                "userMessage" -> Message(item.s("id"),"you",item.optJSONArray("content")?.objects()?.mapNotNull {it.s("text").takeIf {s->s.isNotBlank()}}?.joinToString("\n")?:"")
                else->null
            }
        }?:emptyList() }?.filter{it.text.isNotBlank()}?.takeLast(50)?:emptyList()
    }
    fun reply(text:String,threadId:String?=selected,mode:String="auto",onDone:()->Unit={}){if(threadId==null||text.isBlank())return;scope.launch{
        sending=true;error=""
        try{api("/api/threads/$threadId/reply",JSONObject().put("text",text).put("mode",mode).put("id",UUID.randomUUID().toString()));onDone();scheduleRefresh()}
        catch(e:Exception){error=e.message?:"Reply not sent"}finally{sending=false}
    }}
    var replyActionBusy by mutableStateOf(setOf<String>())
    fun queuedReply(id:String,action:String,text:String="",confirmUnknown:Boolean=false,onDone:()->Unit={}){
        val threadId=selected?:return;val profileLocal=local;val endpoint=base;val credential=token;val previousError=error;val busyKey="$profileLocal:$threadId:$id"
        if(busyKey in replyActionBusy)return;replyActionBusy=replyActionBusy+busyKey
        val body=JSONObject().put("action",action).put("text",text).put("confirmUnknown",confirmUnknown)
        val request=Request.Builder().url(endpoint.trimEnd('/')+"/api/threads/$threadId/replies/$id").header("Authorization","Bearer $credential").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        scope.launch{try{val acknowledged=withContext(Dispatchers.IO){http.newCall(request).execute().use{r->val json=JSONObject(r.body?.string()?:"{}");if(!r.isSuccessful)throw PocketApiException(r.code,ConnectionMessages.server(json.s("error","Could not update outgoing message")));require(json.s("id")==id&&json.s("state").isNotBlank()){"Message update was not acknowledged. Refresh before trying again."};json}}
            if(local==profileLocal&&base==endpoint&&token==credential){if(selected==threadId){detail=detail?.let{JSONObject(it.toString()).put("outgoing",acknowledgedOutgoing(it.optJSONArray("outgoing"),id,action,text,acknowledged.s("state")))};if(error==previousError)error="";onDone()};scheduleRefresh()}
        }catch(e:Exception){if(local==profileLocal&&base==endpoint&&token==credential&&selected==threadId)error=e.message?:"Could not update outgoing message"}finally{replyActionBusy=replyActionBusy-busyKey}}
    }
    fun resumeQueue(){val id=selected?:return;scope.launch{try{api("/api/threads/$id/queue/resume",JSONObject());scheduleRefresh()}catch(e:Exception){error=e.message?:"Could not resume queue"}}}
    fun updateTurnSettings(body:JSONObject,onDone:()->Unit){val id=selected?:return;scope.launch{try{api("/api/threads/$id/settings",body);onDone();scheduleRefresh()}catch(e:Exception){error=e.message?:"Could not save turn settings"}}}
    fun renameTask(name:String,onDone:()->Unit){val id=selected?:return;renameTask(id,name,onDone)}
    fun renameTask(id:String,name:String,onDone:()->Unit){scope.launch{try{val renamed=api("/api/threads/$id/rename",JSONObject().put("name",name));PocketNotificationTitles.rename(id,name,renamed.optJSONArray("notificationIds")?.let{a->(0 until a.length()).map{a.optLong(it)}}?:emptyList(),revision=renamed.optLong("revision"));onDone();scheduleRefresh();refresh()}catch(e:Exception){error=e.message?:"Could not rename task"}}}
    fun restoreTask(id:String){scope.launch{try{api("/api/threads/$id/unarchive",JSONObject());refresh()}catch(e:Exception){error=e.message?:"Could not restore task"}}}
    fun archiveTask(){val id=selected?:return;scope.launch{try{api("/api/threads/$id/archive",JSONObject());if(selected==id)closeTask();refresh()}catch(e:Exception){error=e.message?:"Could not archive task"}}}
    fun watchTask(id:String,enabled:Boolean){scope.launch{try{
        api("/api/threads/$id/watch",JSONObject().put("enabled",enabled))
        tasks=tasks.map{if(it.id==id)it.copy(watched=enabled)else it}
        if(selected==id)refreshDetail()
    }catch(e:Exception){error=PocketNetwork.error(e)}}}
    fun watch(enabled:Boolean){val id=selected?:return;scope.launch{try{api("/api/threads/$id/watch",JSONObject().put("enabled",enabled));refreshDetail()}catch(e:Exception){error=e.message?:"Could not update notifications"}}}
    fun answer(id:String,body:JSONObject){scope.launch{try{api("/api/requests/$id/answer",body);refreshDetail();refresh()}catch(e:Exception){error=e.message?:"Could not answer"}}}
    fun test(){scope.launch{try{api("/api/test-notification",JSONObject())}catch(e:Exception){error=e.message?:"Test failed"}}}
    fun event(json:JSONObject){scope.launch{
        when(json.s("type")){
            "threadRenamed" -> {val applied=PocketNotificationTitles.rename(json.s("threadId"),json.s("name"),json.optJSONArray("notificationIds")?.let{a->(0 until a.length()).map{a.optLong(it)}}?:emptyList(),revision=json.optLong("revision"));if(applied)tasks=tasks.map{if(it.id==json.s("threadId"))it.copy(title=json.s("name"))else it}}
            "rateLimits" -> acceptUsage(json.optJSONObject("usage"))
            "sessionInteraction" -> {rememberSessionInteraction(json.s("threadId"),"item:"+json.s("interactionId"));discoverSession(json.s("threadId"))}
            "status" -> {
                statusRevision++
                val recovered=!codexOnline&&json.optBoolean("connected")
                codexOnline=json.optBoolean("connected");codexConnectionMessage=ConnectionMessages.server(json.optJSONObject("problem")?.s("message")?:"")
                if(recovered){refresh();scheduleRefresh()}
            }
            "notification" -> {val n=json.getJSONObject("notification");acceptNotification(n,"socket");if(PocketVoice.foreground&&!PocketVoice.active&&selected==n.s("thread_id"))refreshDetail();refresh()}
            "reply" -> {if(json.s("state")=="accepted")rememberSessionInteraction(json.s("threadId"),"reply:"+json.s("id"));if(json.s("state") in listOf("failed","unknown"))error=json.s("error","Reply could not be confirmed");scheduleRefresh()}
            "attentionResolved" -> {val ids=json.optJSONArray("ids");if(ids!=null)for(i in 0 until ids.length())PocketAttention.dismiss(ids.optLong(i));refresh();scheduleRefresh()}
            "timeline" -> {PocketTranscript.apply(json);if(json.has("turn"))scheduleRefresh()}
            "immersion" -> PocketImmersion.accept(json)
            "contextNotes" -> {if(json.s("threadId")==selected)detail=detail?.let{JSONObject(it.toString()).put("notes",json.optJSONArray("notes"))}}
            "activity" -> {activities=json.optJSONArray("items")?.objects()?:emptyList()}
            "sessionPreview" -> {discoverSession(json.s("threadId"));tasks=tasks.map{if(it.id==json.s("threadId"))it.copy(preview=json.s("preview"),previewRole=json.s("previewRole","context"),previewKind=json.s("previewKind","message"),updated=json.optLong("activityAt",if(it.updated<100000000000L)it.updated*1000 else it.updated))else it}}
            "sessionStarted" -> {
                val thread=json.optJSONObject("thread")
                if(thread!=null&&!showArchived){
                    val id=thread.s("id",json.s("threadId"))
                    if(id.isNotBlank()){
                        val previous=tasks.firstOrNull{it.id==id}
                        val next=Task(id,thread.s("name",previous?.title?:"New task"),thread.s("cwd",previous?.cwd?:""),thread.optJSONObject("status")?.s("type")?:"pending",thread.optLong("updatedAt",System.currentTimeMillis()),previous?.watched?:false,false,thread.s("preview",previous?.preview?:"Starting…"),previous?.previewRole?:"context",previous?.previewKind?:"message")
                        tasks=tasks.filterNot{it.id==id}+next
                    }
                }
                refresh()
            }
            "codex" -> {val e=json.optJSONObject("event");val p=e?.optJSONObject("params");val id=p?.s("threadId")?.ifBlank{p.optJSONObject("thread")?.s("id")?:""};val method=e?.s("method")
                // Events from existing work can continue while new work is refused for a restart.
                if(codexConnectionMessage!=ConnectionMessages.draining){statusRevision++;codexOnline=true;codexConnectionMessage=""}
                if(!id.isNullOrBlank())discoverSession(id)
                val status=when(method){"turn/started"->"active";"turn/completed"->"idle";"thread/status/changed"->p?.optJSONObject("status")?.s("type");else->null}
                if(!id.isNullOrBlank()&&!status.isNullOrBlank())tasks=tasks.map{if(it.id==id)it.copy(status=status)else it}
                if(e?.has("id")==true||e?.s("method") in listOf("turn/completed","thread/status/changed"))scheduleRefresh()}
        }
    }}
    @Synchronized fun acceptNotification(n:JSONObject,transport:String="history"){
        if(n.s("kind")=="app_update"){if(transport!="history")PocketUpdates.offer(n.optBoolean("_local",local));return}
        val id=n.optLong("id");if(id<=0)return
        val notificationLocal=n.optBoolean("_local",local)
        val seen=prefs.getStringSet(key("seenIds",notificationLocal),emptySet())!!.toMutableSet()
        if(!seen.add(id.toString()))return
        val bounded=seen.sortedByDescending{it.toLongOrNull()?:0}.take(512).toSet()
        n.put("_local",notificationLocal)
        prefs.edit().putStringSet(key("seenIds",notificationLocal),bounded).putString(key("lastDeliveryTransport",notificationLocal),transport).putLong(key("lastDeliveryId",notificationLocal),id).putLong(key("lastDeliveryAt",notificationLocal),System.currentTimeMillis()).apply()
        PocketNotifications.show(context,n)
        val age=System.currentTimeMillis()-n.optLong("created_at")
        if(!PocketNotificationReads.isRead(n)&&transport in listOf("fcm","socket")&&age in 0..120000)PocketSpeech.request(context,n)
    }
    fun catchUp(){scope.launch{try{
        val after=if(prefs.getBoolean(key("needsHistorySync"),false))0 else lastNotification
        val r=api("/api/notifications?after=$after")
        val entries=r.optJSONArray("notifications")?.objects()?:emptyList()
        entries.forEach{acceptNotification(it)}
        entries.maxOfOrNull{it.optLong("id")}?.let{lastNotification=maxOf(lastNotification,it)}
        prefs.edit().putLong(key("lastNotification"),lastNotification).putBoolean(key("needsHistorySync"),false).apply()
    }catch(_:Exception){}}}
}
