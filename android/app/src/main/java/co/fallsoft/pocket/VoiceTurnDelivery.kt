package co.fallsoft.pocket

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Source-bound IO; deliberately independent of selected environment and Compose. */
internal class VoiceTurnRequests(private val context: Context) {
    companion object {
        private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
            .readTimeout(45,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()
    }
    suspend fun call(turn: VoiceTurnStore.Turn,path:String,body:JSONObject?=null):JSONObject = withContext(Dispatchers.IO) {
        val profiles=EnvironmentProfiles(context.getSharedPreferences("pocket",Context.MODE_PRIVATE))
        val destination=profiles.capture(turn.origin.environment)
            ?:throw IllegalStateException("The recording's original environment is no longer paired.")
        check(destination.endpoint.trimEnd('/')==turn.origin.endpoint.trimEnd('/') &&
            backendOwner(destination.endpoint,destination.token)==turn.origin.pairing) {
            "The recording's original pairing changed. It has not been redirected."
        }
        val request=Request.Builder().url(turn.origin.endpoint.trimEnd('/')+path)
            .header("Authorization","Bearer "+destination.token)
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { response ->
            val result=runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if(!response.isSuccessful)throw PocketApiException(response.code,
                result.optString("error","Server returned "+response.code).replace(destination.token,"[credential]").take(300))
            result
        }
    }
}

/** One native input at a time; task execution and response playback never own this mutex. */
internal class VoiceTurnDelivery(private val context:Context) {
    companion object { private val input=Mutex() }
    private val requests=VoiceTurnRequests(context)
    private suspend fun <T> journal(block:(VoiceTurnStore)->T):T = withContext(Dispatchers.IO) {
        VoiceTurnStore(context).use(block)
    }
    suspend fun submit(key:String):VoiceTurnStore.Turn = input.withLock {
        var turn=journal { requireNotNull(it.get(key)) }
        if(!journal { it.claim(key) })return@withLock journal { requireNotNull(it.get(key)) }
        var commitAttempted=false
        var audio:NativeVoiceAudio?=null
        var connection:String?=null
        var heartbeat:Job?=null
        try {
            val capabilities=requests.call(turn,"/api/voice/native/capabilities")
            check(capabilities.optInt("turnJournalVersion")>=1 && capabilities.optBoolean("immutableDestination") &&
                capabilities.optBoolean("recordingDigest")) { "This environment needs the durable voice backend update. The recording remains queued." }
            val existing=try { requests.call(turn,"/api/voice/turns/"+turn.id) }
                catch(e:PocketApiException) { if(e.status!=404)throw e;null }
            if(existing!=null) {
                commitAttempted=true // A pre-existing receipt is not a fresh unsent recording.
                acceptVoiceReceipt(context,turn,existing)
                val found=journal { requireNotNull(it.get(key)) }
                if(found.delivery in setOf(VoiceTurnStore.Delivery.ACCEPTED,VoiceTurnStore.Delivery.UNKNOWN))VoiceReceiptWork.enqueue(context,key)
                return@withLock found
            }
            val offer=CompletableDeferred<String>();val ready=CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) {
                audio=NativeVoiceAudio(context) { kind,data ->
                    when(kind) {
                        "offer"->offer.complete(data)
                        "connected"->ready.complete(Unit)
                        "error"->{val error=IllegalStateException(data);offer.completeExceptionally(error);ready.completeExceptionally(error)}
                    }
                }
            }
            val negotiated=requests.call(turn,"/api/voice/native/start",
                JSONObject().put("sdp",withTimeout(10000){offer.await()}).put("purpose","voice"))
            connection=negotiated.getString("id")
            withContext(Dispatchers.Main) { requireNotNull(audio).answer(negotiated.getString("sdp")) }
            withTimeout(35000){ready.await()}
            coroutineScope {
                heartbeat=launch {
                    while(isActive) { delay(30000);requests.call(turn,"/api/voice/native/heartbeat",JSONObject().put("connectionId",connection)) }
                }
                requests.call(turn,"/api/voice/native/input",JSONObject().put("connectionId",connection)
                    .put("turnId",turn.id).put("destination",JSONObject(turn.origin.destination)))
                val wav=journal { it.audio(turn).readBytes() }
                withContext(Dispatchers.Main) { requireNotNull(audio).send(wav) }
                // Persisted SUBMITTING already precedes this uncertain task boundary.
                commitAttempted=true
                val receipt=requests.call(turn,"/api/voice/native/commit",JSONObject()
                    .put("connectionId",connection).put("turnId",turn.id).put("audioSha256",turn.sha256))
                acceptVoiceReceipt(context,turn,receipt)
                heartbeat?.cancel()
            }
        } catch(e:Exception) {
            withContext(NonCancellable) {
                val reason=if(e is CancellationException)"Voice transport interrupted; recording retained" else e.message?:"Voice transport unavailable"
                journal {
                    val current=requireNotNull(it.get(key))
                    if(current.delivery==VoiceTurnStore.Delivery.SUBMITTING) {
                        if(commitAttempted)it.uncertain(key,reason)else it.notAdmitted(key,reason)
                    }
                }
            }
            if(e is CancellationException)throw e
        } finally {
            heartbeat?.cancel()
            withContext(NonCancellable) {
                withContext(Dispatchers.Main){audio?.close()}
                connection?.let { id ->
                    withTimeoutOrNull(5000) { runCatching { requests.call(turn,"/api/voice/native/stop",JSONObject().put("connectionId",id)) } }
                }
            }
        }
        turn=journal { requireNotNull(it.get(key)) }
        if(turn.delivery in setOf(VoiceTurnStore.Delivery.ACCEPTED,VoiceTurnStore.Delivery.UNKNOWN))VoiceReceiptWork.enqueue(context,key)
        turn
    }
}

/** Validates the original request before committing any presentation or delivery projection. */
internal suspend fun acceptVoiceReceipt(context:Context,turn:VoiceTurnStore.Turn,receipt:JSONObject) = withContext(Dispatchers.IO) {
    check(receipt.optString("id")==turn.id && receipt.optString("audioSha256")==turn.sha256) {
        "Voice receipt did not confirm the original recording identity"
    }
    VoiceTurnStore(context).use { store ->
        if(store.get(turn.key)?.delivery==VoiceTurnStore.Delivery.COMPLETED)return@use
        when(receipt.optString("state")) {
            "completed"->{store.completed(turn.key,receipt);store.playback(turn.key,VoiceTurnStore.Playback.READY)}
            "failed"->store.failed(turn.key,receipt)
            "unknown"->store.uncertain(turn.key,receipt.optString("error","Original delivery is unconfirmed"))
            "transcribing","thinking","running","queued","submitting"->store.accepted(turn.key,receipt)
            else->error("Voice receipt returned an unrecognized delivery state")
        }
    }
}

internal object VoiceReceiptWork {
    fun enqueue(context:Context,key:String) {
        val work=OneTimeWorkRequestBuilder<VoiceReceiptWorker>().setInputData(workDataOf("turnKey" to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("voice-receipt-"+key,ExistingWorkPolicy.KEEP,work)
    }
}

/** Read-only recovery survives foreground service death. It never uploads or resends a task. */
class VoiceReceiptWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val key=inputData.getString("turnKey")?:return@withContext Result.success()
        val turn=VoiceTurnStore(applicationContext).use { it.get(key) }?:return@withContext Result.success()
        if(turn.delivery !in setOf(VoiceTurnStore.Delivery.SUBMITTING,VoiceTurnStore.Delivery.ACCEPTED,VoiceTurnStore.Delivery.UNKNOWN))
            return@withContext Result.success()
        try {
            val receipt=VoiceTurnRequests(applicationContext).call(turn,"/api/voice/turns/"+turn.id)
            acceptVoiceReceipt(applicationContext,turn,receipt)
            if(receipt.optString("state") in setOf("completed","failed"))Result.success()else Result.retry()
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) {
            VoiceTurnStore(applicationContext).use { it.problem(key,e.message?:"Receipt unavailable") }
            // Missing receipts are not permission to upload; retain the journal and inspect quietly.
            if(e is java.io.IOException || e is PocketApiException && (e.status==404 || e.status==408 || e.status==429 || e.status>=500))
                Result.retry() else Result.success()
        }
    }
}
