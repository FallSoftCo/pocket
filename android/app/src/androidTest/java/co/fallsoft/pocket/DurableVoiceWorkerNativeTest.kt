package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.work.WorkManager
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.File

/** Real retained QA pairing, WorkManager, upload, native ASR and canonical nonexecuting room request. */
@RunWith(AndroidJUnit4::class)
class DurableVoiceWorkerNativeTest {
 @Test fun originalRecordingReachesCanonicalQaRoomAndCancellationSettlesOriginalWorker():Unit=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=c.getSharedPreferences("pocket",0);val profiles=EnvironmentProfiles(prefs)
  val profile=profiles.all().mapNotNull{profiles.capture(it.id)}.first{it.endpoint.startsWith("http://127.0.0.1:18889")||it.endpoint.startsWith("http://127.0.0.1:18890")}
  fun call(path:String,body:JSONObject?=null,binding:String?=null):JSONObject {
   val request=Request.Builder().url(profile.endpoint.trimEnd('/')+path).header("Authorization","Bearer "+profile.token)
   if(binding!=null)request.header("X-Hollywood-Binding",binding)
   if(body!=null)request.post(body.toString().toRequestBody("application/json".toMediaType()))
   return Pocket.http.newCall(request.build()).execute().use{response->
    check(response.isSuccessful){"QA HTTP ${response.code}"};JSONObject(requireNotNull(response.body).string())
   }
  }
  val identity=call("/api/hollywood/v3/identity");val binding=identity.optJSONObject("binding")?:identity
  check(binding.getString("authorityId")=="6b8d1e3c-12c0-4c6d-8943-a7fdb32deece"){"Only isolated QA authority is permitted"}
  val destination=JSONObject().put("room",JSONObject().put("backend","lax").put("bindingId",binding.getString("bindingId"))
   .put("authorityId",binding.getString("authorityId")).put("roomId","6d966d5c-6d16-4fa9-91ee-5313e86312f7")
   .put("recipientId","19e396f9-0530-44bd-91dd-ef29bb1395e4").put("audience","direct"))
  val id="android-worker-room-qa-20261017-r01"
  DurableVoiceWork.initialize(c)
  val turn=VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use{store->
   val origin=VoiceTurnStore.Origin(profile.environment.id,backendOwner(profile.endpoint,profile.token),profile.endpoint,destination.toString())
   store.seal(store.begin(id,origin).key,i.context.assets.open("voice-durable-input.wav").use{it.readBytes()})
  }
  File(c.filesDir,"durable-worker-qa-intent.json").writeText(JSONObject().put("id",id).put("key",turn.key).put("environment",turn.origin.environment).toString())
  val workName="durable-recording-"+turn.key
  var delivery:String?=null
  var cancelAttempted=false
  try {
   DurableVoiceWork.enqueue(c,turn.key)
   val deadline=android.os.SystemClock.elapsedRealtime()+60000
   var accepted=false
   while(android.os.SystemClock.elapsedRealtime()<deadline){
    val current=VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use{it.get(turn.key)!!}
    if(current.delivery==VoiceTurnStore.Delivery.ACCEPTED){accepted=true;break}
    check(current.delivery !in setOf(VoiceTurnStore.Delivery.FAILED,VoiceTurnStore.Delivery.CANCELLED)){current.problem?:"QA recording failed"}
    Thread.sleep(200)
   }
   assertTrue("Real upload/transcript/task receipt did not arrive",accepted)
   val deviceId=prefs.getString(profile.environment.key("deviceId"),null)?:error("Retained QA device identity missing")
   val digest=MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(binding.getString("bindingId"),deviceId,id)).toString().toByteArray()).joinToString(""){"%02x".format(it)}
   val command=call("/api/hollywood/v3/commands/voice-hollywood:"+digest,binding=binding.getString("bindingId"))
   val deliveries=command.getJSONArray("deliveries");assertEquals(1,deliveries.length())
   val original=deliveries.getJSONObject(0);delivery=original.getString("id")
   assertEquals("19e396f9-0530-44bd-91dd-ef29bb1395e4",original.getString("agent"))
   assertTrue("QA accidentally acquired native execution",original.isNull("host"))
   if(original.getString("state") !in setOf("completed","failed","cancelled")){
    cancelAttempted=true
    call("/api/hollywood/v3/deliveries/"+delivery+"/cancel",JSONObject(),binding.getString("bindingId"))
   }
   val settled=android.os.SystemClock.elapsedRealtime()+15000
   while(android.os.SystemClock.elapsedRealtime()<settled){
    if(VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use{it.get(turn.key)!!.delivery}==VoiceTurnStore.Delivery.FAILED)break
    Thread.sleep(200)
   }
   val result=VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use{it.get(turn.key)!!}
   assertEquals("Cancellation was not reconciled through original receipt",VoiceTurnStore.Delivery.FAILED,result.delivery)
   assertEquals(turn.sha256,result.sha256);assertEquals(turn.origin,result.origin)
   File(c.filesDir,"durable-worker-qa-result.json").writeText(JSONObject().put("id",id).put("eventId",command.getJSONObject("event").getString("id")).put("deliveryId",delivery).put("state",result.delivery.name).put("canonicalDeliveries",1).toString())
  }finally{
   // No replay. Preserve original journal and canonical receipt even if this assertion run fails.
   if(!cancelAttempted)delivery?.let{runCatching{call("/api/hollywood/v3/deliveries/"+it+"/cancel",JSONObject(),binding.getString("bindingId"))}}
   WorkManager.getInstance(c).cancelUniqueWork(workName).result.get()
  }
 }
}
