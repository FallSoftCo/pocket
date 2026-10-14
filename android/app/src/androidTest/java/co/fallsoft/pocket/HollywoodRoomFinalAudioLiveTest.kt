package co.fallsoft.pocket

import android.content.Intent
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Private runtime qualification: real account WebRTC, generated test speech, no physical microphone. */
@RunWith(AndroidJUnit4::class)
class HollywoodRoomFinalAudioLiveTest {
 @Test fun finalTranscriptKeepsCapturedHollywoodRoomAndReadsReply()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
  val settings=JSONObject(File(c.filesDir,"hollywood-final-audio-settings.json").readText())
  val base="http://127.0.0.1:18886";val token=settings.getString("token")
  val http=OkHttpClient.Builder().readTimeout(40,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build()
  suspend fun api(path:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
   val b=Request.Builder().url(base+path).header("Authorization","Bearer $token")
   if(body!=null)b.post(body.toString().toRequestBody("application/json".toMediaType()))
   http.newCall(b.build()).execute().use{r->val v=JSONObject(requireNotNull(r.body).string());check(r.isSuccessful){"Native endpoint failed (${r.code}): ${v.optString("error")}"};v}
  }
  val binding=api("/api/hollywood/v3/identity")
  val destination=JSONObject().put("backend","lax").put("bindingId",binding.s("bindingId")).put("authorityId",binding.s("authorityId"))
   .put("roomId",settings.s("roomA")).put("recipientId",settings.s("agentId")).put("audience","room")
  val id=settings.getString("recordingId")
  check(!File(c.filesDir,"hollywood-final-audio-intent.json").exists()){ "Observe the original audio request; do not replay." }
  File(c.filesDir,"hollywood-final-audio-intent.json").writeText(JSONObject().put("id",id).put("destination",destination).toString())
  api("/api/voice/start",JSONObject().put("room",destination))
  val offer=CompletableDeferred<String>();val connected=CompletableDeferred<Unit>();val audio=CompletableDeferred<ByteArray>()
  var transport:NativeVoiceAudio?=null;var connection:String?=null
  i.runOnMainSync{
   c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   transport=NativeVoiceAudio(c){kind,data->when(kind){
    "offer"->offer.complete(data)
    "connected"->connected.complete(Unit)
    "audio"->audio.complete(Base64.decode(JSONObject(data).getString("data"),Base64.DEFAULT))
    "error"->{val e=IllegalStateException(data);if(!connected.isCompleted)connected.completeExceptionally(e);if(!audio.isCompleted)audio.completeExceptionally(e)}
   }}
  }
  try{
   val start=api("/api/voice/native/start",JSONObject().put("sdp",withTimeout(30000){offer.await()}))
   connection=start.getString("id");withContext(Dispatchers.Main){transport!!.answer(start.getString("sdp"))}
   withTimeout(30000){connected.await()}
   api("/api/voice/native/input",JSONObject().put("connectionId",connection).put("turnId",id))
   api("/api/voice/start",JSONObject().put("room",JSONObject(destination.toString()).put("roomId",settings.s("roomB"))))
   api("/api/voice/native/input",JSONObject().put("connectionId",connection).put("turnId",id))
   withContext(Dispatchers.Main){transport!!.send(File(c.filesDir,"hollywood-final-audio-input.wav").readBytes())}
   api("/api/voice/native/commit",JSONObject().put("connectionId",connection).put("turnId",id))
   var result=api("/api/voice/turns/$id")
   withTimeout(120000){while(result.s("state") !in setOf("completed","failed","unknown")){delay(1000);result=api("/api/voice/turns/$id")}}
   assertEquals("completed",result.s("state"));assertTrue("Final transcript: "+result.s("transcript"),result.s("transcript").lowercase().contains("hollywood voice is ready"));assertTrue("Native reply: "+result.s("response"),result.s("response").lowercase().replace('_',' ').contains("hollywood voice is ready"))
   assertEquals(settings.s("roomA"),result.getJSONObject("destination").s("roomId"))
   withContext(Dispatchers.Main){transport!!.speak(1,result.s("response"))}
   api("/api/voice/native/speak",JSONObject().put("connectionId",connection).put("text",result.s("response")).put("voiceTurnId",id))
   val spoken=withTimeout(45000){audio.await()};assertTrue(spoken.size>1000)
   File(c.filesDir,"hollywood-final-audio-output.webm").writeBytes(spoken)
   File(c.filesDir,"hollywood-final-audio-result.json").writeText(JSONObject().put("state","qualified-canonical-Hollywood-WebRTC-ASR-LAX-TTS").put("result",result).put("outputBytes",spoken.size).put("physicalMicrophone",false).put("physicalPhone",false).toString())
  }finally{
   connection?.let{runCatching{api("/api/voice/native/stop",JSONObject().put("connectionId",it))}}
   withContext(Dispatchers.Main){transport?.close()}
  }
 }
}
