package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryComposeNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun nativeReadingPositionSurvivesOlderHistoryAndWarmReopen(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep reading draft").commit()
  fun main(b:()->Unit)=i.runOnMainSync(b)
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};compose.waitUntil(15000){PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  fun text(n:Int)="Native reading passage $n: retained conversation text stays in place as older messages load and this conversation is reopened."
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","native-reading").put("version",100+n).put("row",JSONObject().put("id","reading-native-$n").put("turnId","native-reading").put("kind","message").put("text",text(n))))}
  val history=compose.onNodeWithTag("conversation-history")
  history.performScrollToKey("reading-native-10")
  history.performTouchInput{swipe(center,center+Offset(0f,80f),500)}
  compose.waitForIdle()
  val passage=compose.onNodeWithText(text(11),useUnmergedTree=true)
  val top=passage.fetchSemanticsNode().boundsInRoot.top
  val arrived=CompletableDeferred<Unit>();val settled=CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={arrived.complete(Unit);settled.await()})}}
  compose.waitUntil(15000){arrived.isCompleted}
  assertEquals("Pending older read moved text",top,passage.fetchSemanticsNode().boundsInRoot.top,2f)
  assertFalse(PocketTranscript.rows.any{it.s("id")=="older-message"})
  settled.complete(Unit)
  compose.waitUntil(15000){!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="older-message"}}
  assertEquals("Older page shifted reading passage",top,passage.fetchSemanticsNode().boundsInRoot.top,2f)
  main{Pocket.closeTask()};compose.waitForIdle()
  main{Pocket.open("same-thread")};compose.waitUntil(15000){!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="reading-native-11"}}
  assertEquals("Warm reopen shifted reading passage",top,passage.fetchSemanticsNode().boundsInRoot.top,2f)
  assertTrue("Warm reopen hid already local history",PocketTranscript.rows.any{it.s("id")=="older-message"})
  assertEquals("History navigation lost draft","Keep reading draft",prefs.getString("draft:same-thread",null))
  compose.waitUntil(15000){c.noBackupFilesDir.resolve("transcript-cache-v1").listFiles()?.filter{it.extension=="json"}?.any{file->try{val value=JSONObject(file.readText());value.optJSONObject("position")?.s("row")?.startsWith("reading-native-")==true&&value.optJSONArray("rows")?.objects()?.any{it.s("id")=="older-message"}==true}catch(_:Exception){false}}==true}
  prefs.edit().putFloat("qualification:reading-top",top).commit()

 }
 @Test fun coldProcessRestoresHistoryAndPositionBeforeSlowRecentRead(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  assertTrue("Preparation did not finish",prefs.contains("qualification:reading-top"))
  fun main(b:()->Unit)=i.runOnMainSync(b)
  main{Pocket.init(c)}
  assertTrue("App was not a fresh process",PocketTranscript.rows.isEmpty())
  fun qa(path:String)=kotlinx.coroutines.runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$path",JSONObject())}
  qa("advance") // revision2 intentionally holds recent history in the owned fixture.
  main{c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")}
  compose.waitUntil(15000){PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="reading-native-11"}}
  val text="Native reading passage 11: retained conversation text stays in place as older messages load and this conversation is reopened."
  assertEquals("Cold process lost reading position",prefs.getFloat("qualification:reading-top",-1f),compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,2f)
  assertTrue("Cold process hid previously local earlier history",PocketTranscript.rows.any{it.s("id")=="older-message"})
  assertTrue("Stale cache exposed pending action authority",Pocket.detail?.optJSONArray("pending")==null&&Pocket.detail?.optJSONArray("outgoing")==null&&Pocket.detail?.optJSONObject("catchup")==null)
  qa("release")
  compose.waitUntil(15000){!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}}
  assertEquals("Fresh read moved cached reading position",prefs.getFloat("qualification:reading-top",-1f),compose.onNodeWithText(text,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top,2f)
  assertEquals("Cold restore lost draft","Keep reading draft",prefs.getString("draft:same-thread",null))
  assertEquals("Restore submitted work",0,qa("state").getJSONArray("replies").length())
 }

}
