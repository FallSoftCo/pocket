package co.fallsoft.pocket

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PocketPush {
    @Synchronized fun initialize():Boolean {
        val raw=Pocket.prefs.getString("firebase",null)?:return false
        val config=JSONObject(raw)
        val existing=FirebaseApp.getApps(Pocket.context).firstOrNull{it.name==FirebaseApp.DEFAULT_APP_NAME}
        if(existing?.options?.applicationId==config.s("applicationId"))return true
        existing?.delete()
        val options=FirebaseOptions.Builder().setApplicationId(config.getString("applicationId"))
            .setApiKey(config.getString("apiKey")).setProjectId(config.getString("projectId"))
            .setGcmSenderId(config.getString("senderId")).build()
        FirebaseApp.initializeApp(Pocket.context,options)
        return true
    }
    fun configure(config:JSONObject?,registered:Boolean=false){
        if(config==null){Pocket.pushStatus="Firebase is not configured on your server";return}
        val changed=Pocket.prefs.getString("firebase",null)!=config.toString()
        Pocket.prefs.edit().putString("firebase",config.toString()).apply()
        try{
            initialize()
            if(changed||!registered||!Pocket.prefs.getBoolean("pushReady",false)){
                Pocket.pushStatus="Registering notifications…";PushRegistrationWorker.enqueue(Pocket.context)
            }else Pocket.pushStatus="Firebase push is ready"
        }catch(_:Exception){Pocket.pushStatus="Could not initialize Firebase"}
    }
}

class PushRegistrationWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        if(Pocket.token.isBlank()||!PocketPush.initialize())return Result.success()
        return try{
            val token=Tasks.await(FirebaseMessaging.getInstance().token,20,TimeUnit.SECONDS)
            val config=JSONObject(Pocket.prefs.getString("firebase","{}")!!)
            runBlocking {Pocket.api("/api/device/push",JSONObject().put("token",token).put("projectId",config.getString("projectId")))}
            Pocket.prefs.edit().putBoolean("pushReady",true).apply()
            Pocket.scope.launch{Pocket.pushStatus="Firebase push is ready"}
            Result.success()
        }catch(_:Exception){
            Pocket.prefs.edit().putBoolean("pushReady",false).apply()
            Pocket.scope.launch{Pocket.pushStatus="Notification registration will retry when connected"}
            Result.retry()
        }
    }
    companion object {
        fun enqueue(context:Context){
            val request=OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pocket-push-registration",ExistingWorkPolicy.KEEP,request)
        }
    }
}

class PocketFirebaseService:FirebaseMessagingService(){
    override fun onNewToken(token:String){
        if(Pocket.token.isNotBlank()){
            Pocket.prefs.edit().putBoolean("pushReady",false).apply()
            PushRegistrationWorker.enqueue(this)
        }
    }
    override fun onMessageReceived(message:RemoteMessage){
        if(Pocket.token.isBlank()||message.data["device_id"]!=Pocket.prefs.getString("deviceId",null))return
        if(message.data["operations"]=="failure"){
            PocketNotifications.operations(this,message.notification?.title?:"Pocket needs attention",message.notification?.body?:"Check GitHub Actions for details.")
            return
        }
        val n=JSONObject(message.data)
        if(n.optLong("id")<=0)return
        // Render immediately within FCM's execution window. No network request is needed.
        Pocket.acceptNotification(n,"fcm")
        Log.i("PocketPush","Received FCM notification ${n.optLong("id")}")
    }
    override fun onDeletedMessages(){Pocket.prefs.edit().putBoolean("needsHistorySync",true).apply()}
}

/** Durable inline replies survive process death and use one stable server idempotency key. */
class ReplyDeliveryWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val id=inputData.getString("id")?:return Result.success()
        val raw=Pocket.prefs.getString("outbox:$id",null)?:return Result.success()
        if(Pocket.token.isBlank())return Result.success()
        val reply=JSONObject(raw);val thread=reply.getString("thread")
        return try{
            val response=runBlocking{
                var status=Pocket.api("/api/threads/$thread/reply",JSONObject().put("id",id).put("text",reply.getString("text")))
                repeat(5){if(status.s("state") in listOf("queued","sending")){kotlinx.coroutines.delay(1000);status=Pocket.api("/api/replies/$id")}}
                status
            }
            when(response.s("state")){
                "accepted"->{
                    update(reply,"Reply sent to Codex")
                    val edit=Pocket.prefs.edit().remove("outbox:$id")
                    if(Pocket.prefs.getString("draft:$thread",null)==reply.getString("text"))edit.remove("draft:$thread")
                    edit.apply();PocketAttention.dismiss((reply.getInt("notificationId")-1000).toLong())
                    // Dismiss the old attention state, then retain this delivery confirmation.
                    update(reply,"Reply sent to Codex");Result.success()
                }
                "failed","unknown"->{
                    Pocket.prefs.edit().putString("draft:$thread",reply.getString("text")).apply()
                    update(reply,"Reply needs attention · open Pocket");Result.failure()
                }
                else->{update(reply,"Reply queued · waiting for Codex");Result.retry()}
            }
        }catch(_:Exception){
            Pocket.prefs.edit().putString("draft:$thread",reply.getString("text")).apply()
            update(reply,if(runAttemptCount<8)"Reply saved · waiting for your workstation" else "Reply needs attention · open Pocket")
            if(runAttemptCount<8)Result.retry()else Result.failure()
        }
    }
    private fun update(reply:JSONObject,title:String){
        val id=reply.getInt("notificationId")
        val b=androidx.core.app.NotificationCompat.Builder(applicationContext,"work").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(reply.getString("text")).setSilent(true).setAutoCancel(true)
            .setContentIntent(PocketNotifications.open(applicationContext,reply.getString("thread"),id))
        try{androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(id,b.build())}catch(_:SecurityException){}
    }
    companion object{
        fun enqueue(c:Context,id:String){
            val request=OneTimeWorkRequestBuilder<ReplyDeliveryWorker>().setInputData(workDataOf("id" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            WorkManager.getInstance(c).enqueueUniqueWork("pocket-reply-$id",ExistingWorkPolicy.KEEP,request)
        }
    }
}
