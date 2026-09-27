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

object PocketNotifications {
    fun operations(c:Context,title:String,body:String){
        channels(c)
        val intent=Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/FallSoftCo/pocket/actions"))
        val open=PendingIntent.getActivity(c,900, intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b=NotificationCompat.Builder(c,"work").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setCategory(NotificationCompat.CATEGORY_ERROR)
        try{NotificationManagerCompat.from(c).notify("pocket-operations",900,b.build())}catch(_:SecurityException){}
    }
    fun channels(c:Context){
        val m=c.getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(NotificationChannel("work","Codex updates & replies",NotificationManager.IMPORTANCE_HIGH).apply{description="Updates you request from Codex, questions, and replies to your phone messages."})
    }
    fun open(c:Context,thread:String?,code:Int):PendingIntent=PendingIntent.getActivity(c,code,Intent(c,MainActivity::class.java).apply{flags=Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP;putExtra("thread",thread)},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun show(c:Context,n:JSONObject,reminder:Boolean=false){
        channels(c)
        val id=n.optLong("id").toInt()+1000; val thread=n.s("thread_id").takeIf{it.isNotBlank()}
        val attention=PocketAttention.needs(n)&&!PocketAttention.dismissed(n.optLong("id"))
        val b=NotificationCompat.Builder(c,PocketAudio.channel(c,n.s("kind"))).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.s("title")).setContentText(n.s("body"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.s("body")))
            .setColor(0xffb3f5cb.toInt()).setAutoCancel(!attention).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(open(c,thread,id))
        if(reminder)b.setSubText("Still needs your attention")
        if(attention){PocketAttention.remember(n);b.setDeleteIntent(PocketAttention.action(c,n.optLong("id"),"dismiss"))}
        if(thread!=null){
            val intent=Intent(c,ReplyReceiver::class.java).putExtra("thread",thread).putExtra("notificationId",id)
            val pi=PendingIntent.getBroadcast(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notification,"Reply",pi)
                .addRemoteInput(RemoteInput.Builder("reply").setLabel("Your reply…").build()).setAllowGeneratedReplies(false).build())
            b.addAction(R.drawable.ic_notification,"Open task",open(c,thread,id))
            if(attention)b.addAction(R.drawable.ic_notification,"Later · 30m",PocketAttention.action(c,n.optLong("id"),"snooze"))
        }
        try{NotificationManagerCompat.from(c).notify(id,b.build())}catch(_:SecurityException){}
    }
}

/** Live conversation events only; Firebase handles notifications while the app is closed. */
object PocketLive {
    private val client=OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).connectTimeout(15,TimeUnit.SECONDS).build()
    private var ws:WebSocket?=null
    private var retry:Job?=null
    private var active=false
    private var attempt=0
    fun start(){if(active)return;active=true;connect()}
    fun stop(){active=false;retry?.cancel();val old=ws;ws=null;old?.close(1000,"App in background");Pocket.connected=false}
    private fun connect(){
        if(!active||Pocket.token.isBlank())return
        retry?.cancel()
        val request=Request.Builder().url(Pocket.base.replaceFirst("https://","wss://")+"/events").header("Authorization","Bearer ${Pocket.token}").build()
        ws=client.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){Pocket.scope.launch{if(webSocket!==ws)return@launch;attempt=0;Pocket.connected=true;Pocket.catchUp();Pocket.refresh();Pocket.refreshDetail()}}
            override fun onMessage(webSocket:WebSocket,text:String){if(webSocket===ws)try{Pocket.event(JSONObject(text))}catch(_:Exception){}}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){reconnect(webSocket)}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){reconnect(webSocket)}
        })
    }
    private fun reconnect(socket:WebSocket){Pocket.scope.launch{
        if(socket!==ws)return@launch
        Pocket.connected=false
        if(active){retry?.cancel();retry=Pocket.scope.launch{delay((2000L*(++attempt)).coerceAtMost(30000));connect()}}
    }}
}
class ReplyReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context,i:Intent){
        val text=RemoteInput.getResultsFromIntent(i)?.getCharSequence("reply")?.toString()?.trim()?:return
        val thread=i.getStringExtra("thread")?:return
        if(text.isEmpty()||Pocket.token.isBlank())return
        val notificationId=i.getIntExtra("notificationId",0)
        val id=UUID.randomUUID().toString()
        val payload=JSONObject().put("id",id).put("thread",thread).put("text",text).put("notificationId",notificationId)
        Pocket.prefs.edit().putString("outbox:$id",payload.toString()).commit()
        ReplyDeliveryWorker.enqueue(c,id)
        val b=NotificationCompat.Builder(c,"work").setSmallIcon(R.drawable.ic_notification).setContentTitle("Sending reply to Codex…").setContentText(text).setContentIntent(PocketNotifications.open(c,thread,notificationId)).setAutoCancel(true).setSilent(true)
        try{NotificationManagerCompat.from(c).notify(notificationId,b.build())}catch(_:SecurityException){}
    }
}
