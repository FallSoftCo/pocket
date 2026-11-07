package co.fallsoft.pocket
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Explicit delegation from existing private pairings, never from an agent/admin token. */
internal object CoordinatorVoiceLink {
    private val registered=mutableMapOf<String,String>()
    private val registering=mutableMapOf<String,Deferred<Unit>>()
    suspend fun api(base:String,token:String,path:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
        val request=Request.Builder().url(base.trimEnd('/')+path).header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        Pocket.http.newCall(request.build()).execute().use{response->
            val result=JSONObject(response.body?.string()?:"{}")
            if(!response.isSuccessful)throw PocketApiException(response.code,ConnectionMessages.server(result.s("error","Coordinator unavailable")))
            result
        }
    }
    suspend fun register(base:String,token:String){
        val owner=backendOwner(base,token)
        val existing=registering[owner];if(existing!=null){existing.await();return}
        val source=JSONArray(Pocket.environmentProfiles.all().mapNotNull{profile->
            val paired=Pocket.captureEnvironment(profile.id)?:return@mapNotNull null
            if(profile.local||paired.endpoint.trimEnd('/')==base.trimEnd('/'))return@mapNotNull null
            JSONObject().put("environmentId",profile.id).put("name",profile.name).put("base",paired.endpoint.trimEnd('/')).put("token",paired.token)
        })
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(source.toString().toByteArray()).joinToString(""){"%02x".format(it)}
        if(registered[owner]==fingerprint)return
        val job=Pocket.scope.async(start=CoroutineStart.LAZY){api(base,token,"/api/coordinator/sources",JSONObject().put("sources",source));Unit}
        registering[owner]=job;job.start()
        try{job.await();registered[owner]=fingerprint;while(registered.size>16)registered.remove(registered.keys.first())}finally{if(registering[owner]===job)registering.remove(owner)}
    }
    fun prepare(){
        val source=Pocket.captureEnvironment(Pocket.environmentId)?:return
        Pocket.scope.launch{try{register(source.endpoint,source.token)}catch(e:CancellationException){throw e}catch(_:Exception){/* Voice input remains usable; sources are explicitly unavailable in retrieval. */}}
    }
}
