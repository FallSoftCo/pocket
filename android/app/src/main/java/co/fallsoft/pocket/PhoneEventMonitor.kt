package co.fallsoft.pocket

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A notification-only local socket, independent of the selected remote UI socket. */
internal object PhoneEventMonitor {
    private val client=Pocket.http.newBuilder().pingInterval(25,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
    private var destination:EnvironmentRequest?=null
    private var socket:WebSocket?=null
    private var retry:Job?=null
    private var active=false
    private var attempts=0
    var connected=false;private set
    fun start(){
        val next=Pocket.captureEnvironment("phone")?:return
        if(active&&destination?.matches(next)==true)return
        stop();destination=next;active=true;connect()
    }
    fun stop(){active=false;connected=false;retry?.cancel();retry=null;val old=socket;socket=null;old?.close(1000,"Local monitor stopped");destination=null;attempts=0}
    private fun current(ws:WebSocket,d:EnvironmentRequest)=active&&socket===ws&&Pocket.captureEnvironment("phone")?.matches(d)==true
    private fun watermark(id:Long){if(id<=0)return;val key=Pocket.environmentKey("lastNotification","phone");Pocket.prefs.edit().putLong(key,maxOf(Pocket.prefs.getLong(key,0),id)).apply();if(Pocket.environmentId=="phone")Pocket.lastNotification=maxOf(Pocket.lastNotification,id)}
    private fun notification(n:JSONObject){Pocket.acceptNotification(n.put("_local",true).put("_environment","phone"),"socket");watermark(n.optLong("id"))}
    private fun connect(){
        val d=destination?:return;if(!active)return
        if(Pocket.captureEnvironment("phone")?.matches(d)!=true){stop();return}
        val request=Request.Builder().url(d.endpoint.replaceFirst("https://","wss://").replaceFirst("http://","ws://").trimEnd('/')+"/events").header("Authorization","Bearer ${d.token}").build()
        socket=client.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(ws:WebSocket,response:Response){Pocket.scope.launch{
                if(!current(ws,d))return@launch;connected=true;attempts=0
                try{val after=Pocket.prefs.getLong(Pocket.environmentKey("lastNotification","phone"),0);val result=Pocket.apiEnvironment(d,"/api/notifications?after=$after");if(current(ws,d))result.optJSONArray("notifications")?.objects().orEmpty().forEach{notification(it)}}catch(_:Exception){}
            }}
            override fun onMessage(ws:WebSocket,text:String){Pocket.scope.launch{
                if(!current(ws,d))return@launch
                runCatching{val event=JSONObject(text);when(event.s("type")){
                    "notification","coordinatorReport"->event.optJSONObject("notification")?.let{notification(it)}
                    "threadRenamed"->PocketNotificationTitles.rename(event.s("threadId"),event.s("name"),event.optJSONArray("notificationIds")?.let{a->(0 until a.length()).map{a.optLong(it)}}?:emptyList(),true,event.optLong("revision"),"phone")
                    "attentionResolved"->event.optJSONArray("ids")?.let{a->(0 until a.length()).forEach{PocketAttention.dismiss(a.optLong(it),true,"phone")}}
                }}
            }}
            override fun onFailure(ws:WebSocket,t:Throwable,response:Response?)=reconnect(ws,d)
            override fun onClosed(ws:WebSocket,code:Int,reason:String)=reconnect(ws,d)
            override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
        })
    }
    private fun reconnect(ws:WebSocket,d:EnvironmentRequest){Pocket.scope.launch{if(!current(ws,d))return@launch;connected=false;socket=null;retry?.cancel();retry=Pocket.scope.launch{delay((2000L*(++attempts)).coerceAtMost(30000));connect()}}}
}
