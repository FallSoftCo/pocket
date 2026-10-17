package co.fallsoft.pocket

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Source-bound IO; deliberately independent of selected environment and Compose. */
internal class VoiceTurnRequests(private val context: Context) {
    companion object {
        private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
            .readTimeout(45,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()
    }
    suspend fun call(turn: VoiceTurnStore.Turn,path:String,body:JSONObject?=null,audio:java.io.File?=null):JSONObject = withContext(Dispatchers.IO) {
        val profiles=EnvironmentProfiles(context.getSharedPreferences("pocket",Context.MODE_PRIVATE))
        val destination=profiles.capture(turn.origin.environment)
            ?:throw IllegalStateException("The recording's original environment is no longer paired.")
        check(destination.endpoint.trimEnd('/')==turn.origin.endpoint.trimEnd('/') &&
            backendOwner(destination.endpoint,destination.token)==turn.origin.pairing) {
            "The recording's original pairing changed. It has not been redirected."
        }
        val request=Request.Builder().url(turn.origin.endpoint.trimEnd('/')+path)
            .header("Authorization","Bearer "+destination.token)
        if(audio!=null)request.put(audio.asRequestBody("audio/wav".toMediaType()))
        else if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use { response ->
            val result=runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrElse { JSONObject() }
            if(!response.isSuccessful)throw PocketApiException(response.code,
                result.optString("error","Server returned "+response.code).replace(destination.token,"[credential]").take(300))
            result
        }
    }
}

