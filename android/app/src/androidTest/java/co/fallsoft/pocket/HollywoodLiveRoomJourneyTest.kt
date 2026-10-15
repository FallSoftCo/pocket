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
  check(!journal.exists()){"Original real-room journey exists. Reconcile its effects before any new journey."}
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
  stage("prepared")
  try {
   check(prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
    .putString(profile.key("server"),base).putString(profile.key("token"),config.s("token")).putString(profile.key("deviceId"),config.s("deviceId"))
    .putString("hollywood-room:$owner:"+identity.s("bindingId"),room).commit())
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(profile.id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Newest canonical message was not the initial visible page"){d.hasObject(By.textContains(config.s("latestMarker")))}
   assertTrue("Native paging footer missing",d.hasObject(By.res("reader-page-up")))
   stage("newest-visible")
   type("Room draft retained")
   d.pressBack()
   await("Back with IME must open room catalog"){d.hasObject(By.res("native-room:$room"))}
   click(By.res("native-room:$room"))
   await("Room draft lost across catalog navigation"){field()?.text=="Room draft retained"}
   click(By.res("native-room-address"));click(By.res("native-room-private"));d.pressBack()
   await("Private scope did not retain a distinct draft"){field()?.text.orEmpty().isEmpty()}
   type("Private draft retained")
   click(By.res("native-room-address"));click(By.res("native-room-private"));d.pressBack()
   await("Room audience draft was overwritten"){field()?.text=="Room draft retained"}
   stage("back-and-audience-drafts")
   // A genuine live publication while an older page is being read must not
   // force the reader to the bottom; Latest explicitly follows it afterwards.
   click(By.res("reader-page-up"))
   await("Manual reading did not expose Latest"){d.hasObject(By.res("native-room-latest"))}
   val anchor=d.findObjects(By.textStartsWith("Earlier canonical QA")).firstOrNull()?.text
   val live="QA live update while reading older"
   stage("live-publication-prepared",JSONObject().put("operationId","live-room-qa-r01:while-older"))
   client.call("rooms/$room/messages",HollywoodScope(room,recipient,"room",null,"message").message("live-room-qa-r01:while-older",live))
   delay(4000)
   if(anchor!=null)assertTrue("New output moved the older reading position",d.hasObject(By.text(anchor)))
   click(By.res("native-room-latest"));await("Latest canonical publication did not become visible"){d.hasObject(By.textContains(live))}
   stage("older-live-verified")
   control(JSONObject().put("aux",true))
   type("QA accepted while auxiliary reads fail")
   stage("ui-send-prepared")
   click(By.desc("Send message"))
   await("A real accepted receipt was not retained"){receipt("QA accepted while auxiliary reads fail")!=null}
   await("Accepted send relabeled by a later auxiliary failure"){d.hasObject(By.text("Accepted by Hollywood"))}
   assertFalse("Accepted delivery was called unconfirmed",d.hasObject(By.textContains("Delivery remains saved")))
   assertNull("Confirmed operation still blocks the outbox",client.pending(scope))
   await("History was gated on failed metadata/attention"){d.hasObject(By.textContains("QA accepted while auxiliary reads fail"))}
   stage("accepted-during-auxiliary-failure",receipt("QA accepted while auxiliary reads fail")!!)
   control(JSONObject().put("aux",false).put("disconnect",true))
   await("Authenticated native socket did not reconnect",20000){PocketLive.connectionRevision>=2&&Pocket.connected}
   stage("reconnected")
   type("QA lost acknowledgement original operation")
   control(JSONObject().put("dropMessageAck",true))
   stage("lost-ack-send-prepared")
   click(By.desc("Send message"))
   await("Unconfirmed operation lost its original saved identity"){client.pending(scope)!=null&&d.hasObject(By.textContains("Delivery remains saved"))}
   val original=client.pending(scope)!!;val operation=original.getJSONObject("body").s("operationId")
   stage("lost-ack-original-saved",JSONObject().put("operationId",operation))
   click(By.text("Check original delivery"))
   await("Original receipt did not reconcile without a new send"){client.messageReceipt(scope,operation)!=null&&client.pending(scope)==null}
   assertEquals(operation,client.messageReceipt(scope,operation)!!.s("operationId"))
   before.forEach{(key,value)->assertEquals("Existing pairing/draft/receipt changed: $key",value,prefs.all[key])}
   control(JSONObject().put("aux",false))
   stage("passed",JSONObject().put("actualGatewayAndPostgres",true).put("newestFirst",true).put("olderAndLive",true)
    .put("imeBackToCatalog",true).put("audienceDraftIsolation",true).put("acceptedSurvivesAuxiliaryFailure",true)
    .put("originalUnknownReconciled",operation).put("priorPairingDraftReceiptsPreserved",true).put("modelsStarted",0).put("physicalPhone",false))
  } finally {
   Configurator.getInstance().waitForIdleTimeout=oldIdle
   i.runOnMainSync{if(Pocket.captureEnvironment(oldEnvironment)!=null)Pocket.activateEnvironment(oldEnvironment)}
  }
 }
}
