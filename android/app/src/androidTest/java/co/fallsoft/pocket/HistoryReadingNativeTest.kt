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
 private fun visiblePassages():List<Pair<String,android.graphics.Rect>>{
  val out=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(out)
  val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.ByteArrayInputStream(out.toByteArray()))
  val nodes=doc.getElementsByTagName("node")
  return (0 until nodes.length).mapNotNull{index->val n=nodes.item(index) as org.w3c.dom.Element;val text=n.getAttribute("text");if(!text.startsWith("Reading passage "))null else {val b=Regex("[0-9]+").findAll(n.getAttribute("bounds")).map{it.value.toInt()}.toList();if(b.size!=4)null else text to android.graphics.Rect(b[0],b[1],b[2],b[3])}}
 }
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$path",JSONObject())}
 private var eventVersion=900
 private fun event(id:String,kind:String,text:String):JSONObject{val version=++eventVersion;return JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","order").put("version",version).put("row",JSONObject().put("id","order/$id").put("turnId","order").put("kind",kind).put("text",text).put("version",version))}
 @Test fun readingAnchorSurvivesDeferredOlderPage(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putBoolean("diagnostic:scroll",true).commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","reading").put("version",100+n).put("row",JSONObject().put("id","reading-$n").put("turnId","reading").put("kind","message").put("text","Reading passage $n: retained synthetic conversation text with a clear place to return while earlier history arrives.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Reading passage 39:")),5000));Thread.sleep(150)
  repeat(3){requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.7f);Thread.sleep(200)}
  device.waitForIdle(2000)
  device.dumpWindowHierarchy(java.io.File(c.cacheDir,"history-reading-hierarchy.xml"))
  val visible=visiblePassages()
  val last=visible.firstOrNull{it.first.startsWith("Reading passage 39:")}
  assertTrue("Reading gesture returned to latest: ${last?.second}",last==null||last.second.isEmpty)
  val anchor=requireNotNull(visible.firstOrNull{!it.second.isEmpty&&it.second.top>50&&it.second.bottom<device.displayHeight*3/4})
  val text=anchor.first;val top=anchor.second.top
  val settled=CompletableDeferred<Unit>();val arrived=CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={arrived.complete(Unit);settled.await()})}}
  await{PocketTranscript.loading};await{arrived.isCompleted}
  assertFalse("Page applied during unsettled read",PocketTranscript.rows.any{it.s("id")=="older-message"})
  settled.complete(Unit);await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="older-message"}}
  assertTrue("Prepend shifted reading passage",kotlin.math.abs(requireNotNull(visiblePassages().firstOrNull{it.first==text}).second.top-top)<=24)

 }
 @Test fun lateInputEventReconcilesCanonicalOrderOnce(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putBoolean("diagnostic:scroll",true).commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("order")
  main{Pocket.event(event("answer","message","Initial streamed answer"));Pocket.event(event("user","user","Ordering request"))}
  await{PocketTranscript.loading}
  main{Pocket.event(event("answer","message","New answer during reconciliation"))}
  await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="order/end"}}
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Late input remains after its response",ids.indexOf("order/user")<ids.indexOf("order/answer"))
  assertEquals("New streamed answer overwritten","New answer during reconciliation",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  val reads=qa("state").getJSONArray("reads").length();main{Pocket.event(event("user","user","Ordering request"))};Thread.sleep(700)
  assertEquals("Repeated input triggered polling",reads,qa("state").getJSONArray("reads").length())
 }

 @Test fun shortHistoryPagesRequireAnotherReadingGesture(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("pacing")
  main{for(n in 0..7)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","reading").put("version",100+n).put("row",JSONObject().put("id","pacing-read-$n").put("turnId","reading").put("kind","message").put("text","Reading passage $n: retained synthetic conversation text with a clear place to return while earlier history arrives.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Reading passage 7:")),5000));Thread.sleep(150)
  requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.9f)
  await{PocketTranscript.rows.any{it.s("id")=="pacing-1"}&&!PocketTranscript.loading}
  Thread.sleep(3000)
  fun olderReads()=qa("state").getJSONArray("reads").objects().count{it.optBoolean("older")}
  assertEquals("Idle edge drained multiple history pages",1,olderReads())
  requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.5f)
  await{PocketTranscript.rows.any{it.s("id")=="pacing-2"}&&!PocketTranscript.loading}
  assertEquals("Second gesture did not load exactly one page",2,olderReads())
 }

}
