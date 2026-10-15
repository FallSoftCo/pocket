package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One finite journey through the real separate Hollywood authority, not production rooms.
 * Saved journal/receipts fence reruns after any real POST; inspect them first.
 */
@RunWith(AndroidJUnit4::class)
class HollywoodLiveRoomJourneyTest {
 @Test fun newestOlderReconnectAudienceAndOriginalReceiptRemainCoherent()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val config=JSONObject(c.filesDir.resolve("live-room-qa-config.json").readText())
  val journal=c.filesDir.resolve("live-room-journey.json")
  val continuing=journal.exists()
  if(continuing)check(JSONObject(journal.readText()).s("stage")=="accepted-head-visible"){"Only the original accepted operation may continue."}
  fun stage(name:String,extra:JSONObject=JSONObject()){
   journal.writeText(extra.put("stage",name).put("at",System.currentTimeMillis()).toString())
  }
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  // Synthetic existing-profile canaries, never a copied handset credential.
  check(prefs.edit().putString("server","http://127.0.0.1:9").putString("token","synthetic-never-a-real-credential")
   .putString("deviceId","00000000-0000-4000-8000-000000000099").putString("draft:prior-session","Keep this unrelated draft").commit())
  val before=prefs.all.filterKeys{it=="server"||it=="token"||it=="deviceId"||it.startsWith("local:")||it.startsWith("draft:")||it.startsWith("pending:")||it.startsWith("attention:")}
  val oldEnvironment=prefs.getString("activeEnvironment","workstation")!!
  val profile=EnvironmentIdentity("live_room_qa_20261015","Real room QA")
  val profiles=EnvironmentProfiles(prefs).all().filter{it.id!=profile.id}+profile
  val identity=config.getJSONObject("identity");val room=config.s("roomId");val base=config.s("base")
  val owner=backendOwner(base,config.s("token"));val recipient=config.s("recipientId")
  val client=HollywoodClient(owner,identity,base,config.s("token"))
  val scope=HollywoodScope(room,recipient,"room",null,"request")
  fun await(label:String,timeout:Long=30000,test:()->Boolean){
   val end=SystemClock.elapsedRealtime()+timeout
   while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(120)}
   assertTrue(label,test())
  }
  fun click(selector:BySelector){await("Control unavailable: $selector"){d.findObject(selector)?.isEnabled==true};d.findObject(selector).click()}
  fun field()=d.findObject(By.clazz("android.widget.EditText"))
  fun type(text:String){await("Room input unavailable"){field()!=null};field().click();field().text=text}
  fun control(body:JSONObject){
   val request=Request.Builder().url(base+"/__qa/control").header("Authorization","Bearer "+config.s("controlToken"))
    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
   Pocket.http.newCall(request).execute().use{assertEquals(200,it.code)}
  }
  fun receipt(text:String):JSONObject?=prefs.all.entries.asSequence().filter{it.key.startsWith("receipt:hollywood:$owner:")}
   .mapNotNull{runCatching{JSONObject(it.value as String)}.getOrNull()}
   .firstOrNull{it.optJSONObject("receipt")?.optJSONObject("event")?.s("body")==text}
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  if(!continuing)stage("prepared")
  try {
   check(prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
    .putString(profile.key("server"),base).putString(profile.key("token"),config.s("token")).putString(profile.key("deviceId"),config.s("deviceId"))
    .putString("hollywood-room:$owner:"+identity.s("bindingId"),room).commit())
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(profile.id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Newest canonical message was not the initial visible page"){d.hasObject(By.textContains(config.s("latestMarker")))}
   assertTrue("Native paging footer missing",d.hasObject(By.res("reader-page-up")))
   if(!continuing)stage("newest-visible")
   val text="QA core accepted head refresh r03"
   if(!continuing){
   control(JSONObject().put("aux",true))
   type(text)
   stage("core-ui-send-prepared")
   click(By.desc("Send message"))
   await("A real accepted receipt was not retained"){receipt(text)!=null}
   await("Accepted send relabeled by a later auxiliary failure"){d.hasObject(By.text("Accepted by Hollywood"))}
   assertFalse("Accepted delivery was called unconfirmed",d.hasObject(By.textContains("Delivery remains saved")))
   assertNull("Confirmed operation still blocks the outbox",client.pending(scope))
   await("History was gated on failed metadata/attention"){d.hasObject(By.textContains(text))}
   val accepted=receipt(text)!!;val operation=accepted.s("operationId")
   stage("accepted-head-visible",accepted)
   }
   val accepted=receipt(text)?:error("Original accepted receipt missing")
   val operation=accepted.s("operationId")
   await("Initial native socket unavailable"){PocketLive.connectionRevision>0&&Pocket.connected}
   val revision=PocketLive.connectionRevision
   control(JSONObject().put("aux",false).put("disconnect",true))
   await("Authenticated native socket did not reconnect",20000){PocketLive.connectionRevision>revision&&Pocket.connected}
   // Reopen through actual keyboard-aware Back and room card, not a new send.
   field()?.click();d.pressBack()
   await("Back must open room catalog"){d.hasObject(By.res("native-room:$room"))}
   click(By.res("native-room:$room"))
   await("Original accepted message missing after disconnect/reopen"){d.hasObject(By.textContains(text))}
   val history=client.call("rooms/$room/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   assertEquals("Original message duplicated",1,history.count{it.s("body")==text})
   assertEquals(operation,client.messageReceipt(scope,operation)!!.s("operationId"))
   assertNull(client.pending(scope))
   before.forEach{(key,value)->assertEquals("Existing pairing/draft/receipt changed: $key",value,prefs.all[key])}
   stage("passed",JSONObject().put("actualGatewayAndPostgres",true).put("newestFirst",true)
    .put("acceptedSurvivesAuxiliaryFailure",true).put("headRefreshVisible",true).put("disconnectReopenSameOriginal",true)
    .put("operationId",operation).put("receipt",accepted).put("matchingCanonicalMessages",1)
    .put("priorPairingDraftReceiptsPreserved",true).put("modelsStarted",0).put("physicalPhone",false))
  } catch(error:Throwable){
   d.takeScreenshot(c.filesDir.resolve("live-room-failure.png"))
   d.dumpWindowHierarchy(c.filesDir.resolve("live-room-failure.xml"))
   throw error
  } finally {
   Configurator.getInstance().waitForIdleTimeout=oldIdle
   i.runOnMainSync{if(Pocket.captureEnvironment(oldEnvironment)!=null)Pocket.activateEnvironment(oldEnvironment)}
  }
 }
}
