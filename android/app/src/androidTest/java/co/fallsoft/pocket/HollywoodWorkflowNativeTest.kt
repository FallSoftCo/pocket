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

/** Real accepted enterprise history through the authenticated gateway; no task submissions. */
@RunWith(AndroidJUnit4::class)
class HollywoodWorkflowNativeTest {
 @Test fun acceptedGoalAssignedWorkAndNativePlanAreVisible()=runBlocking{
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="harness_native_room")
  val cfg=JSONObject(c.filesDir.resolve("hollywood-workflow-settings.json").readText())
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val before=prefs.all.filterKeys{it=="server"||it=="token"||it=="deviceId"||it.startsWith("local:")||it.startsWith("draft:")||it.startsWith("pending:")||it.startsWith("attention:")}
  val selected=prefs.getString("activeEnvironment","workstation")!!
  val profile=EnvironmentIdentity("workflow_readback","Enterprise workflow verification")
  val profiles=EnvironmentProfiles(prefs).all().filter{it.id!=profile.id}+profile
  check(prefs.edit().putString("environments:v1",JSONArray(profiles.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
    .putString(profile.key("server"),cfg.s("base")).putString(profile.key("token"),cfg.s("token")).putString(profile.key("deviceId"),cfg.s("deviceId")).commit())
  check(prefs.edit().putString("hollywood-room:"+backendOwner(cfg.s("base"),cfg.s("token"))+":"+cfg.getJSONObject("identity").s("bindingId"),cfg.s("room")).commit())
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  fun await(timeout:Long=45000,test:()->Boolean){val end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(150)};assertTrue("Read-only workflow UI did not settle",test())}
  fun click(selector:BySelector){await{d.findObject(selector)?.isEnabled==true};d.findObject(selector).click()}
  try{
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(profile.id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await{Pocket.environmentId==profile.id&&BackendNavigation.nativeSelected()&&d.hasObject(By.text("Goals & work"))}
   val origin=Pocket.captureEnvironment(profile.id)!!
   val binding=Pocket.apiEnvironment(origin,"/api/hollywood/v3/identity")
   val client=HollywoodClient(backendOwner(origin.endpoint,origin.token),binding,origin.endpoint,origin.token)
   val history=client.call("rooms/"+cfg.s("room")+"/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   val rows=roomTranscriptRows(history,"actual-workflow")
   val plan=rows.single{it.s("type")=="planUpdate"}
   assertEquals("activity",plan.s("kind"));assertFalse(isCommandRun(plan))
   cfg.getJSONArray("plan").objects().forEach{assertTrue(plan.s("text").contains(it.s("step")))}
   assertTrue(rows.any{it.s("type")=="goalState"&&it.s("title")=="Native goal · complete"})
   click(By.text("Goals & work"))
   click(By.textContains(cfg.s("goalTitle")+" · accepted"))
   await{d.hasObject(By.text("Goal & plan"))&&d.hasObject(By.textContains("Outcome · accepted"))}
   repeat(3){if(!d.hasObject(By.text("Acceptance criteria"))){d.swipe(d.displayWidth/2,d.displayHeight*3/4,d.displayWidth/2,d.displayHeight/3,20);Thread.sleep(300)}}
   await{d.hasObject(By.text("Acceptance criteria"))}
   click(By.text("Back"))
   click(By.text("Close"))
   click(By.text("Room"));click(By.text("Rooms"))
   click(By.res("native-room:"+cfg.s("workRoom")))
   await{d.hasObject(By.text("Goals & work"))}
   click(By.text("Goals & work"))
   click(By.textContains(cfg.s("workTitle").take(120)+" · accepted"))
   await{d.hasObject(By.textContains("Acceptance · accepted"))&&d.hasObject(By.text("Execution · completed"))}
   repeat(6){if(!d.hasObject(By.textContains("SHA-256:"))){d.swipe(d.displayWidth/2,d.displayHeight*3/4,d.displayWidth/2,d.displayHeight/3,20);Thread.sleep(300)}}
   await{d.hasObject(By.textContains("bb8451a0299c5162d78447ac5812dff4bfe67a3dbafdadcbb40c7041f69dcd78"))}
   click(By.text("Close"))
   before.forEach{(key,value)->assertTrue("Preserved pairing/draft/receipt changed: "+key,value==prefs.all[key])}
   c.filesDir.resolve("hollywood-workflow-ui-result.json").writeText(JSONObject().put("state","passed-real-native-enterprise-room-views")
     .put("actualGatewayAndPostgres",true).put("actualNativePlan",true).put("planOutsideCommandRuns",true)
     .put("acceptedGoalVisible",true).put("crossRoomAssignedWorkVisible",true).put("evidenceVisible",true)
     .put("executionAndAcceptanceSeparate",true).put("priorPairingsDraftsReceiptsPreserved",true)
     .put("modelsStarted",0).put("physicalPhone",false).put("productionChanged",false).toString())
  }finally{
   Configurator.getInstance().waitForIdleTimeout=oldIdle
   i.runOnMainSync{if(Pocket.captureEnvironment(selected)!=null)Pocket.activateEnvironment(selected)}
  }
 }
}

