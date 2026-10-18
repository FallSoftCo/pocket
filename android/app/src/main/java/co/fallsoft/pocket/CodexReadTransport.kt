package co.fallsoft.pocket

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Read-only history adapter. Retiring its owning session cancels the actual HTTP call,
 * rather than merely ignoring a response after a blocking execute() completes.
 * Position POST is a canonical history query, never a command admission.
 */
internal object CodexReadTransport {
    suspend fun read(destination:EnvironmentRequest,path:String,body:JSONObject?=null,client:OkHttpClient=Pocket.http):JSONObject {
        val resource=path.substringBefore('?')
        require(
            body==null&&(resource.matches(Regex("/api/threads/[^/]+"))||resource in setOf("/api/threads","/api/status","/api/activity","/api/notifications","/api/attention"))||
            body!=null&&resource.matches(Regex("/api/threads/[^/]+/timeline/position"))
        ){"Only canonical history reads are supported"}

        val builder=Request.Builder().url(destination.endpoint.trimEnd('/')+path)
            .header("Authorization","Bearer "+destination.token)
        if(body!=null)builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine{continuation->
            val call=client.newCall(builder.build())
            continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback {
                override fun onFailure(call:Call,e:IOException){
                    if(continuation.isActive)continuation.resumeWithException(e)
                }
                override fun onResponse(call:Call,response:Response){
                    try {
                        val value=response.use{
                            val raw=it.body?.string().orEmpty()
                            val json=try{JSONObject(raw)}catch(_:Exception){throw PocketApiException(it.code,"Unexpected history response")}
                            if(!it.isSuccessful)throw PocketApiException(it.code,ConnectionMessages.server(json.s("error","History unavailable")))
                            json
                        }
                        if(continuation.isActive)continuation.resume(value)
                    }catch(error:Exception){if(continuation.isActive)continuation.resumeWithException(error)}
                }
            })
        }
    }
}
