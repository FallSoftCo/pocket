package co.fallsoft.pocket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Captured pairing origin: an in-flight command never follows a profile switch. */
class LosangelexClient(val owner:String,val base:String=Pocket.base.trimEnd('/'),private val token:String=Pocket.token) {
    private val http=Pocket.http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    suspend fun call(path:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
        val request=Request.Builder().url("$base/api/losangelex/$path").header("Authorization","Bearer $token")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        http.newCall(request.build()).execute().use{response->
            val value=runCatching{JSONObject(response.body?.string()?:"{}").also{if(it.length()==0&&!response.isSuccessful)throw Exception()}}.getOrElse{throw PocketApiException(response.code,"Losangelex returned an invalid response")}
            if(!response.isSuccessful)throw PocketApiException(response.code,value.s("error","Losangelex request failed (${response.code})"))
            value
        }
    }
    fun draft(scope:TeamScope)=Pocket.prefs.getString(scope.draftKey(owner),"")?:""
    fun saveDraft(scope:TeamScope,text:String){Pocket.prefs.edit().putString(scope.draftKey(owner),text).apply()}
    fun pending(scope:TeamScope)=Pocket.prefs.getString("pending:${scope.draftKey(owner)}",null)?.let{runCatching{JSONObject(it)}.getOrNull()}
    fun savePending(scope:TeamScope,command:JSONObject?){Pocket.prefs.edit().apply{if(command==null)remove("pending:${scope.draftKey(owner)}")else putString("pending:${scope.draftKey(owner)}",command.toString())}.apply()}
}
