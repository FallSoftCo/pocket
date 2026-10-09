package co.fallsoft.pocket

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.compose.runtime.*

object PocketNotifications {
    private data class ImmersedNotice(val original:String,val profile:String,val reminder:Boolean)
    private val immersedNotices=linkedMapOf<Int,ImmersedNotice>()
    private fun immersionProfile()="${Pocket.local}:${Pocket.base}:${Pocket.prefs.getString(Pocket.key("deviceId"),"")}"
    /** A translation arriving later updates the same unread notification without another sound. */
    fun refreshImmersion(){
        val profile=immersionProfile()
        val active=try{Pocket.context.getSystemService(NotificationManager::class.java).activeNotifications.map{it.id}.toSet()}catch(_:Exception){return}
        val notices=synchronized(immersedNotices){immersedNotices.entries.removeAll{it.value.profile!=profile||it.key !in active};immersedNotices.values.toList()}
        notices.forEach{saved->try{val n=JSONObject(saved.original);if(!PocketNotificationReads.isRead(n))show(Pocket.context,n,reminder=saved.reminder,translationRefresh=true)}catch(_:Exception){}}
    }
    private fun immersed(id:String,text:String):String {PocketImmersion.offer(id,text,"notification urgent");return PocketImmersion.target(id,text)}

    fun operations(c:Context,title:String,body:String){
        channels(c)
        val intent=Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/FallSoftCo/pocket/actions"))
        val open=PendingIntent.getActivity(c,900, intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=NotificationCompat.Builder(c,"work").setSmallIcon(R.drawable.ic_notification).setContentTitle(immersed("operations:title",title)).setContentText(immersed("operations:body",body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(PocketImmersion.target("operations:body",body))).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_ERROR)
        try{NotificationManagerCompat.from(c).notify("pocket-operations",900,b.build())}catch(_:SecurityException){}
    }
    fun channels(c:Context){
        val m=c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel("work",PocketImmersion.label("Codex updates & replies"),NotificationManager.IMPORTANCE_HIGH).apply{description=PocketImmersion.label("Updates you request from Codex, questions, and replies to your phone messages.")})
    }
    fun open(c:Context,thread:String?,code:Int,local:Boolean=Pocket.local,coordinator:Boolean=false,environment:String=Pocket.environment(local)):PendingIntent=PendingIntent.getActivity(c,code,Intent(c,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP;data=Pocket.notificationIntent(environment,"open",code);putExtra("thread",thread);putExtra("local",local);putExtra("environment",environment);if(coordinator){putExtra("coordinatorReport",true);putExtra("reportNotificationId",code)}},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun show(c:Context,n:JSONObject,reminder:Boolean=false,translationRefresh:Boolean=false){
        PocketNotificationTitles.remember(n)
        if(PocketNotificationReads.isRead(n))return
        channels(c)
        val local=n.optBoolean("_local",Pocket.local);val environment=n.s("_environment",Pocket.environment(local));val id=(if(n.s("kind")=="coordinator_report")499998 else n.optLong("id").toInt())+(if(local)500000 else 1000); val thread=n.s("thread_id").takeIf{it.isNotBlank()}
        if(n.s("kind")=="coordinator_report"){
            val manager=c.getSystemService(NotificationManager::class.java);val profile=PocketNotificationTitles.extras(n).getString("nextcompProfile")
            val previous=manager.activeNotifications.firstOrNull{it.id==id&&it.notification.extras.getString("nextcompProfile")==profile}
            if((previous?.notification?.extras?.getLong("nextcompReportAt")?:0)>n.optLong("created_at"))return
            manager.activeNotifications.filter{it.id!=id&&it.notification.extras.getString("nextcompProfile")==profile&&it.notification.extras.getString("nextcompThread").orEmpty().isBlank()&&(it.notification.extras.getString("nextcompKind")=="coordinator_report"||it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()=="NextComp check-in")}.forEach{manager.cancel(Pocket.notificationTag(environment),it.id)}
        }
        val attention=PocketAttention.needs(n)&&!PocketAttention.dismissed(n.optLong("id"),local,environment)
        val sameProfile=environment==Pocket.environmentId
        if(sameProfile){synchronized(immersedNotices){immersedNotices[id]=ImmersedNotice(n.toString(),immersionProfile(),reminder);while(immersedNotices.size>30)immersedNotices.remove(immersedNotices.keys.first())}}
        val title=if(sameProfile)immersed("notification-title:${n.optLong("id")}",n.s("title"))else n.s("title")
        val body=if(sameProfile)immersed("notification-body:${n.optLong("id")}",n.s("body"))else n.s("body")
        if(sameProfile&&PocketImmersion.enabled){val spoken=PocketSpeech.text(n);PocketImmersion.offer("notification-speech:${n.optLong("id")}",spoken,"speech urgent")}

        val b=NotificationCompat.Builder(c,PocketAudio.channel(c,n.s("kind"))).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body)
            .addExtras(PocketNotificationTitles.extras(n).apply{putString("nextcompKind",n.s("kind"));if(n.s("kind")=="coordinator_report")putLong("nextcompReportAt",n.optLong("created_at"))})
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xffffc600.toInt()).setAutoCancel(!attention&&n.s("kind")!="coordinator_report").setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open(c,thread,id,local,n.s("kind")=="coordinator_report",environment))
        if(n.s("kind")=="coordinator_report"||translationRefresh||PocketSpeech.count>0&&!PocketSpeech.paused)b.setSilent(true)
        b.setOnlyAlertOnce(true)
        if(reminder)b.setSubText(PocketImmersion.label("Still needs your attention"))
        if(attention){PocketAttention.remember(n);b.setDeleteIntent(PocketAttention.action(c,n.optLong("id"),"dismiss",local,environment))}
        if(thread!=null&&n.s("kind")=="question"){
            b.addAction(R.drawable.ic_notification,PocketImmersion.label("Answer"),open(c,thread,id,local,n.s("kind")=="coordinator_report",environment))
            if(attention)b.addAction(R.drawable.ic_notification,PocketImmersion.label("Skip"),PocketAttention.action(c,n.optLong("id"),"skip",local,environment))
            if(attention)b.addAction(R.drawable.ic_notification,PocketImmersion.label("Later · 30m"),PocketAttention.action(c,n.optLong("id"),"snooze",local,environment))
        }else if(thread!=null&&!n.optBoolean("canAcceptDirectInput",true)){
            val parent=n.s("parentThreadId").takeIf{it.isNotBlank()}
            if(parent!=null)b.addAction(R.drawable.ic_notification,PocketImmersion.label("Guide parent"),open(c,parent,id+100000,local,environment=environment))
            b.addAction(R.drawable.ic_notification,PocketImmersion.label("Review agent"),open(c,thread,id,local,environment=environment))
            if(attention)b.addAction(R.drawable.ic_notification,PocketImmersion.label("Later · 30m"),PocketAttention.action(c,n.optLong("id"),"snooze",local,environment))
        }else if(thread!=null){
            val intent=Intent(c,ReplyReceiver::class.java).setData(Pocket.notificationIntent(environment,"reply",id)).putExtra("thread",thread).putExtra("notificationId",id).putExtra("notificationDbId",n.optLong("id")).putExtra("local",local).putExtra("environment",environment)
            val pi=PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification,PocketImmersion.label("Reply"),pi)
                .addRemoteInput(RemoteInput.Builder("reply").setLabel(PocketImmersion.label("Your reply…")).build()).setAllowGeneratedReplies(false).build())
            b.addAction(R.drawable.ic_notification,PocketImmersion.label("Open task"),open(c,thread,id,local,n.s("kind")=="coordinator_report",environment))
            if(attention)b.addAction(R.drawable.ic_notification,PocketImmersion.label("Later · 30m"),PocketAttention.action(c,n.optLong("id"),"snooze",local,environment))
        }
        try{NotificationManagerCompat.from(c).notify(Pocket.notificationTag(environment),id,b.build())}catch(_:SecurityException){}
    }
}

/** Live conversation events only; Firebase handles notifications while the app is closed. */
object PocketLive {
    var outageVisible by androidx.compose.runtime.mutableStateOf(false);private set
    private val client=Pocket.http.newBuilder().pingInterval(25,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build()
    private var ws:WebSocket?=null
    private var retry:Job?=null
    private var disconnectNotice:Job?=null
    private var active=false
    private var attempt=0
    fun start(){if(active)return;active=true;connect()}
    fun retryNow(){stop();start()}
    fun stop(){active=false;retry?.cancel();disconnectNotice?.cancel();outageVisible=false;val old=ws;ws=null;old?.close(1000,"App in background")}
    private fun connect(){
        if(!active||Pocket.token.isBlank())return
        retry?.cancel()
        val eventBase=Pocket.base.replaceFirst("https://","wss://").replaceFirst("http://","ws://")
        val request=Request.Builder().url(eventBase+"/events").header("Authorization","Bearer ${Pocket.token}").build()
        ws=client.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){Pocket.scope.launch{if(webSocket!==ws)return@launch;Pocket.catchUp();Pocket.refresh();Pocket.refreshDetail()}}
            override fun onMessage(webSocket:WebSocket,text:String){Pocket.scope.launch{if(webSocket!==ws)return@launch;try{val event=JSONObject(text);disconnectNotice?.cancel();outageVisible=false;attempt=0;Pocket.connected=true;Pocket.connectionError="";Pocket.event(event)}catch(_:Exception){}}}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){reconnect(webSocket,PocketNetwork.error(t))}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){reconnect(webSocket)}
        })
    }
    private fun reconnect(socket:WebSocket,error:String="Connection lost. Retrying…"){Pocket.scope.launch{
        if(socket!==ws)return@launch
        ws=null
        if(disconnectNotice?.isActive!=true)disconnectNotice=Pocket.scope.launch{
            delay(8_000)
            if(active){Pocket.connected=false;Pocket.connectionError=error;outageVisible=true}
        }
        if(active){retry?.cancel();retry=Pocket.scope.launch{delay((2000L*(++attempt)).coerceAtMost(30000));connect()}}
    }}
}
class ReplyReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context,i:Intent){
        val text=RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString()?.trim()?:return
        val thread=i.getStringExtra("thread")?:return
        val local=i.getBooleanExtra("local",false);val environment=i.getStringExtra("environment")?:if(local)"phone" else "workstation"
        val destination=Pocket.captureEnvironment(environment)?:return
        if(text.isEmpty())return
        val notificationId=i.getIntExtra("notificationId",0)
        val id=UUID.randomUUID().toString()
        val payload=JSONObject().put("id",id).put("thread",thread).put("text",text).put("notificationId",notificationId).put("notificationDbId",i.getLongExtra("notificationDbId",0)).put("local",local).put("environment",environment).put("endpoint",destination.endpoint).put("token",destination.token)
        Pocket.prefs.edit().putString("outbox:$id",payload.toString()).commit()
        ReplyDeliveryWorker.enqueue(c,id)
        val b=NotificationCompat.Builder(c,"work").setSmallIcon(R.drawable.ic_notification).setContentTitle(PocketImmersion.label("Sending reply to Codex…")).setContentText(text).setContentIntent(PocketNotifications.open(c,thread,notificationId,local,environment=environment)).setAutoCancel(true).setSilent(true)
        try{NotificationManagerCompat.from(c).notify(Pocket.notificationTag(environment),notificationId,b.build())}catch(_:SecurityException){}
    }
}
