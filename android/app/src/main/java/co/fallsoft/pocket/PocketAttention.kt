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
    fun dismissed(id:Long,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)):Boolean {revision;return Pocket.prefs.getBoolean(Pocket.key("dismissed:$id",local,environment),false)}
    fun remember(n:JSONObject){
        val id=n.optLong("id");val local=n.optBoolean("_local",Pocket.local);val environment=n.s("_environment",Pocket.environment(local));if(!needs(n)||dismissed(id,local,environment)||Pocket.prefs.contains(Pocket.key("attention:$id",local,environment)))return
        Pocket.prefs.edit().putString(Pocket.key("attention:$id",local,environment),n.toString()).commit()
        if(enabled)schedule(id,0,5,local=local,environment=environment)
    }
    fun action(c:Context,id:Long,action:String,local:Boolean=Pocket.local,environment:String=Pocket.environment(local))=PendingIntent.getBroadcast(c,(id+(if(local)500000 else 1000)).toInt(),Intent(c,AttentionReceiver::class.java).setData(Pocket.notificationIntent(environment,action,(id+(if(local)500000 else 1000)).toInt())).setAction(action).putExtra("id",id).putExtra("local",local).putExtra("environment",environment),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun dismiss(id:Long,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)){
        Pocket.prefs.edit().putBoolean(Pocket.key("dismissed:$id",local,environment),true).remove(Pocket.key("attention:$id",local,environment)).apply();revision++
        WorkManager.getInstance(Pocket.context).cancelUniqueWork("pocket-attention-$environment-$id")
        Pocket.context.getSystemService(NotificationManager::class.java).cancel(Pocket.notificationTag(environment),(id+(if(local)500000 else 1000)).toInt())
    }
    fun snooze(id:Long,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)){
        Pocket.context.getSystemService(NotificationManager::class.java).cancel(Pocket.notificationTag(environment),(id+(if(local)500000 else 1000)).toInt())
        Pocket.prefs.edit().putLong(Pocket.key("snoozed:$id",local,environment),System.currentTimeMillis()+30*60000).apply();revision++
        if(enabled)schedule(id,0,30,local=local,environment=environment)
    }
    fun toggle(value:Boolean){
        enabled=value;Pocket.prefs.edit().putBoolean("remindersEnabled",value).apply()
        if(!value)WorkManager.getInstance(Pocket.context).cancelAllWorkByTag("pocket-attention")
        else Pocket.environmentProfiles.all().forEach{profile->Pocket.prefs.all.keys.filter{it.startsWith(profile.key("attention:"))}.forEach{schedule(it.substringAfterLast(':').toLong(),0,5,local=profile.local,environment=profile.id)}}
    }
    fun schedule(id:Long,stage:Int,minutes:Long,append:Boolean=false,local:Boolean=Pocket.local,environment:String=Pocket.environment(local)){
        val request=OneTimeWorkRequestBuilder<AttentionWorker>().setInputData(workDataOf("id" to id,"stage" to stage,"local" to local,"environment" to environment))
            .setInitialDelay(minutes,TimeUnit.MINUTES).addTag("pocket-attention").addTag("pocket-attention-$environment")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build()
        WorkManager.getInstance(Pocket.context).enqueueUniqueWork("pocket-attention-$environment-$id",if(append)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,request)
    }
}
class AttentionReceiver:BroadcastReceiver(){
    override fun onReceive(c:Context,i:Intent){if(i.action=="caption-dismiss"){PocketSpeechCaptions.dismiss();return};val id=i.getLongExtra("id",0);val local=i.getBooleanExtra("local",false);val environment=i.getStringExtra("environment")?:if(local)"phone" else "workstation";if(id<=0)return
        when(i.action){"skip"->PocketQuestionActions.skipNotification(id,local,environment);"snooze"->PocketAttention.snooze(id,local,environment);else->PocketAttention.dismiss(id,local,environment)}
    }
}
class AttentionWorker(c:Context,p:WorkerParameters):Worker(c,p){
    override fun doWork():Result {
        val id=inputData.getLong("id",0);val stage=inputData.getInt("stage",0);val local=inputData.getBoolean("local",false);val environment=inputData.getString("environment")?:if(local)"phone" else "workstation"
        if(!PocketAttention.enabled||Pocket.savedToken(local,environment).isBlank()||PocketAttention.dismissed(id,local,environment))return Result.success()
        val raw=Pocket.prefs.getString(Pocket.key("attention:$id",local,environment),null)?:return Result.success()
        // Check current Codex state before repeating a prompt already answered at the terminal.
        val current=try{runBlocking{Pocket.apiFor(local,"/api/notifications/$id/attention",environmentId=environment)}}catch(_:Exception){return Result.retry()}
        if(!current.optBoolean("needsAttention")){PocketAttention.dismiss(id,local,environment);return Result.success()}
        if(PocketAttention.dismissed(id,local,environment))return Result.success()
        val n=JSONObject(raw)
        PocketNotifications.show(applicationContext,n,reminder=true)
        Pocket.prefs.edit().putLong("lastReminderId",id).putInt("lastReminderStage",stage).putLong("lastReminderAt",System.currentTimeMillis()).apply()
        if(stage<2)PocketAttention.schedule(id,stage+1,if(stage==0)15 else 30,append=true,local=local,environment=environment)
        return Result.success()
    }
}
