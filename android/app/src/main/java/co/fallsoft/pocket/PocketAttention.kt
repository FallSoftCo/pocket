package co.fallsoft.pocket

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.work.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PocketAttention {
    var revision by mutableIntStateOf(0); private set
    var enabled by mutableStateOf(true); private set
    fun init(){enabled=Pocket.prefs.getBoolean("remindersEnabled",true)}
    fun needs(n:JSONObject)=n.s("thread_id").isNotBlank()&&n.s("kind") in listOf("question","approval","error")
    fun dismissed(id:Long):Boolean {revision;return Pocket.prefs.getBoolean("dismissed:$id",false)}
    fun remember(n:JSONObject){
        val id=n.optLong("id");if(!needs(n)||dismissed(id)||Pocket.prefs.contains("attention:$id"))return
        Pocket.prefs.edit().putString("attention:$id",n.toString()).commit()
        if(enabled)schedule(id,0,5)
    }
    fun action(c:Context,id:Long,action:String)=PendingIntent.getBroadcast(c,(id+1000).toInt(),Intent(c,AttentionReceiver::class.java).setAction(action).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun dismiss(id:Long){
        Pocket.prefs.edit().putBoolean("dismissed:$id",true).remove("attention:$id").apply();revision++
        WorkManager.getInstance(Pocket.context).cancelUniqueWork("pocket-attention-$id")
        Pocket.context.getSystemService(NotificationManager::class.java).cancel((id+1000).toInt())
    }
    fun snooze(id:Long){
        Pocket.context.getSystemService(NotificationManager::class.java).cancel((id+1000).toInt())
        Pocket.prefs.edit().putLong("snoozed:$id",System.currentTimeMillis()+30*60000).apply();revision++
        if(enabled)schedule(id,0,30)
    }
    fun toggle(value:Boolean){
        enabled=value;Pocket.prefs.edit().putBoolean("remindersEnabled",value).apply()
        if(!value)WorkManager.getInstance(Pocket.context).cancelAllWorkByTag("pocket-attention")
        else Pocket.prefs.all.keys.filter{it.startsWith("attention:")}.forEach{schedule(it.substringAfter(':').toLong(),0,5)}
    }
    fun schedule(id:Long,stage:Int,minutes:Long,append:Boolean=false){
        val request=OneTimeWorkRequestBuilder<AttentionWorker>().setInputData(workDataOf("id" to id,"stage" to stage))
            .setInitialDelay(minutes,TimeUnit.MINUTES).addTag("pocket-attention")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
        WorkManager.getInstance(Pocket.context).enqueueUniqueWork("pocket-attention-$id",if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,request)
    }
}
class AttentionReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context,i:Intent){val id=i.getLongExtra("id",0);if(id<=0)return
        if(i.action=="snooze")PocketAttention.snooze(id)else PocketAttention.dismiss(id)
    }
}
class AttentionWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val id=inputData.getLong("id",0);val stage=inputData.getInt("stage",0)
        if(!PocketAttention.enabled||Pocket.token.isBlank()||PocketAttention.dismissed(id))return Result.success()
        val raw=Pocket.prefs.getString("attention:$id",null)?:return Result.success()
        // Check current Codex state before repeating a prompt already answered at the terminal.
        val current=try{runBlocking{Pocket.api("/api/notifications/$id/attention")}}catch(_:Exception){return Result.retry()}
        if(!current.optBoolean("needsAttention")){PocketAttention.dismiss(id);return Result.success()}
        if(PocketAttention.dismissed(id))return Result.success()
        val n=JSONObject(raw)
        PocketNotifications.show(applicationContext,n,reminder=true)
        Pocket.prefs.edit().putLong("lastReminderId",id).putInt("lastReminderStage",stage).putLong("lastReminderAt",System.currentTimeMillis()).apply()
        if(stage<2)PocketAttention.schedule(id,stage+1,if(stage==0)15 else 30,append=true)
        return Result.success()
    }
}
