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

/** Fresh owned AVD, actual paired native gateway/Hollywood/LAX. No synthetic model output. */
@RunWith(AndroidJUnit4::class)
class HollywoodContextReviewNativeTest {
 @Test fun reviewedWindowOriginalReceiptAndEnvironmentIsolation(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_context_review_r340")
  val cfg=JSONObject(c.filesDir.resolve("context-review-qa.json").readText())
  require(cfg.s("runId")=="r340-original-r01"&&cfg.s("base")=="http://127.0.0.1:18880")
  val other=cfg.getJSONObject("foreign")
  require(other.s("base")=="http://127.0.0.1:18881"&&other.getJSONObject("identity").s("authorityId")!=cfg.getJSONObject("identity").s("authorityId"))
  val journal=c.filesDir.resolve("context-review-native-journal.json")
  val state=if(journal.exists())JSONObject(journal.readText())else JSONObject()
  fun save(key:String,value:Any=true){state.put(key,value);journal.writeText(state.toString())}
  fun await(label:String,timeout:Long=30000,check:()->Boolean){
   val end=SystemClock.elapsedRealtime()+timeout
   while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(check())return;Thread.sleep(200)}
   d.dumpWindowHierarchy(c.filesDir.resolve("context-review-failure.xml"))
   throw AssertionError(label)
  }
  fun click(selector:BySelector){await("Missing enabled $selector"){d.findObject(selector)?.isEnabled==true};d.findObject(selector).click()}
  fun tag(s:String)=By.res(s)
  fun scrollClick(selector:BySelector){
   repeat(10){i.uiAutomation.clearCache();val node=d.findObject(selector);if(node!=null&&node.isEnabled){node.click();return}
    d.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN,.65f);Thread.sleep(200)}
   click(selector)
  }
  fun menu(){click(tag("native-room-controls"))}
  fun openReview(){menu();scrollClick(By.text("Context windows"));await("Context review not opened"){d.hasObject(tag("native-context-review"))}}
  fun screenshot(name:String){d.takeScreenshot(c.filesDir.resolve("context-review-"+name+".png"))}
  val idle=Configurator.getInstance().waitForIdleTimeout
  try{
   Configurator.getInstance().waitForIdleTimeout=0
   d.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
   if(!state.optBoolean("initialized")){
    val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
    check(prefs.all.isEmpty()||prefs.getString("activeEnvironment",null)==null){"Refuse to overwrite an existing app profile"}
    val profiles=JSONArray()
    val edit=prefs.edit()
    for((id,name,source) in listOf(Triple("context_primary_r340","Context QA",cfg),Triple("context_foreign_r340","Foreign QA",other))){
     profiles.put(JSONObject().put("id",id).put("name",name).put("local",false))
     edit.putString("environment:$id:server",source.s("base")).putString("environment:$id:token",source.s("token"))
     val owner=backendOwner(source.s("base"),source.s("token"))
     edit.putString("backend:$owner","lax").putString("hollywood-room:$owner:"+source.getJSONObject("identity").s("bindingId"),source.s("roomId"))
    }
    check(edit.putString("environments:v1",profiles.toString()).putString("activeEnvironment","context_primary_r340").commit())
    save("initialized")
   }
   i.runOnMainSync{Pocket.init(c)}
   i.runOnMainSync{contextLaunch(c)}
   await("Actual room did not load"){d.hasObject(By.clazz("android.widget.EditText"))}
   val client=HollywoodClient(backendOwner(cfg.s("base"),cfg.s("token")),cfg.getJSONObject("identity"),cfg.s("base"),cfg.s("token"))
   fun read(path:String)=runBlocking{client.call(path)}
   val input=By.clazz("android.widget.EditText")
   if(!state.optBoolean("taskClickIntent")){
    await("Room composer not enabled"){d.findObject(input)?.isEnabled==true}
    d.findObject(input).text=cfg.s("request")
    await("Typed task not retained"){d.findObject(input)?.text==cfg.s("request")}
    Thread.sleep(500)
    assertEquals(cfg.s("request"),d.findObject(input)?.text)
    save("taskClickIntent")
    click(By.desc("Send message"))
   }
   var initial:JSONObject?=null
   await("Original human request not confirmed; never resubmit",45000){
    initial=read("rooms/"+cfg.s("roomId")+"/history?limit=100").getJSONArray("events").objects()
      .singleOrNull{it.s("author")==client.principalId&&it.s("body")==cfg.s("request")}
    initial!=null
   }
   save("originalEvent",initial!!.s("id"))
   var source:JSONObject?=null
   await("Actual native work did not complete",240000){
    source=read("agents/"+cfg.s("agentId")+"/contexts/writer/seed-sources?view=history")
      .getJSONArray("sources").objects().singleOrNull{it.s("kind")=="work"&&it.s("source")==initial!!.s("id")&&it.s("state")=="complete"}
    source!=null
   }
   save("source",source!!.s("reference"))
   await("Composer not restored"){d.hasObject(input)}
   d.findObject(input).text="Primary draft canary r340"
   openReview()
   val session=HollywoodContextReviewSession(client,cfg.s("agentId"))
   if(session.draftId==null)scrollClick(tag("native-context-create"))
   await("Original draft not durable"){session.draftId!=null}
   save("draftId",session.draftId!!)
   val recovered=read(session.base+"/seed-drafts/"+session.draftId+"/control").optJSONObject("control")
   if(recovered==null){
    scrollClick(tag("native-context-sources"))
    scrollClick(tag("native-context-source:"+source!!.s("reference")))
    await("Selected work not in reviewed canonical data"){d.hasObject(tag("native-context-kept:"+source!!.s("reference")))}
    screenshot("selected")
    click(tag("native-context-prepare"))
   }
   if(!state.optBoolean("approvalClickIntent")){
    await("Real native packet not prepared",120000){d.findObject(tag("native-context-state"))?.text=="Ready for your review"}
    screenshot("prepared")
    save("approvalClickIntent")
    click(tag("native-context-approve"))
   }
   if(!state.optBoolean("originalRecovered")){
    await("Accepted response loss did not retain original action",30000){d.hasObject(tag("native-context-check-original"))}
    assertNotNull(session.pending)
    save("approvalOperation",session.pending!!.s("operationId"))
    screenshot("unknown")
    click(tag("native-context-close"))
    await("Primary draft lost closing review"){d.findObject(input)?.text=="Primary draft canary r340"}
    menu();scrollClick(tag("environment-picker-toggle"));scrollClick(tag("environment-choice:context_foreign_r340"))
    await("Foreign environment not selected"){Pocket.environmentId=="context_foreign_r340"&&d.hasObject(By.clazz("android.widget.EditText"))}
    d.findObject(input).text="Foreign draft canary r340"
    openReview()
    await("Foreign profile displayed original pending control"){d.hasObject(tag("native-context-state"))}
    assertFalse(d.hasObject(tag("native-context-check-original")))
    val foreignClient=HollywoodClient(backendOwner(other.s("base"),other.s("token")),other.getJSONObject("identity"),other.s("base"),other.s("token"))
    assertNull(HollywoodContextReviewSession(foreignClient,other.s("agentId")).draftId)
    assertNotNull(session.pending)
    screenshot("foreign-isolation")
    click(tag("native-context-close"))
    menu();scrollClick(tag("environment-picker-toggle"));scrollClick(tag("environment-choice:context_primary_r340"))
    await("Primary draft lost after environment switch"){Pocket.environmentId=="context_primary_r340"&&d.findObject(input)?.text=="Primary draft canary r340"}
    openReview()
    await("Original action lost after reopening"){d.hasObject(tag("native-context-check-original"))}
    assertEquals(state.s("approvalOperation"),session.pending!!.s("operationId"))
    click(tag("native-context-check-original"))
    await("Original operation did not settle"){session.pending==null}
    save("originalRecovered")
   }
   await("Native fresh Writer window not installed",90000){d.findObject(tag("native-context-state"))?.text=="New context installed"}
   val installed=read(session.base+"/seed-drafts/"+session.draftId+"/control")
   assertEquals("installed",installed.getJSONObject("control").s("state"))
   assertEquals(1,installed.getJSONObject("control").getInt("generation"))
   screenshot("installed")
   click(tag("native-context-close"))
   await("Room draft lost after successful install"){d.findObject(input)?.text=="Primary draft canary r340"}
   // Read-only final scope journey: an actual denied metadata read must not be relabelled disabled/idle.
   menu();scrollClick(tag("environment-picker-toggle"));scrollClick(tag("environment-choice:context_foreign_r340"))
   await("Foreign composer not ready"){Pocket.environmentId=="context_foreign_r340"&&d.findObject(input)?.isEnabled==true}
   openReview()
   await("Failed metadata mislabelled as disabled"){d.findObject(tag("native-context-state"))?.text=="Context status unavailable"}
   assertFalse(d.hasObject(By.text("Reviewed native windows are not enabled for this agent.")))
   assertFalse(d.hasObject(tag("native-context-check-original")))
   screenshot("foreign-isolation")
   click(tag("native-context-close"))
   menu();scrollClick(tag("environment-picker-toggle"));scrollClick(tag("environment-choice:context_primary_r340"))
   await("Primary draft lost after read-only final scope check"){Pocket.environmentId=="context_primary_r340"&&d.findObject(input)?.text=="Primary draft canary r340"}
   openReview();await("Original installed review not recovered"){d.findObject(tag("native-context-state"))?.text=="New context installed"}
   screenshot("installed");click(tag("native-context-close"))
   val result=JSONObject().put("state","passed").put("originalEvent",state.s("originalEvent")).put("source",state.s("source"))
    .put("draftId",state.s("draftId")).put("approvalOperation",state.s("approvalOperation"))
    .put("actualGateway",true).put("actualNativePacket",true).put("environmentIsolation",true).put("roomDraftPreserved",true)
    .put("originalReceiptRecoveredByGet",true).put("physicalPhone",false).put("modelOutputFixture",false).put("failedMetadataExplicitlyUnavailable",true)
   c.filesDir.resolve("context-review-native-result.json").writeText(result.toString(2))
  }catch(e:Throwable){screenshot("failure");d.dumpWindowHierarchy(c.filesDir.resolve("context-review-failure.xml"));throw e}
  finally{Configurator.getInstance().waitForIdleTimeout=idle}
 }
 private fun contextLaunch(c:Context){c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
}

