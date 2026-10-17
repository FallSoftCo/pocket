package co.fallsoft.pocket

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Account-authenticated rendering only: no microphone permission, coordinator or paid API. */
internal class NativeSpeechRenderer(private val context:Context,private val endpoint:String,private val credential:String){
    private suspend fun api(path:String,body:JSONObject):JSONObject=suspendCancellableCoroutine{continuation->
        val request=Request.Builder().url(endpoint.trimEnd('/')+path).header("Authorization","Bearer $credential")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call=Pocket.http.newCall(request)
        continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){response.use{
                try{val json=JSONObject(it.body?.string()?:"{}");if(!it.isSuccessful)throw IllegalStateException(json.s("error","Native speech unavailable"));if(continuation.isActive)continuation.resume(json)}
                catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }}
        })
    }
    suspend fun render(text:String):ByteArray=withContext(Dispatchers.Main.immediate){
        val connected=CompletableDeferred<Unit>();val audio=CompletableDeferred<ByteArray>()
        var connection:String?=null;var transport:NativeVoiceAudio?=null
        val began=android.os.SystemClock.elapsedRealtime();SpeechUsage.add("attempts")
        val jobs=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try{
            transport=NativeVoiceAudio(context,explicitCompletion=true){kind,data->
                when(kind){
                    "offer"->jobs.launch{try{val answer=api("/api/voice/native/start",JSONObject().put("sdp",data).put("purpose","speech"));connection=answer.s("id");transport?.answer(answer.s("sdp"))}catch(e:Exception){connected.completeExceptionally(e)}}
                    "connected"->connected.complete(Unit)
                    "error"->{val error=IllegalStateException(data);connected.completeExceptionally(error);audio.completeExceptionally(error)}
                    "audio"->try{val payload=JSONObject(data);if(payload.optLong("token")==1L){val encoded=payload.s("data");require(encoded.length<=24*1024*1024){"Native speech exceeded its size limit"};val bytes=Base64.decode(encoded,Base64.DEFAULT);require(validNativeSpeechAudio(bytes)){"Native speech returned invalid audio"};audio.complete(bytes)}}catch(e:Exception){audio.completeExceptionally(e)}
                }
            }
            withTimeout(35000){connected.await()}
            requireNotNull(transport).speak(1L,text)
            api("/api/voice/native/speak",JSONObject().put("connectionId",connection).put("text",text))
            val bytes=withTimeout(90000){audio.await()}
            SpeechUsage.add("generated");SpeechUsage.add("bytes",bytes.size.toLong())
            withContext(Dispatchers.IO){
                val file=java.io.File.createTempFile("speech-duration-",".webm",context.cacheDir)
                val metadata=android.media.MediaMetadataRetriever()
                try{file.writeBytes(bytes);metadata.setDataSource(file.path);val duration=metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull();if(duration!=null&&duration>0)SpeechUsage.add("generatedMs",duration) else SpeechUsage.add("unknownDuration")}
                catch(_:Exception){SpeechUsage.add("unknownDuration")}
                finally{metadata.release();file.delete()}
            }
            bytes
        }catch(e:CancellationException){SpeechUsage.add("cancelled");throw e}
        catch(e:Exception){SpeechUsage.add("failed");throw e}
        finally{
            SpeechUsage.add("renderMs",android.os.SystemClock.elapsedRealtime()-began)
            transport?.close();jobs.cancel()
            withContext(NonCancellable){connection?.let{id->withTimeoutOrNull(5000){try{api("/api/voice/native/stop",JSONObject().put("connectionId",id))}catch(_:Exception){}}}}
        }
    }
}
