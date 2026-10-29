package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommandHistoryNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device=UiDevice.getInstance(i)
 private fun main(block:()->Unit)=i.runOnMainSync(block)
 private fun await(condition:()->Boolean){
  val end=System.currentTimeMillis()+15000
  while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)}
  assertTrue("Synthetic conversation did not become ready",condition())
 }
 @Test fun groupedRunsExpandAndCollapseWithoutHidingAnswersOrMutatingHistory(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")}
  await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  fun append(id:String,kind:String,text:String,type:String="",detail:String=""){
   main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","command-native").put("version",2000)
    .put("row",JSONObject().put("id","command-native/"+id).put("turnId","command-native")
     .put("kind",kind).put("type",type).put("status","completed").put("title","Native command "+id)
     .put("text",text).put("detail",detail).put("version",2000)))}
  }
  append("before","message","Answer before commands")
  append("one","activity","First command stdout","commandExecution","First full command detail")
  append("two","activity","Waiting for the operation","sleep","Wait detail preserved")
  append("after","message","Answer after commands")
  assertTrue(device.wait(Until.hasObject(By.text("Answer after commands")),5000))
  assertFalse(device.hasObject(By.text("First command stdout")))
  assertFalse(device.hasObject(By.text("Waiting for the operation")))
  val source=PocketTranscript.rows.map{it.toString()}
  main{Pocket.activities=listOf(JSONObject().put("threadId","same-thread").put("previewKind","terminalInteraction").put("state","working"))}
  assertTrue(device.wait(Until.hasObject(By.text("Using existing terminal")),5000))
  assertEquals("Live terminal activity changed canonical history",source,PocketTranscript.rows.map{it.toString()})
  main{Pocket.activities=listOf(JSONObject().put("threadId","other-thread").put("previewKind","terminalInteraction").put("state","working"))}
  assertTrue(device.wait(Until.gone(By.text("Using existing terminal")),5000))
  main{Pocket.activities=listOf(JSONObject().put("threadId","same-thread").put("previewKind","terminalInteraction").put("state","finished"))}
  assertFalse(device.hasObject(By.text("Using existing terminal")))
  requireNotNull(device.findObject(By.desc("Expand command runs"))).click()
  assertTrue(device.wait(Until.hasObject(By.text("First full command detail")),5000))
  assertTrue(device.wait(Until.hasObject(By.text("Wait detail preserved")),5000))
  assertTrue(device.hasObject(By.text("Answer after commands")))
  assertEquals("Expansion changed canonical history",source,PocketTranscript.rows.map{it.toString()})
  val protected=PocketTranscript.rows.filter{it.s("id").startsWith("command-native/")}.map{Triple(it.s("id"),it.s("text"),it.s("detail"))}
  main{Pocket.open("second-thread")}
  await{Pocket.selected=="second-thread"&&!PocketTranscript.loading}
  main{Pocket.open("same-thread")}
  await{Pocket.selected=="same-thread"&&!PocketTranscript.loading}
  assertTrue("Returning collapsed the command passage",device.wait(Until.hasObject(By.text("First full command detail")),5000))
  assertTrue(device.hasObject(By.text("Wait detail preserved")))
  requireNotNull(device.findObject(By.text("Collapse runs"))).click()
  assertTrue(device.wait(Until.gone(By.text("First full command detail")),5000))
  assertFalse(device.hasObject(By.text("Waiting for the operation")))
  assertTrue(device.hasObject(By.text("Answer after commands")))
  assertEquals("Navigation lost or changed the command passage",protected,PocketTranscript.rows.filter{it.s("id").startsWith("command-native/")}.map{Triple(it.s("id"),it.s("text"),it.s("detail"))})
  assertEquals("same-thread",Pocket.selected)
 }
}
