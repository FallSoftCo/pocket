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
        require(resource in setOf("/api/backends","/api/status","/api/threads","/api/hollywood/v3/rooms"))
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
                            parseWorkCatalogResponse(it.code,it.body?.string().orEmpty())
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
    val destination:EnvironmentRequest,val backend:String,val identity:JSONObject?=null,val archived:Boolean=false,
    private val read:suspend (EnvironmentRequest,String,String?)->JSONObject=WorkCatalogTransport::get
) {
    val key=WorkSourceKey(destination.environment.id,backendOwner(destination.endpoint,destination.token)+
        (if(backend=="lax")":"+identity?.s("bindingId").orEmpty() else ""),backend,if(archived)"archived"else"recent")
    init {
        require(backend in setOf("codex","lax"))
        if(backend=="lax"){
            require(identity!=null&&identity.s("bindingId").matches(Regex("[0-9a-f]{64}")))
            require(listOf("authorityId","principalId","defaultRoomId").all{hollywoodUuid(identity!!.s(it))})
        }
    }
    suspend fun head():List<WorkEntry<WorkCatalogPayload>> = when(backend){
        "codex"->{
            val result=read(destination,if(archived)"/api/threads?archived=true"else"/api/threads",null)
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
                // Event epoch-ms is already canonical; never scale it like legacy sessions.
                val at=HollywoodRoomFeedSummary.read(row)?.workAt?:0L
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


// Preserve HTTP status even when a legacy server returns an HTML error page.
// Malformed successful responses remain failures, never an empty catalog.
internal fun parseWorkCatalogResponse(status:Int,body:String):JSONObject {
    val json=try{JSONObject(body)}catch(_:Exception){null}
    if(status !in 200..299)throw PocketApiException(status,
        ConnectionMessages.server(json?.s("error","Catalog unavailable")?:"Catalog unavailable"))
    return json?:throw IOException("Catalog response unavailable")
}

/** Older stock gateways predate backend discovery. Confirm their authenticated
 * canonical session catalog before advertising stock capability; authentication,
 * network and malformed-response failures never downgrade to this compatibility path.
 */
internal suspend fun discoverWorkBackends(
    read:suspend ()->JSONObject,
    confirmLegacyCodex:suspend ()->Unit
):JSONObject = try{read()}catch(error:PocketApiException){
    if(error.status!=404)throw error
    confirmLegacyCodex()
    JSONObject().put("backends",org.json.JSONArray().put(JSONObject()
        .put("id","codex").put("configured",true).put("available",true)))
}

/** Viewer-authorized feedSummary v1 only; never room metadata or observation time.
 * Recency must name the highest-sequence projected event, not the largest clock.
 * Malformed/legacy summaries leave the room navigable with unknown work time.
 */
internal data class HollywoodRoomFeedSummary(val workAt:Long,val preview:String?) {
    companion object {
        private const val MAX_SAFE=9007199254740991L
        private data class Event(val id:String,val seq:Long,val at:Long,val text:String?)
        private fun integer(raw:JSONObject,key:String):Long? {
            val value=raw.opt(key)
            if(value !is Int&&value !is Long)return null
            return (value as Number).toLong().takeIf{it in 0..MAX_SAFE}
        }
        private fun event(raw:JSONObject,textKey:String):Event? {
            val id=raw.opt("eventId") as? String?:return null
            if(!hollywoodUuid(id))return null
            val seq=integer(raw,"seq")?.takeIf{it>0}?:return null
            val at=integer(raw,"occurredAt")?:return null
            // Do not stringify arbitrary objects or render unbounded legacy text.
            val text=(raw.opt(textKey) as? String)?.takeIf{
                raw.opt("truncated") is Boolean&&it.codePointCount(0,it.length)<=320&&it.isNotBlank()
            }
            return Event(id,seq,at,text)
        }
        fun read(room:JSONObject):HollywoodRoomFeedSummary? {
            val raw=room.optJSONObject("feedSummary")?:return null
            if(integer(raw,"version")!=1L)return null
            val events=mutableListOf<Event>()
            for((key,textKey) in listOf("latestMessage" to "body","latestActivity" to "title")){
                if(raw.isNull(key))continue
                events+=event(raw.optJSONObject(key)?:return null,textKey)?:return null
            }
            val latest=events.maxByOrNull{it.seq}
            if(latest==null){
                if(!raw.isNull("recency"))return null
                return HollywoodRoomFeedSummary(0,null)
            }
            val recency=event(raw.optJSONObject("recency")?:return null,"")?:return null
            if(recency.id!=latest.id||recency.seq!=latest.seq||recency.at!=latest.at)return null
            return HollywoodRoomFeedSummary(recency.at,events.sortedByDescending{it.seq}.firstNotNullOfOrNull{it.text})
        }
    }
}

internal fun hollywoodRoomPreview(room:JSONObject,available:Boolean):String =
    (if(available)HollywoodRoomFeedSummary.read(room)?.preview else null)?:room.s("purpose")
