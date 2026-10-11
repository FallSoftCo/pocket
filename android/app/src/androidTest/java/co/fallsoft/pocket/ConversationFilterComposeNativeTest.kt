package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ConversationFilterComposeNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun filteringKeepsNativeAnswerGeometryAndVisibleInk(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep filter draft").commit()
  i.runOnMainSync{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  i.runOnMainSync{Pocket.open("same-thread")};compose.waitUntil(15000){PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  fun text(n:Int)="Compose passage $n: a meaningful answer remains at its reading position when surrounding commentary is hidden or restored."
  i.runOnMainSync{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","compose-reading").put("version",100+n).put("row",JSONObject().put("id","compose-passage-$n").put("turnId","compose-reading").put("kind","message").put("phase",if(n%2==0)"final_answer" else "commentary").put("text",text(n))))}
  val changes=java.util.concurrent.ConcurrentHashMap<Int,java.util.concurrent.atomic.AtomicInteger>()
  val descriptions=java.util.concurrent.ConcurrentHashMap<Int,String>()
  val observer=androidx.compose.runtime.snapshots.Snapshot.registerApplyObserver{states,_->states.forEach{state->val key=System.identityHashCode(state);changes.computeIfAbsent(key){java.util.concurrent.atomic.AtomicInteger()}.incrementAndGet();if(!descriptions.containsKey(key))descriptions.putIfAbsent(key,state.toString().take(180))}}
  try {
  // Arrange via the real LazyColumn's native scroll semantics, avoiding stale
  // UiAutomation virtual-node coordinates during an in-flight gesture.
  compose.onNodeWithTag("conversation-history").performScrollToKey("compose-passage-33")
  val answer=compose.onNodeWithText(text(34),useUnmergedTree=true)
  val before=answer.fetchSemanticsNode().boundsInRoot
  fun ink(name:String):Int{
   val bitmap=answer.captureToImage().asAndroidBitmap()
   java.io.File(c.cacheDir,"filter-compose-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
   var count=0;for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val p=bitmap.getPixel(x,y);if(android.graphics.Color.red(p)>150&&android.graphics.Color.green(p)>150&&android.graphics.Color.blue(p)>150)count++};return count
  }
  val originalInk=ink("before");assertTrue("Original answer not drawn",originalInk>100)
  compose.onNodeWithText("Important").performClick()
  val filtered=answer.fetchSemanticsNode().boundsInRoot
  assertEquals("Filter moved native answer",before.top,filtered.top,2f)
  assertTrue("Filtered answer visually blank",ink("important")>=originalInk*.7)
  compose.onNodeWithText("Important").performClick()
  assertEquals("Return moved native answer",before.top,answer.fetchSemanticsNode().boundsInRoot.top,2f)
  assertTrue("Returned answer visually blank",ink("restored")>=originalInk*.7)
  assertEquals("Filter erased draft","Keep filter draft",c.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  }finally{observer.dispose();changes.entries.sortedByDescending{it.value.get()}.take(20).forEach{println("Compose changes ${it.value.get()}: "+descriptions[it.key])}}
 }
 @Test fun savedAnswerReturnsToUnfilteredConversationWithoutLeavingTailSpace(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  fun main(b:()->Unit)=i.runOnMainSync(b)
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};compose.waitUntil(15000){PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  fun text(n:Int)="Saved passage $n: useful context is readable in its original conversation and the latest reply has no leftover filter space."
  main{
   Pocket.detail=JSONObject(requireNotNull(Pocket.detail).toString()).put("notes",org.json.JSONArray().put(JSONObject().put("id","saved-passage-34").put("text",text(34))))
   for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","saved-reading").put("version",100+n).put("row",JSONObject().put("id","saved-passage-$n").put("turnId","saved-reading").put("kind","message").put("phase",if(n%2==0)"final_answer" else "commentary").put("text",text(n))))
  }
  val history=compose.onNodeWithTag("conversation-history")
  history.performScrollToKey("saved-passage-39")
  val last=compose.onNodeWithText(text(39),useUnmergedTree=true)
  val unfilteredEnd=last.fetchSemanticsNode().boundsInRoot.top
  history.performScrollToKey("saved-passage-33")
  compose.onNodeWithText("Important").performClick()
  compose.onNodeWithText("Surrounding conversation").performClick()
  compose.waitUntil(15000){!compose.onAllNodesWithText("Surrounding conversation").fetchSemanticsNodes().any()}
  history.performScrollToKey("saved-passage-39")
  assertEquals("Filter padding leaked into ordinary conversation",unfilteredEnd,last.fetchSemanticsNode().boundsInRoot.top,2f)
 }

}
