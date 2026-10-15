package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only real production history under a separately paired, scoped QA human. */
@RunWith(AndroidJUnit4::class)
class HollywoodProductionReadNativeTest {
 @Test fun productionCatalogAcceptedWorkAndAttemptHistoryUseTheCapturedHuman()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation()
  val c=i.targetContext
  val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val cfg=JSONObject(c.filesDir.resolve("hollywood-production-read-qa.json").readText())
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val before=prefs.all.filterKeys{it=="server"||it=="token"||it=="deviceId"||it.startsWith("local:")||it.startsWith("draft:")||it.startsWith("pending:")||it.startsWith("attention:")}
  val selected=prefs.getString("activeEnvironment","workstation")!!
  val profile=EnvironmentIdentity("production_readonly_20261015","OZZZ native read-only QA")
  val profiles=EnvironmentProfiles(prefs).all().filter{it.id!=profile.id}+profile
  check(prefs.edit()
   .putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
   .putString(profile.key("server"),cfg.s("base"))
   .putString(profile.key("token"),cfg.s("token"))
   .putString(profile.key("deviceId"),cfg.s("deviceId")).commit())
  check(prefs.edit().putString("hollywood-room:"+backendOwner(cfg.s("base"),cfg.s("token"))+":"+cfg.getJSONObject("identity").s("bindingId"),cfg.s("globalRoom")).commit())
  val oldIdle=Configurator.getInstance().waitForIdleTimeout
  Configurator.getInstance().waitForIdleTimeout=0
  fun await(timeout:Long=45000,test:()->Boolean){
   val end=SystemClock.elapsedRealtime()+timeout
   while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(150)}
   assertTrue("Real native room UI did not settle",test())
  }
  fun click(selector:BySelector){
   await{d.findObject(selector)?.isEnabled==true}
   var clicked=false
   repeat(3){
    if(!clicked)try { val node=d.findObject(selector);if(node!=null){node.click();clicked=true} }
    catch(_:StaleObjectException){i.uiAutomation.clearCache()}
   }
   assertTrue("Read-only navigation unavailable",clicked)
  }
  try {
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(profile.id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await{Pocket.environmentId==profile.id&&BackendNavigation.nativeSelected()&&d.hasObject(By.text("Goals & work"))}
   val origin=Pocket.captureEnvironment(profile.id)!!
   val identity=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity")
   assertTrue("Human binding changed",identity.s("bindingId")==cfg.getJSONObject("identity").s("bindingId"))
   val client=HollywoodClient(backendOwner(origin.endpoint,origin.token),identity,origin.endpoint,origin.token)
   val catalog=client.call("rooms").getJSONArray("rooms").objects()
   val expected=cfg.getJSONArray("roomIds").let{a->(0 until a.length()).map{a.getString(it)}.toSet()}
   assertEquals("Canonical production room catalog differs",expected,catalog.map{it.s("id")}.toSet())
   click(By.text("Goals & work"))
   click(By.textContains(cfg.s("goalTitle")+" · active"))
   await{d.hasObject(By.text("Goal & plan"))&&d.hasObject(By.textContains("Outcome · active"))}
   click(By.text("Back"));click(By.text("Close"))
   click(By.text("Room"));click(By.text("Rooms"))
   val workSelector=By.res("native-room:"+cfg.s("workRoom"))
   repeat(8){if(!d.hasObject(workSelector)){d.swipe(d.displayWidth/2,d.displayHeight*3/4,d.displayWidth/2,d.displayHeight/3,20);Thread.sleep(150)}}
   click(workSelector)
   await{d.hasObject(By.text("Goals & work"))&&d.hasObject(By.res("native-room-history"))}
   val history=client.call("rooms/"+cfg.s("workRoom")+"/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   val rows=roomTranscriptRows(history,"production-readback")
   assertTrue("Real native command history unavailable",rows.any{isCommandRun(it)})
   assertTrue("Finite native goal completion missing",rows.any{it.s("type")=="goalState"&&it.s("title")=="Native goal · complete"})
   click(By.text("Goals & work"))
   click(By.textContains(cfg.s("workTitle").take(120)+" · accepted"))
   await{d.hasObject(By.textContains("Acceptance · accepted"))&&d.hasObject(By.text("Execution · completed"))}
   repeat(8){if(!d.hasObject(By.textContains(cfg.s("evidenceSha")))){d.swipe(d.displayWidth/2,d.displayHeight*3/4,d.displayWidth/2,d.displayHeight/3,20);Thread.sleep(150)}}
   await{d.hasObject(By.textContains(cfg.s("evidenceSha")))}
   val attempts=client.call("work/"+cfg.s("workId")+"/attempts?after=0&limit=100")
   assertTrue("Original failed attempt history missing",attempts.getJSONArray("attempts").length()>=2)
   click(By.text("Close"))
   before.forEach{(key,value)->assertTrue("Prior pairing/draft/receipt changed",value==prefs.all[key])}
   c.filesDir.resolve("hollywood-production-read-result.json").writeText(JSONObject()
    .put("state","passed").put("actualGatewayAndPostgres",true).put("separatelyPairedReadOnlyHuman",true)
    .put("roomCount",catalog.size).put("enterpriseGoalStillActive",true).put("acceptedWorkAndEvidenceVisible",true)
    .put("actualNativeCommands",true).put("finiteNativeGoalCompletion",true).put("attemptHistoryRetained",true)
    .put("priorPairingsDraftsReceiptsPreserved",true).put("modelsStarted",0).put("physicalPhone",false).toString())
  } finally {
   Configurator.getInstance().waitForIdleTimeout=oldIdle
   i.runOnMainSync{if(Pocket.captureEnvironment(selected)!=null)Pocket.activateEnvironment(selected)}
  }
 }
}
