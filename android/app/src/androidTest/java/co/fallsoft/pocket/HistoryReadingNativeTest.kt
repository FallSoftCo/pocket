package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class HistoryReadingNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device=UiDevice.getInstance(i)
 private fun main(block:()->Unit)=i.runOnMainSync(block)
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue("Reading condition timed out",condition())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$path",JSONObject())}
 private fun event(id:String,kind:String,text:String)=JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","order").put("version",900).put("row",JSONObject().put("id","order/$id").put("turnId","order").put("kind",kind).put("text",text).put("version",900))
 @Test fun readingAnchorAndLateInputAreReconciledWithoutVoice(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","reading").put("version",100+n).put("row",JSONObject().put("id","reading-$n").put("turnId","reading").put("kind","message").put("text","Reading passage $n: retained synthetic conversation text with a clear place to return while earlier history arrives.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Reading passage 39:")),5000));Thread.sleep(150)
  repeat(3){device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*2/3,25);Thread.sleep(200)}
  val last=device.findObject(By.textContains("Reading passage 39:"))
  println("Reading visible bounds: "+device.findObjects(By.textContains("Reading passage ")).map{it.text.substringBefore(":")+":"+it.visibleBounds})
  assertTrue("Reading gesture returned to latest: ${last?.visibleBounds}",last==null||last.visibleBounds.isEmpty)
  val anchor=requireNotNull(device.findObjects(By.textContains("Reading passage ")).firstOrNull{it.visibleBounds.top>50&&it.visibleBounds.bottom<device.displayHeight*3/4})
  val text=anchor.text;val top=anchor.visibleBounds.top
  val settled=CompletableDeferred<Unit>();val arrived=CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={arrived.complete(Unit);settled.await()})}}
  await{PocketTranscript.loading};await{arrived.isCompleted}
  assertFalse("Page applied during unsettled read",PocketTranscript.rows.any{it.s("id")=="older-message"})
  settled.complete(Unit);await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="older-message"}}
  assertTrue("Prepend shifted reading passage",kotlin.math.abs(requireNotNull(device.findObject(By.text(text))).visibleBounds.top-top)<=24)
  qa("order")
  main{PocketTranscript.reset("same-thread");Pocket.event(event("answer","message","Live answer retained"));Pocket.event(event("user","user","Ordering request"))}
  await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="order/end"}}
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Late input remains after its response",ids.indexOf("order/user")<ids.indexOf("order/answer"))
  assertEquals("Reconciliation overwrote streamed answer","Live answer retained",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  val reads=qa("state").getJSONArray("reads").length()
  main{Pocket.event(event("user","user","Ordering request"))};Thread.sleep(700)
  assertEquals("Repeated input caused history polling",reads,qa("state").getJSONArray("reads").length())
 }
}
