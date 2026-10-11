package co.fallsoft.pocket

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A receipt records a view already presented, never a background fetch. */
internal data class PresentedReadReceipt(val environment:String,val owner:String,val thread:String,val kind:String,val value:String,val at:Long) {
    val id get()=backendOwner(owner,"$thread\u0000$kind\u0000$value")
    val lane get()=backendOwner(owner,"$thread\u0000$kind")
    fun valid(now:Long)=thread.isNotBlank()&&value.isNotBlank()&&kind in setOf("notifications","catchup")&&now>=at&&now-at<if(kind=="catchup")600_000L else 7*86400_000L
    fun matches(destination:EnvironmentRequest)=environment==destination.environment.id&&owner==backendOwner(environment+"\u0000"+destination.endpoint,destination.token)
    fun json()=JSONObject().put("environment",environment).put("owner",owner).put("thread",thread).put("kind",kind).put("value",value).put("at",at)
    companion object {fun parse(s:String)=JSONObject(s).let{PresentedReadReceipt(it.getString("environment"),it.getString("owner"),it.getString("thread"),it.getString("kind"),it.getString("value"),it.getLong("at"))}}
}
internal object PresentedReads {
    private const val prefix="presented-read:"
    @Synchronized fun record(thread:String,kind:String,value:String,environment:String){
        val destination=Pocket.captureEnvironment(environment)?:return
        val r=PresentedReadReceipt(environment,backendOwner(environment+"\u0000"+destination.endpoint,destination.token),thread,kind,value,System.currentTimeMillis())
        if(!r.valid(System.currentTimeMillis()))return
        // Persist before admission. Repeated viewport callbacks share one durable work ID.
        if(Pocket.prefs.contains(prefix+r.id))return
        if(!Pocket.prefs.edit().putString(prefix+r.id,r.json().toString()).commit())return
        enqueue(r.lane)
    }
    private fun pending()=Pocket.prefs.all.entries.filter{it.key.startsWith(prefix)}.mapNotNull{(key,value)->
        runCatching{PresentedReadReceipt.parse(value as String)}.getOrElse{remove(key.removePrefix(prefix));null}
    }
    fun restore(){pending().map{it.lane}.distinct().forEach{enqueue(it)}}
    private fun enqueue(lane:String){try{
        val request=OneTimeWorkRequestBuilder<PresentedReadWorker>().setInputData(workDataOf("lane" to lane))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        // Append closes the finishing-worker race. Extra wakeups find an empty lane.
        WorkManager.getInstance(Pocket.context).enqueueUniqueWork("presented-read-lane-$lane",ExistingWorkPolicy.APPEND_OR_REPLACE,request)
    }catch(_:Exception){/* Durable receipt remains; next foreground admission retries. */}}
    fun first(lane:String)=pending().filter{it.lane==lane}.minByOrNull{it.at}
    fun remove(id:String){Pocket.prefs.edit().remove(prefix+id).commit()}
}
class PresentedReadWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
    override suspend fun doWork():Result {
        val lane=inputData.getString("lane")?:return Result.success()
        repeat(32){
        val receipt=PresentedReads.first(lane)?:return Result.success()
        val id=receipt.id
        val destination=Pocket.captureEnvironment(receipt.environment)
        if(!receipt.valid(System.currentTimeMillis())||destination==null||!receipt.matches(destination)){PresentedReads.remove(id);return@repeat}
        try {
            val body=if(receipt.kind=="catchup")JSONObject().put("token",receipt.value)else JSONObject().put("throughId",receipt.value.toLong())
            val result=Pocket.apiEnvironment(destination,"/api/threads/${receipt.thread}/${receipt.kind}/read",body)
            val current=Pocket.captureEnvironment(receipt.environment)
            if(current==null||!receipt.matches(current)){PresentedReads.remove(id);return@repeat}
            if(receipt.kind=="notifications"){
                if(!result.optBoolean("ok"))return Result.retry()
                PocketNotificationReads.accept(receipt.thread,receipt.value.toLong(),result,destination)
            }else if(result.s("threadId")!=receipt.thread)return Result.retry()
            PresentedReads.remove(id)
        }catch(e:CancellationException){throw e}catch(e:Exception){
            if(e is java.io.IOException||e is PocketApiException&&(e.status in listOf(408,429)||e.status>=500))return Result.retry()
            // Expired/superseded captures and revoked credentials remain unseen locally.
            PresentedReads.remove(id)
        }
        }
        return Result.retry() // Bound each run; unfinished receipts retain their order.
    }
}
