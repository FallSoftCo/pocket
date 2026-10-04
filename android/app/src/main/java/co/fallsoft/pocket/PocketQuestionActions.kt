package co.fallsoft.pocket

import android.content.Context
import androidx.work.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Durable question delivery keeps the originating profile even when the user switches servers. */
object PocketQuestionActions {
    fun skipRequest(requestId:String)=enqueue(Pocket.context,"request",requestId,Pocket.local)
    fun skipNotification(id:Long,local:Boolean=Pocket.local)=enqueue(Pocket.context,"notification",id.toString(),local)
    private fun enqueue(c:Context,kind:String,id:String,local:Boolean){
        val endpoint=Pocket.savedBase(local);val token=Pocket.savedToken(local)
        if(endpoint.isBlank()||token.isBlank())return
        val key="question-action:$local:$kind:$id"
        val payload=JSONObject().put("kind",kind).put("id",id).put("local",local).put("endpoint",endpoint).put("token",token)
        // Credentials stay in private app storage rather than WorkManager's input data/logs.
        Pocket.prefs.edit().putString(key,payload.toString()).commit()
        val work=OneTimeWorkRequestBuilder<QuestionActionWorker>().setInputData(workDataOf("key" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork(key,ExistingWorkPolicy.KEEP,work)
    }
}
class QuestionActionWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val key=inputData.getString("key")?:return Result.failure()
        val raw=Pocket.prefs.getString(key,null)?:return Result.success()
        val payload=JSONObject(raw);val local=payload.optBoolean("local");val endpoint=payload.getString("endpoint");val token=payload.getString("token")
        if(Pocket.savedBase(local)!=endpoint||Pocket.savedToken(local)!=token){Pocket.prefs.edit().remove(key).commit();return Result.failure()}
        val id=payload.getString("id");val notification=payload.getString("kind")=="notification"
        val path=if(notification)"/api/notifications/$id/skip" else "/api/requests/$id/answer"
        val request=Request.Builder().url(endpoint.trimEnd('/')+path).header("Authorization","Bearer $token")
            .post(JSONObject().put("skip",true).toString().toRequestBody("application/json".toMediaType())).build()
        try {
            Pocket.http.newCall(request).execute().use{response->
                if(!response.isSuccessful){
                    if(response.code>=500||response.code==408||response.code==429)return Result.retry()
                    // A disappeared native request is already resolved. Never invent an answer.
                    if(!(response.code==409&&!notification)){
                        val cached=if(notification)Pocket.prefs.getString(Pocket.key("attention:$id",local),null) else null
                        val thread=cached?.let{JSONObject(it).s("thread_id")}
                        PocketNotifications.channels(applicationContext)
                        val notice=androidx.core.app.NotificationCompat.Builder(applicationContext,"work").setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("Question could not be skipped").setContentText("Open the session to review the request.")
                            .setContentIntent(PocketNotifications.open(applicationContext,thread,901,local)).setAutoCancel(true)
                        try{androidx.core.app.NotificationManagerCompat.from(applicationContext).notify("question-delivery",901,notice.build())}catch(_:SecurityException){}
                        Pocket.prefs.edit().remove(key).commit();return Result.failure()
                    }
                }
                val result=try{JSONObject(response.body?.string()?:"{}")}catch(_:Exception){return Result.retry()}
                if(response.isSuccessful&&!result.optBoolean("ok"))return Result.retry()
                // Submission alone does not dismiss attention; the server resolves it authoritatively.
                if(notification&&result.optBoolean("expired"))PocketAttention.dismiss(id.toLong(),local)
                Pocket.prefs.edit().remove(key).commit();return Result.success()
            }
        }catch(_:Exception){return Result.retry()}
    }
}
