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

class PocketApplication: Application() { override fun onCreate(){super.onCreate();Pocket.init(this)} }
fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.s(key:String, fallback:String="") = if (isNull(key)) fallback else optString(key,fallback)
data class Task(val id:String,val title:String,val cwd:String,val status:String,val updated:Long,val watched:Boolean)
data class Message(val id:String,val role:String,val text:String)
class PocketApiException(val status:Int,message:String):Exception(message)

object Pocket {
    lateinit var context:Context
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(40,TimeUnit.SECONDS).build()
    var base by mutableStateOf(""); var token by mutableStateOf("")
    var connected by mutableStateOf(false); var codexOnline by mutableStateOf(false)
    var error by mutableStateOf(""); var busy by mutableStateOf(false)
    var tasks by mutableStateOf(listOf<Task>())
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
    var sending by mutableStateOf(false)
    var pushStatus by mutableStateOf("Setting up notifications…")
    var refreshJob:Job?=null
    var lastNotification:Long=0
    val prefs get()=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
    fun init(c:Context){
        context=c.applicationContext;base=prefs.getString("server","")!!;token=prefs.getString("token","")!!;lastNotification=prefs.getLong("lastNotification",0)
        if(!prefs.contains("seenIds"))prefs.edit().putStringSet("seenIds",((lastNotification-511).coerceAtLeast(1)..lastNotification).map{it.toString()}.toSet()).apply()
        context.getSystemService(android.app.NotificationManager::class.java).apply{cancel(1);deleteNotificationChannel("connection")}
        PocketAudio.init()
        PocketAttention.init()
        if(token.isNotBlank())try{PocketPush.initialize()}catch(_:Exception){}
        if(prefs.getBoolean("pushReady",false))pushStatus="Firebase push is ready"
    }
    suspend fun api(path:String, body:JSONObject?=null, authorized:Boolean=true):JSONObject=withContext(Dispatchers.IO){
        val request=Request.Builder().url(base.trimEnd('/')+path)
        if(authorized)request.header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { r ->
            val raw=r.body?.string()?:"{}"; val json=try{JSONObject(raw)}catch(e:Exception){JSONObject().put("error","Unexpected server response (${r.code})")}
            if(!r.isSuccessful)throw PocketApiException(r.code,json.s("error","Request failed (${r.code})"));json
        }
    }
    fun pair(server:String,code:String){scope.launch{
        busy=true;error=""
        try {
            val url=server.trim().trimEnd('/');require(url.startsWith("https://")){"Use your server’s HTTPS address."}
            base=url
            val r=api("/api/pair",JSONObject().put("code",code).put("name",Build.MODEL),false)
            token=r.getString("token");host=r.s("host")
            prefs.edit().putString("server",base).putString("token",token).putString("deviceId",r.s("id")).apply()
            PocketPush.configure(r.optJSONObject("firebase"));PocketLive.start();refresh()
        }catch(e:Exception){error=e.message?:"Could not pair"}finally{busy=false}
    }}
    fun disconnect(){
        val oldBase=base;val oldToken=token
        scope.launch(Dispatchers.IO){try{
            val req=Request.Builder().url(oldBase+"/api/device/disconnect").header("Authorization","Bearer $oldToken").post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().close()
        }catch(_:Exception){}}
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("pocket-push-registration")
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("pocket-attention")
        PocketLive.stop();token="";base="";connected=false;tasks=emptyList();detail=null;selected=null;notifications=emptyList();lastNotification=0;pushStatus="Not paired";prefs.edit().clear().apply()
    }
    fun refresh(){scope.launch{
        try{
            val r=api("/api/status");codexOnline=r.optBoolean("connected");host=r.s("host")
            prefs.edit().putString("deviceId",r.s("deviceId")).apply()
            PocketPush.configure(r.optJSONObject("firebase"),r.optJSONObject("push")?.optBoolean("registered")==true)
            tasks=api("/api/threads").optJSONArray("threads")?.objects()?.map{Task(it.s("id"),it.s("name"),it.s("cwd"),it.optJSONObject("status")?.s("type")?:"idle",it.optLong("updatedAt"),it.optBoolean("watched"))}?:emptyList()
            notifications=api("/api/notifications").optJSONArray("notifications")?.objects()?.reversed()?:emptyList()
            val latestAttention=api("/api/attention").optJSONArray("notifications")?.objects()?:emptyList()
            val activeIds=latestAttention.map{it.optLong("id")}.toSet()
            prefs.all.keys.filter{it.startsWith("attention:")}.mapNotNull{it.substringAfter(':').toLongOrNull()}.filter{it !in activeIds}.forEach{PocketAttention.dismiss(it)}
            attention=latestAttention
            error=""
        }catch(e:Exception){error=e.message?:"Unable to reach your workstation"}
    }}
    fun open(id:String){selected=id;newTask=false;detail=null;error="";tab=0;PocketTranscript.reset(id);refreshDetail()}
    fun refreshDetail(){scope.launch{PocketTranscript.load()}}
    fun scheduleRefresh(){if(refreshJob?.isActive==true)return;refreshJob=scope.launch{delay(400);PocketTranscript.load()}}
    fun composeTask(){newTask=true;error="";startStatus=if(prefs.contains("newTaskRequest"))"A task request is saved. Check its status to continue." else "";scope.launch{try{projects=api("/api/projects").optJSONArray("projects")?.objects()?:emptyList()}catch(e:Exception){error=e.message?:"Could not load projects"}}}
    fun startTask(cwd:String,prompt:String){if(starting)return;scope.launch{
        starting=true;error="";startStatus="Starting on your workstation…"
        val previous=prefs.getString("newTaskRequest",null)?.let{JSONObject(it)}
        val request=if(previous?.s("cwd")==cwd&&previous.s("prompt")==prompt)previous else JSONObject().put("id",UUID.randomUUID().toString()).put("cwd",cwd).put("prompt",prompt)
        prefs.edit().putString("newTaskRequest",request.toString()).commit()
        var submitted=false
        try{
            var result=api("/api/threads",request)
            submitted=true
            var attempts=0
            while(result.s("state") in listOf("queued","creating")&&attempts++<45){startStatus=if(result.s("state")=="queued")"Waiting for your workstation…" else "Creating your session…";delay(1000);result=api("/api/session-starts/${request.s("id")}")}
            when(result.s("state")){
                "started"->{prefs.edit().remove("newTaskRequest").remove("newTaskPrompt").putString("lastProject",cwd).apply();open(result.getString("thread_id"));refresh()}
                "failed"->{prefs.edit().remove("newTaskRequest").apply();error=result.s("error","Session creation failed. You can correct the request and try again.");startStatus=""}
                "unknown"->{error=result.s("error","Could not confirm session creation. Check recent tasks.");startStatus="Check recent tasks before trying again. This request may already have started."}
                else->startStatus="Still queued. Check status to continue without creating a duplicate."
            }
        }catch(e:Exception){
            error=e.message?:"Could not reach the workstation"
            if(!submitted&&e is PocketApiException&&e.status in listOf(400,401,403,413)){
                prefs.edit().remove("newTaskRequest").apply();startStatus=""
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
    fun reply(text:String,threadId:String?=selected,onDone:()->Unit={}){if(threadId==null||text.isBlank())return;scope.launch{
        sending=true;error=""
        try{api("/api/threads/$threadId/reply",JSONObject().put("text",text).put("id",UUID.randomUUID().toString()));onDone();scheduleRefresh()}
        catch(e:Exception){error=e.message?:"Reply not sent"}finally{sending=false}
    }}
    fun watch(enabled:Boolean){val id=selected?:return;scope.launch{try{api("/api/threads/$id/watch",JSONObject().put("enabled",enabled));refreshDetail()}catch(e:Exception){error=e.message?:"Could not update notifications"}}}
    fun answer(id:String,body:JSONObject){scope.launch{try{api("/api/requests/$id/answer",body);refreshDetail();refresh()}catch(e:Exception){error=e.message?:"Could not answer"}}}
    fun test(){scope.launch{try{api("/api/test-notification",JSONObject())}catch(e:Exception){error=e.message?:"Test failed"}}}
    fun event(json:JSONObject){scope.launch{
        when(json.s("type")){
            "status" -> codexOnline=json.optBoolean("connected")
            "notification" -> {val n=json.getJSONObject("notification");acceptNotification(n,"socket");refresh()}
            "reply" -> {if(json.s("state") in listOf("failed","unknown"))error=json.s("error","Reply could not be confirmed");scheduleRefresh()}
            "attentionResolved" -> {val ids=json.optJSONArray("ids");if(ids!=null)for(i in 0 until ids.length())PocketAttention.dismiss(ids.optLong(i));refresh();scheduleRefresh()}
            "timeline" -> {PocketTranscript.apply(json);if(json.has("turn"))scheduleRefresh()}
            "sessionStarted" -> refresh()
            "codex" -> {val e=json.optJSONObject("event");if(e?.has("id")==true||e?.s("method") in listOf("turn/completed","thread/status/changed"))scheduleRefresh()}
        }
    }}
    @Synchronized fun acceptNotification(n:JSONObject,transport:String="history"){
        val id=n.optLong("id");if(id<=0)return
        val seen=prefs.getStringSet("seenIds",emptySet())!!.toMutableSet()
        if(!seen.add(id.toString()))return
        val bounded=seen.sortedByDescending{it.toLongOrNull()?:0}.take(512).toSet()
        prefs.edit().putStringSet("seenIds",bounded).putString("lastDeliveryTransport",transport).putLong("lastDeliveryId",id).putLong("lastDeliveryAt",System.currentTimeMillis()).apply()
        PocketNotifications.show(context,n)
    }
    fun catchUp(){scope.launch{try{
        val after=if(prefs.getBoolean("needsHistorySync",false))0 else lastNotification
        val r=api("/api/notifications?after=$after")
        val entries=r.optJSONArray("notifications")?.objects()?:emptyList()
        entries.forEach{acceptNotification(it)}
        entries.maxOfOrNull{it.optLong("id")}?.let{lastNotification=maxOf(lastNotification,it)}
        prefs.edit().putLong("lastNotification",lastNotification).putBoolean("needsHistorySync",false).apply()
    }catch(_:Exception){}}}
}
