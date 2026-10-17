package co.fallsoft.pocket

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Source-bound verified audio assets. No microphone, WebView, task submission or API-key fallback. */
internal class NativeSpeechRenderer(private val context:Context,private val endpoint:String,private val credential:String){
    private suspend fun read(path:String,body:JSONObject?=null,audio:Boolean=false):ByteArray=suspendCancellableCoroutine{continuation->
        val request=Request.Builder().url(endpoint.trimEnd('/')+path).header("Authorization","Bearer $credential")
        if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
        val call=Pocket.http.newCall(request.build());continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){response.use{
                try{
                    val limit=if(audio&&it.isSuccessful)16*1024*1024 else 65536
                    val bytes=ByteArrayOutputStream()
                    requireNotNull(it.body).byteStream().use{input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=limit){"Speech response exceeded its bounded size"};bytes.write(buffer,0,n)}}
                    val value=bytes.toByteArray()
                    if(!it.isSuccessful){val message=runCatching{JSONObject(value.toString(Charsets.UTF_8)).s("error")}.getOrNull();throw PocketApiException(it.code,(message?:"Speech server returned ${it.code}").replace(credential,"[credential]").take(300))}
                    if(continuation.isActive)continuation.resume(value)
                }catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }}
        })
    }
    private suspend fun api(path:String,body:JSONObject?=null)=JSONObject(read(path,body).toString(Charsets.UTF_8))
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    suspend fun render(text:String):ByteArray=withContext(Dispatchers.IO){
        require(text.isNotBlank()&&text.length<=2000){"Speech passage must be split into bounded chunks"}
        val began=android.os.SystemClock.elapsedRealtime();SpeechUsage.add("attempts")
        var id:String?=null;var attempt:String?=null;var completed=false
        try{
            val capability=api("/api/voice/native/capabilities")
            check(capability.optInt("renderedSpeechVersion")>=1){"This environment needs its native audio asset update. Your text is kept."}
            val key=digest((capability.getString("speechPolicy")+":"+text).toByteArray(Charsets.UTF_8));id=key
            attempt=UUID.randomUUID().toString()
            var receipt=api("/api/voice/renderings/$key",JSONObject().put("text",text).put("attemptId",attempt))
            attempt=receipt.getString("attemptId");val cached=receipt.optBoolean("cacheHit")
            val deadline=android.os.SystemClock.elapsedRealtime()+180000
            while(receipt.s("phase")!="completed"){
                check(receipt.s("id")==key){"Speech receipt belongs to another passage"}
                check(receipt.s("phase") in setOf("queued","rendering")){receipt.s("error","Speech generation stopped; your text is kept")}
                check(android.os.SystemClock.elapsedRealtime()<deadline){"Speech generation exceeded its deadline; your text is kept"}
                delay(1000);receipt=api("/api/voice/renderings/$key")
            }
            check(receipt.s("id")==key){"Speech receipt belongs to another passage"}
            val bytes=read("/api/voice/renderings/$key/audio",audio=true)
            check(bytes.size.toLong()==receipt.getLong("bytes")&&digest(bytes)==receipt.getString("audioSha256")&&validNativeSpeechAudio(bytes)){"Saved speech failed integrity verification; your text is kept"}
            completed=true
            if(cached)SpeechUsage.add("cacheHits")else{
                SpeechUsage.add("generated");val duration=receipt.optLong("durationMs")
                if(duration>0)SpeechUsage.add("generatedMs",duration)else SpeechUsage.add("unknownDuration")
            }
            SpeechUsage.add("bytes",bytes.size.toLong());bytes
        }catch(e:CancellationException){SpeechUsage.add("cancelled");throw e}
        catch(e:Exception){SpeechUsage.add("failed");throw e}
        finally{
            SpeechUsage.add("renderMs",android.os.SystemClock.elapsedRealtime()-began)
            if(!completed&&id!=null&&attempt!=null)withContext(NonCancellable){withTimeoutOrNull(5000){runCatching{api("/api/voice/renderings/$id/cancel",JSONObject().put("attemptId",attempt))}}}
        }
    }
}
