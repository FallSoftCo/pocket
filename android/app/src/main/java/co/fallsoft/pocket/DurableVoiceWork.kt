package co.fallsoft.pocket

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** This journal contains only v1 immutable recording admissions. Legacy unknown sends never enter it. */
internal object DurableVoiceWork {
    const val JOURNAL="voice-recordings-v1"
    private val initialization=Mutex()
    private var initialized=false
    suspend fun initialize(context:Context)=withContext(Dispatchers.IO) {
        initialization.withLock {
            if(!initialized){VoiceTurnStore(context,JOURNAL).use{it.recoverInterruptedOwner()};initialized=true}
        }
    }
    fun enqueue(context:Context,key:String) {
        val request=OneTimeWorkRequestBuilder<DurableVoiceWorker>()
            .setInputData(workDataOf("turnKey" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("durable-recording-"+key,ExistingWorkPolicy.KEEP,request)
    }
    suspend fun restore(context:Context)=withContext(Dispatchers.IO) {
        initialize(context)
        VoiceTurnStore(context,JOURNAL).use { store ->
            store.list().filter { it.capture==VoiceTurnStore.Capture.SEALED && it.delivery !in terminal }.forEach { enqueue(context,it.key) }
        }
    }
    val terminal=setOf(VoiceTurnStore.Delivery.COMPLETED,VoiceTurnStore.Delivery.FAILED,VoiceTurnStore.Delivery.CANCELLED)
}

/** Uploads immutable audio, never task commands. Server receipts alone advance the delivery lifecycle. */
class DurableVoiceWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val key=inputData.getString("turnKey")?:return@withContext Result.success()
        DurableVoiceWork.initialize(applicationContext)
        VoiceTurnStore(applicationContext,DurableVoiceWork.JOURNAL).use { store ->
            var turn=store.get(key)?:return@use Result.success()
            if(turn.capture!=VoiceTurnStore.Capture.SEALED || turn.delivery in DurableVoiceWork.terminal)return@use Result.success()
            val requests=VoiceTurnRequests(applicationContext)
            try {
                val capability=requests.call(turn,"/api/voice/native/capabilities")
                check(capability.optBoolean("durableAudioAdmission")) { "This environment needs its durable audio adapter. Recording remains saved." }
                if(turn.delivery==VoiceTurnStore.Delivery.UNSUBMITTED) {
                    store.claim(key);turn=requireNotNull(store.get(key))
                }
                val path="/api/voice/recordings/"+turn.id
                var receipt=try { requests.call(turn,path) }
                    catch(e:PocketApiException) {
                        if(e.status!=404)throw e
                        // Retrying this admission ID cannot submit a task; the server rejects changed audio/destination.
                        requests.call(turn,"/api/voice/recordings",JSONObject().put("id",turn.id)
                            .put("destination",JSONObject(turn.origin.destination)).put("audioSha256",turn.sha256).put("bytes",turn.bytes))
                    }
                if(receipt.optString("phase") in setOf("receiving","sealing"))
                    receipt=requests.call(turn,path+"/audio",audio=store.audio(turn))
                // Keep the interactive path responsive; longer outages use WorkManager's durable bounded backoff.
                repeat(40) {
                    check(receipt.optString("id")==turn.id && receipt.optString("audioSha256")==turn.sha256) {
                        "Audio receipt does not belong to the original recording"
                    }
                    withContext(Dispatchers.Main){PocketVoice.recordingProgress(turn,receipt)}
                    when(receipt.optString("state")) {
                        "completed"->{store.completed(key,receipt);store.playback(key,VoiceTurnStore.Playback.READY);withContext(Dispatchers.Main){PocketVoice.deliveredRecording(turn,receipt)};return@use Result.success()}
                        "failed","blocked"->{store.failed(key,receipt);return@use Result.success()}
                        "cancelled"->{store.failed(key,receipt.put("error","Recording cancelled"));return@use Result.success()}
                        "unknown"->store.uncertain(key,receipt.optString("error","Original task delivery is unconfirmed"))
                        else->if(receipt.optString("phase") in setOf("ready","transcribing","transcribed")) {
                            if(store.get(key)?.delivery in setOf(VoiceTurnStore.Delivery.SUBMITTING,VoiceTurnStore.Delivery.STORED))store.stored(key,receipt)
                        } else if(receipt.optString("phase")=="accepted")store.accepted(key,receipt)
                    }
                    delay(2000)
                    receipt=requests.call(turn,path)
                }
                Result.retry()
            }catch(e:CancellationException){throw e}
            catch(e:Exception) {
                store.problem(key,e.message?:"Recording retained; transport unavailable")
                withContext(Dispatchers.Main){PocketVoice.recordingProgress(turn,JSONObject().put("state","blocked").put("error",e.message?:"Recording saved; transport unavailable"))}
                // No re-creation as task input and no destination changes, including unknown acknowledgements.
                if(e is java.io.IOException || e is PocketApiException && (e.status==404||e.status==408||e.status==429||e.status>=500))Result.retry()
                else Result.failure()
            }
        }
    }
}
