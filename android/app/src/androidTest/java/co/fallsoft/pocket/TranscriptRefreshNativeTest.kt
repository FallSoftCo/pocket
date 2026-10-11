package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class TranscriptRefreshNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun await(b:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(b())return;Thread.sleep(50)};assertTrue("Freshness condition timed out",b())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun has(n:Int)=PocketTranscript.rows.any{it.s("text")=="Fresh response "+n}
 @Test fun previewDuringScheduledRecentReadIsNotLost(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep my draft").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance")
  await{qa("state").getJSONArray("reads").objects().any{it.optInt("revision")==2}}
  qa("advance");await{Pocket.tasks.first().preview=="Fresh response 3"}
  assertTrue("Cached messages hidden during in-flight refresh",has(1))
  qa("release")
  await{has(3)&&!PocketTranscript.updating}
  assertEquals("Draft lost during coalesced refresh","Keep my draft",prefs.getString("draft:same-thread",null))
  val reads=qa("state").getJSONArray("reads").length()
  qa("repeat");Thread.sleep(1000)
  assertEquals("Unchanged preview caused repeated reads",reads,qa("state").getJSONArray("reads").length())
 }
 @Test fun cachedPreviewRefreshIsVisibleAndLatestWinsOverOlderHistory(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Unsaved draft preserved").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance");await{Pocket.tasks.first().preview=="Fresh response 2"&&PocketTranscript.updating}
  assertTrue("Cached content disappeared during refresh",has(1))
  assertTrue("Refresh indicator not visible",device.wait(Until.hasObject(By.text("Updating messages")),2000))
  qa("release")
  await{has(2)&&!PocketTranscript.updating}
  val reads=qa("state").getJSONArray("reads").length()
  qa("repeat");Thread.sleep(800)
  assertEquals("Repeated previews triggered polling",reads,qa("state").getJSONArray("reads").length())
  val readerSettled=kotlinx.coroutines.CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={readerSettled.await()})}};await{PocketTranscript.loading}
  qa("advance");await{PocketTranscript.updating}
  Thread.sleep(2400)
  assertFalse("Older page inserted before reader settled",PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
  readerSettled.complete(Unit)
  await{has(3)&&!PocketTranscript.updating}
  assertTrue("Older loaded rows lost",PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
  qa("silent");main{Pocket.refresh()};await{has(4)&&!PocketTranscript.updating}
  main{Pocket.closeTask();Pocket.open("same-thread")}
  await{PocketTranscript.updating};assertTrue("Warm history hidden",has(4))
  await{!PocketTranscript.loading&&!PocketTranscript.updating}
  assertEquals("Unsaved draft preserved",prefs.getString("draft:same-thread",null))
  qa("order")
  fun live(id:String,kind:String,text:String,version:Int)=JSONObject().put("threadId","same-thread").put("turnId","order").put("version",version).put("row",JSONObject().put("id","order/"+id).put("turnId","order").put("kind",kind).put("text",text).put("version",version))
  main{
   PocketTranscript.reset("same-thread")
   PocketTranscript.apply(live("answer","message","Cached ordering answer",100))
   PocketTranscript.apply(live("user","user","Ordering request",101))
   Pocket.scope.launchEnvironment{PocketTranscript.load()}
  }
  await{PocketTranscript.loading}
  main{PocketTranscript.apply(live("answer","message","Newer answer during fetch",900))}
  await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="order/end"}}
  assertEquals(listOf("older-message","same-message","order/header","order/user","order/answer","order/end"),PocketTranscript.rows.map{it.s("id")})
  assertEquals("Newer answer during fetch",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  assertTrue("Newer answer absent from native view",device.wait(Until.hasObject(By.text("Newer answer during fetch")),2000))
  assertEquals("Draft changed during ordering repair","Unsaved draft preserved",prefs.getString("draft:same-thread",null))
  main{
   Pocket.detail=JSONObject(requireNotNull(Pocket.detail).toString()).put("outgoing",org.json.JSONArray().put(JSONObject().put("id","failed-ui").put("state","failed").put("mode","auto").put("text","Synthetic failed draft").put("result","Synthetic transport failure")))
  }
  assertTrue("Retry control missing",device.wait(Until.hasObject(By.text("Retry")),3000))
  fun actionEnabled(label:String):Boolean{
   var node=device.findObject(By.text(label))?:return false
   while(true){if(!node.isEnabled)return false;node=node.parent?:return true}
  }
  main{Pocket.replyActionBusy=setOf("other-environment:same-thread:failed-ui")}
  assertTrue("Other environment disabled this action",actionEnabled("Retry"))
  main{Pocket.replyActionBusy=setOf("${Pocket.environmentId}:same-thread:failed-ui")}
  await{!actionEnabled("Retry")}
  assertFalse("Edit available during pending request",actionEnabled("Edit"))
  assertFalse("Remove available during pending request",actionEnabled("Remove"))
  main{Pocket.replyActionBusy=emptySet()}
  await{actionEnabled("Retry")}
  main{Pocket.reply("Synthetic rapid submission");Pocket.reply("Synthetic rapid submission")}
  await{!Pocket.sending}
  assertEquals("Rapid input created duplicate submissions",1,qa("state").getJSONArray("replies").length())
  assertTrue("Keyboard entry missing",device.wait(Until.hasObject(By.pkg("co.fallsoft.pocket").text("Keyboard")),3000))
  device.findObject(By.pkg("co.fallsoft.pocket").text("Keyboard")).click()
  assertTrue("Editor did not open",device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000))
  device.findObject(By.clazz("android.widget.EditText")).text="One handed draft"
  await{prefs.getString("draft:same-thread",null)=="One handed draft"}
  assertFalse("Keyboard control duplicated while typing",device.hasObject(By.pkg("co.fallsoft.pocket").text("Keyboard")))
  device.pressBack()
  await{Pocket.selected==null}
  assertEquals("Back lost the draft","One handed draft",prefs.getString("draft:same-thread",null))
  main{Pocket.open("same-thread",keyboard=true)}
  assertTrue("Draft not restored in editor",device.wait(Until.hasObject(By.clazz("android.widget.EditText").text("One handed draft")),3000))
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
  await{qa("state").getJSONArray("replies").length()==2&&!Pocket.sending}
  assertEquals("Enter changed the sent draft","One handed draft",qa("state").getJSONArray("replies").getJSONObject(1).s("text"))
  await{!prefs.contains("draft:same-thread")}
  device.findObject(By.clazz("android.widget.EditText")).text="Voice must keep this draft"
  await{prefs.getString("draft:same-thread",null)=="Voice must keep this draft"}
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
  await{PocketVoice.state=="Listening"}
  assertEquals("Capture routed away from current chat","same-thread",PocketVoice.targetThread)
  assertTrue("Recording timer not initialized",PocketVoice.recordingStartedAt>0)
  assertTrue("Recording feedback not visible",device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Recording .*"))),2000))
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
  await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
  assertEquals("Voice keys lost typed draft","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  assertFalse("Silent emulator capture submitted a voice turn",prefs.contains("voicePendingId"))
  assertTrue("Capture error hidden",device.wait(Until.hasObject(By.text(PocketVoice.problem)),2000))
  assertTrue("Touch microphone retry missing",device.wait(Until.hasObject(By.desc("Start recording")),2000))
  device.findObject(By.desc("Start recording")).click()
  await{PocketVoice.state=="Listening"}
  assertTrue("One-tap stop control missing",device.wait(Until.hasObject(By.desc("Stop and send recording")),2000))
  device.findObject(By.desc("Stop and send recording")).click()
  await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
  assertEquals("Repeated touch recording lost draft","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  main{PocketVoice.stop()}
  main{for(n in 0..29){PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","read-error").put("version",1000+n).put("row",JSONObject().put("id","error-reading-$n").put("turnId","read-error").put("kind","message").put("text","Older reading position $n: retained synthetic material filling the conversation so errors at its end would be offscreen.")))}}
  assertTrue(device.wait(Until.hasObject(By.textContains("Older reading position 29:")),3000))
  repeat(3){requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.7f);Thread.sleep(150)}
  assertFalse("Reading setup remained at transcript end",device.hasObject(By.textContains("Older reading position 29:")))
  val retained=PocketTranscript.rows.map{it.s("id")}
  qa("fail-read");main{Pocket.refreshDetail()}
  await{!PocketTranscript.loading&&Pocket.error.contains("Synthetic history refresh unavailable")}
  assertEquals("Failed refresh discarded local history",retained,PocketTranscript.rows.map{it.s("id")})
  assertTrue("History refresh error hidden",device.wait(Until.hasObject(By.textContains("Synthetic history refresh unavailable")),2000))
  assertTrue("Read recovery inaccessible",device.wait(Until.hasObject(By.text("Reload conversation")),2000))
  device.findObject(By.text("Reload conversation")).click()
  await{!PocketTranscript.loading&&Pocket.error.isBlank()}
  assertEquals("Read retry erased typing","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  val readsBeforeRejected=qa("state").getJSONArray("reads").length()
  qa("fail-read-permanent")
  main{Pocket.refreshDetail();Pocket.refreshDetail()}
  await{!PocketTranscript.loading&&Pocket.error.contains("Synthetic history permission rejected")}
  assertFalse("Permission failure still permits automatic reads",PocketTranscript.automaticRetryAllowed)
  assertFalse("Rejected coalesced read left perpetual Updating messages",PocketTranscript.updating)
  Thread.sleep(900)
  assertEquals("Rejected read retried unchanged credentials",readsBeforeRejected+1,qa("state").getJSONArray("reads").length())
  device.findObject(By.text("Reload conversation")).click()
  await{!PocketTranscript.loading&&Pocket.error.isBlank()}
  assertTrue("Explicit successful reload did not restore automatic pagination",PocketTranscript.automaticRetryAllowed)

 }
}
