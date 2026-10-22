package co.fallsoft.pocket

import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual account WebRTC, isolated retained authority, no task admission or model work. */
@RunWith(AndroidJUnit4::class)
class CoordinatorNativeAudioLiveTest {
 @Test fun liveInputFinalizesAndOutputPlaysBeforeCompleteBuffer() {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  i.runOnMainSync{Pocket.init(c)}
  check(Pocket.base=="http://127.0.0.1:18889"){"Only the retained isolated QA authority is allowed"}
  val http=OkHttpClient.Builder().callTimeout(40,TimeUnit.SECONDS).build()
  val executor=Executors.newSingleThreadExecutor()
  fun api(path:String,body:JSONObject):JSONObject {
   val request=Request.Builder().url(Pocket.base+path).header("Authorization","Bearer "+Pocket.token)
    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
   return http.newCall(request).execute().use{r->val j=JSONObject(r.body!!.string());check(r.isSuccessful){j.optString("error")};j}
  }
  val events=mutableListOf<JSONObject>();val began=SystemClock.elapsedRealtime()
  var audio:NativeVoiceAudio?=null;var connection:String?=null
  val volume=c.getSystemService(AudioManager::class.java);val original=volume.getStreamVolume(AudioManager.STREAM_MUSIC)
  fun connect(purpose:String,onEvent:(String,String)->Unit):NativeVoiceAudio {
   val ready=CompletableFuture<Unit>();var created:NativeVoiceAudio?=null
   i.runOnMainSync{created=NativeVoiceAudio(c,explicitCompletion=true,livePlayback=true){kind,data->
    synchronized(events){events.add(JSONObject().put("kind",kind).put("at",SystemClock.elapsedRealtime()-began))}
    when(kind){
     "offer"->executor.submit{try{
      val result=api("/api/voice/native/start",JSONObject().put("sdp",data).put("purpose",purpose));connection=result.getString("id")
      i.runOnMainSync{created!!.answer(result.getString("sdp"))}
     }catch(e:Exception){ready.completeExceptionally(e)}}
     "connected"->ready.complete(Unit)
     "error"->ready.completeExceptionally(IllegalStateException(data))
    }
    onEvent(kind,data)
   }}
   ready.get(40,TimeUnit.SECONDS);return requireNotNull(created)
  }
  try{
   volume.setStreamVolume(AudioManager.STREAM_MUSIC,(volume.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2).coerceAtLeast(1),0)
   val id=UUID.randomUUID().toString();val wav=i.context.assets.open("voice-durable-input.wav").use{it.readBytes()}
   check(String(wav.copyOfRange(0,4))=="RIFF");val pcm=wav.copyOfRange(44,wav.size)
   val hash=MessageDigest.getInstance("SHA-256").digest(wav).joinToString(""){"%02x".format(it)}
   audio=connect("transcription"){_,_->}
   api("/api/voice/native/input/begin",JSONObject().put("id",id).put("connectionId",connection))
   i.runOnMainSync{audio!!.beginInput(1)}
   val feedAt=SystemClock.elapsedRealtime()
   for(offset in pcm.indices step 4000){
    val part=pcm.copyOfRange(offset,(offset+4000).coerceAtMost(pcm.size));i.runOnMainSync{audio!!.appendInput(1,part)}
    Thread.sleep(part.size.toLong()*1000/32000)
   }
   val stopped=SystemClock.elapsedRealtime()
   val proof=runBlocking(kotlinx.coroutines.Dispatchers.Main){audio!!.finalizeInput(1)}
   assertTrue(proof.getBoolean("confirmed"))
   api("/api/voice/native/input/finalize",JSONObject().put("id",id).put("connectionId",connection)
    .put("audioSha256",hash).put("bytes",wav.size).put("finalization",proof))
   val finalized=SystemClock.elapsedRealtime()
   i.runOnMainSync{audio!!.close()};api("/api/voice/native/stop",JSONObject().put("connectionId",connection));connection=null
   val complete=CompletableFuture<JSONObject>();var firstAudio:Long?=null
   audio=connect("voice"){kind,data->
    if(kind=="speaking"&&firstAudio==null)firstAudio=SystemClock.elapsedRealtime()
    if(kind=="audio")complete.complete(JSONObject(data))
    if(kind=="error")complete.completeExceptionally(IllegalStateException(data))
   }
   val passage="The coordinator received your request. Your other work has not been interrupted."
   val speechAt=SystemClock.elapsedRealtime()
   i.runOnMainSync{audio!!.speak(2,passage)}
   api("/api/voice/native/speak",JSONObject().put("connectionId",connection).put("text",passage))
   val result=complete.get(70,TimeUnit.SECONDS);val speechDone=SystemClock.elapsedRealtime()
   assertTrue("Live audio was not used",result.optBoolean("live"));assertNotNull(firstAudio)
   assertTrue("First audio waited for the full cache",firstAudio!!<speechDone-250)
   File(c.filesDir,"coordinator-native-audio-live.json").writeText(JSONObject().put("inputId",id)
    .put("audioSha256",hash).put("inputDurationMs",pcm.size*1000/32000).put("captureFeedMs",stopped-feedAt)
    .put("stopToFinalTranscriptMs",finalized-stopped).put("speechFirstAudioMs",firstAudio!!-speechAt)
    .put("speechCompletedMs",speechDone-speechAt).put("livePlayback",true).put("taskAdmissions",0)
    .put("events",org.json.JSONArray(synchronized(events){events.toList()})).toString())
  }finally{
   i.runOnMainSync{audio?.close()}
   connection?.let{runCatching{api("/api/voice/native/stop",JSONObject().put("connectionId",it))}}
   volume.setStreamVolume(AudioManager.STREAM_MUSIC,original,0);executor.shutdownNow()
  }
 }
}

