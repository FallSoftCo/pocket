package co.fallsoft.pocket
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Read/heard receipts only. A retry can never submit or resume task work. */
internal object CoordinatorReceiptWork {
    fun response(context:Context,key:String){enqueue(context,"response:"+key,workDataOf("recordingKey" to key))}
    fun update(context:Context,home:String,owner:String,source:String,seq:Long){enqueue(context,"update:$owner:$source:$seq",workDataOf("home" to home,"owner" to owner,"source" to source,"seq" to seq))}
    private fun enqueue(context:Context,id:String,data:Data){
        val work=OneTimeWorkRequestBuilder<CoordinatorReceiptWorker>().setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("coordinator-heard-"+id,ExistingWorkPolicy.KEEP,work)
    }
}
class CoordinatorReceiptWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
    override suspend fun doWork():Result=withContext(Dispatchers.IO){
        try{
            val key=inputData.getString("recordingKey")
            if(key!=null){
                VoiceTurnStore(applicationContext,DurableVoiceWork.JOURNAL).use{store->
                    val turn=store.get(key)?:return@withContext Result.success()
                    val request=VoiceTurnRequests(applicationContext)
                    request.call(turn,"/api/voice/turns/${turn.id}/timing",JSONObject().put("stage","handset_response_heard"))
                    request.call(turn,"/api/voice/turns/${turn.id}/presented",JSONObject())
                    store.playback(key,VoiceTurnStore.Playback.COMPLETED)
                }
            }else{
                val profiles=EnvironmentProfiles(applicationContext.getSharedPreferences("pocket",Context.MODE_PRIVATE))
                val home=profiles.capture(inputData.getString("home")?:return@withContext Result.failure())?:return@withContext Result.failure()
                if(backendOwner(home.endpoint,home.token)!=inputData.getString("owner"))return@withContext Result.failure()
                CoordinatorVoiceLink.api(home.endpoint,home.token,"/api/coordinator/voice-updates/presented",JSONObject().put("environmentId",inputData.getString("source")).put("seq",inputData.getLong("seq",0)))
            }
            Result.success()
        }catch(e:CancellationException){throw e}catch(e:Exception){
            if(e is PocketApiException&&e.status in setOf(400,401,403,404))Result.failure() else Result.retry()
        }
    }
}
