package co.fallsoft.pocket
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class HollywoodCatalogLiveNativeTest {
 @Test fun actualCatalogCountsAndOriginalAcceptedMessageSurviveListAndRoomReopening()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val config=JSONObject(c.filesDir.resolve("live-room-qa-config.json").readText())
  val client=HollywoodClient(backendOwner(config.s("base"),config.s("token")),config.getJSONObject("identity"),config.s("base"),config.s("token"))
  val target=config.s("roomId");val canonical=client.call("rooms").getJSONArray("rooms").objects().single{it.s("id")==target}
  val activity=HollywoodRoomActivity.read(canonical,true)?:error("Actual canonical activity snapshot unavailable")
  val expected=if(activity.idle)"Idle; no visible unsettled work at observation" else activity.categories().joinToString("; "){(label,count)->"$count $label"}
  fun await(label:String,test:()->Boolean){val end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(120)};assertTrue(label,test())}
  fun tapStable(tag:String){
   var previous:android.graphics.Rect?=null;var stableAt=SystemClock.elapsedRealtime()
   await("Tap region did not settle: "+tag){
    val bounds=d.findObject(By.res(tag))?.visibleBounds
    if(bounds==null||bounds!=previous){previous=bounds?.let{android.graphics.Rect(it)};stableAt=SystemClock.elapsedRealtime();false}
    else SystemClock.elapsedRealtime()-stableAt>=350
   }
   d.findObject(By.res(tag)).click()
  }
  val latest=client.call("rooms/$target/history?before=9007199254740991&limit=100").getJSONArray("events").objects().last{it.s("kind") in listOf("message","result","request")&&it.s("body").isNotBlank()}.s("body").take(80)
  val original=Pocket.environmentId;val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  try {
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment("live_room_qa_20261015",force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Original accepted message missing"){d.hasObject(By.textContains(latest))}
   val controls=d.findObject(By.res("native-room-controls")).visibleBounds
   val history=d.findObject(By.res("native-room-history")).visibleBounds
   assertTrue("Room controls remained in a top header",controls.top>=history.bottom)
   d.findObject(By.res("native-room-controls")).click()
   await("Room address must be available at anchored footer menu"){d.hasObject(By.res("native-room-address"))}
   d.findObject(By.res("native-room-address")).click()
   await("Footer address lost private recipient controls"){d.hasObject(By.res("native-room-private"))}
   d.pressBack()
   await("Address section did not close in place"){!d.hasObject(By.res("native-room-private"))}
   d.pressBack()
   await("Footer menu did not close"){!d.hasObject(By.res("native-room-address"))}
   d.takeScreenshot(c.filesDir.resolve("room-footer-live.png"))

   d.pressBack()
   await("Actual room catalog missing"){d.hasObject(By.res("native-room:"+target))}
   await("Actual canonical counts not shown"){d.findObject(By.res("native-room-activity:"+target))?.contentDescription==expected}
   assertTrue("Shared catalog paging controls missing",d.hasObject(By.res("reader-page-up"))&&d.hasObject(By.res("reader-page-down")))
   assertTrue("Shared catalog volume control missing",d.hasObject(By.desc("Speech volume")))
   tapStable("native-catalog-find")
   await("Room search did not focus at the thumb edge"){d.hasObject(By.res("native-room-search"))}
   val search=d.findObject(By.res("native-room-search"))
   search.setText("no-such-room-owned-native-check")
   await("Room search did not filter locally"){d.hasObject(By.text("No matching rooms"))&&!d.hasObject(By.res("native-room:"+target))}
   // A stable pre-IME position is not the settled keyboard layout. Physical
   // input must target the visible control after the keyboard has moved it.
   await("Search keyboard has not settled above its first key row"){
    val keyTop=d.findObject(By.res("com.google.android.inputmethod.latin:id/key_pos_0_0"))?.visibleBounds?.top
    val close=d.findObject(By.res("native-room-search-close"))?.visibleBounds
    keyTop!=null&&close!=null&&close.bottom<keyTop
   }
   tapStable("native-room-search-close")
   await("Closing search lost room membership"){d.hasObject(By.res("native-room:"+target))&&!d.hasObject(By.res("native-room-search"))}
   tapStable("native-catalog-controls")
   await("Anchored catalog settings/update menu missing"){d.hasObject(By.res("native-catalog-settings"))}
   assertFalse("Environment list obscures settings before expansion",d.hasObject(By.res("environment-choice:live_room_qa_20261015")))
   tapStable("environment-picker-toggle")
   await("Environment choices did not expand inside their existing menu"){d.hasObject(By.res("environment-choice:live_room_qa_20261015"))}
   tapStable("environment-picker-toggle")
   await("Environment choices did not collapse in place"){!d.hasObject(By.res("environment-choice:live_room_qa_20261015"))}
   assertTrue("Settings vanished after environment expansion",d.hasObject(By.res("native-catalog-settings")))
   d.pressBack()
   await("Catalog menu did not close in place"){!d.hasObject(By.res("native-catalog-settings"))}
   d.takeScreenshot(c.filesDir.resolve("room-catalog-live.png"))
   d.findObject(By.res("native-room:"+target)).click()
   await("Original message missing on return"){d.hasObject(By.textContains(latest))}
   val page=client.call("rooms/$target/history?before=9007199254740991&limit=100").getJSONArray("events").objects()
   assertEquals(1,page.count{it.s("id")=="4564d27f-0fd2-4365-8d8b-69022e098fb7"})
   c.filesDir.resolve("room-catalog-live-receipt.json").writeText(JSONObject().put("passed",true).put("actualCanonicalActivity",canonical.getJSONObject("activity")).put("originalMessageCount",1).put("sharedCatalogControls",true).put("bottomSearchAndClear",true).put("submittedMessages",0).put("physicalPhone",false).toString())
  }catch(e:Throwable){d.takeScreenshot(c.filesDir.resolve("room-catalog-live-failure.png"));d.dumpWindowHierarchy(c.filesDir.resolve("room-catalog-live-failure.xml"));throw e}
  finally{Configurator.getInstance().waitForIdleTimeout=oldIdle;i.runOnMainSync{if(Pocket.captureEnvironment(original)!=null)Pocket.activateEnvironment(original)}}
 }
}
