package co.fallsoft.pocket

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Credentials and audience are captured at discovery; neither follows selection. GET only. */
internal object WorkCatalogTransport {
    private val http by lazy{CodexReadTransport.catalogClient.newBuilder()
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(8,TimeUnit.SECONDS).build()}
    suspend fun get(destination:EnvironmentRequest,path:String,binding:String?=null):JSONObject {
        val resource=path.substringBefore('?')
        require(resource in setOf("/api/backends","/api/threads","/api/hollywood/v3/rooms"))
        require(if(resource=="/api/hollywood/v3/rooms")binding?.matches(Regex("[0-9a-f]{64}"))==true else binding==null)
        val request=Request.Builder().url(destination.endpoint.trimEnd('/')+path)
            .header("Authorization","Bearer "+destination.token)
        binding?.let{request.header("X-Hollywood-Binding",it)}
        return suspendCancellableCoroutine{continuation->
            val call=http.newCall(request.build());continuation.invokeOnCancellation{call.cancel()}
            call.enqueue(object:Callback{
                override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                override fun onResponse(call:Call,response:Response){
                    try{
                        val value=response.use{
                            val json=try{JSONObject(it.body?.string().orEmpty())}catch(e:Exception){throw IOException("Catalog response unavailable")}
                            if(!it.isSuccessful)throw PocketApiException(it.code,ConnectionMessages.server(json.s("error","Catalog unavailable")))
                            json
                        }
                        if(continuation.isActive)continuation.resume(value)
                    }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
                }
            })
        }
    }
}

internal data class WorkCatalogPayload(val destination:EnvironmentRequest,val body:String,val binding:String?=null) {
    override fun toString()="WorkCatalogPayload(${destination.environment.id}, private catalog data)"
}

/** Adapter for independently readable catalog lanes, not a conversation or command transport. */
internal class WorkCatalogReader(
    val destination:EnvironmentRequest,val backend:String,val identity:JSONObject?=null,
    private val read:suspend (EnvironmentRequest,String,String?)->JSONObject=WorkCatalogTransport::get
) {
    val key=WorkSourceKey(destination.environment.id,backendOwner(destination.endpoint,destination.token)+
        (if(backend=="lax")":"+identity?.s("bindingId").orEmpty() else ""),backend)
    init {
        require(backend in setOf("codex","lax"))
        if(backend=="lax"){
            require(identity!=null&&identity.s("bindingId").matches(Regex("[0-9a-f]{64}")))
            require(listOf("authorityId","principalId","defaultRoomId").all{hollywoodUuid(identity!!.s(it))})
        }
    }
    suspend fun head():List<WorkEntry<WorkCatalogPayload>> = when(backend){
        "codex"->{
            val result=read(destination,"/api/threads",null)
            val rows=result.getJSONArray("threads").objects()
            require(rows.all{it.s("id").isNotBlank()}&&rows.map{it.s("id")}.distinct().size==rows.size){"Session identities unavailable"}
            rows.map{row->
                val task=catalogTask(row)
                WorkEntry(WorkItemKey(key,WorkKind.SESSION,"",task.id),workTimeMillis(taskWorkTime(task)),
                    WorkCatalogPayload(destination,row.toString()))
            }
        }
        else->{
            val binding=identity!!
            val result=read(destination,"/api/hollywood/v3/rooms",binding.s("bindingId"))
            require(result.optJSONObject("binding")?.let{hollywoodBindingMatches(binding,it)}==true){"Room audience is unconfirmed"}
            val rows=result.getJSONArray("rooms").objects()
            require(rows.all{hollywoodUuid(it.s("id"))&&it.s("name").isNotBlank()&&it.s("authority")==binding.s("authorityId")}&&rows.map{it.s("id")}.distinct().size==rows.size){"Room identities unavailable"}
            rows.map{row->
                // Current room catalog has no work-recency contract. Unknown is
                // zero, never room edits or the polling timestamp in activity.
                val at=0L
                WorkEntry(WorkItemKey(key,WorkKind.ROOM,binding.s("authorityId"),row.s("id")),at.coerceAtLeast(0),
                    WorkCatalogPayload(destination,row.toString(),binding.toString()))
            }
        }
    }
}


// Existing stock catalogs can carry epoch seconds; compare one time unit across hosts.
internal fun workTimeMillis(value:Long):Long=when {
    value<=0->0
    value<100_000_000_000L->value*1000
    else->value
}
