package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class PresentedReadRecoveryNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val i get()=InstrumentationRegistry.getInstrumentation()
 private val c get()=i.targetContext
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment("workstation")),"/_qa/$path",JSONObject())}
 private fun guard(){check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")}
 @Test fun failedPresentedAcknowledgementsPersistBeforeProcessStops(){
  guard();val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val journal=c.getSharedPreferences("presented-reads",Context.MODE_PRIVATE);journal.edit().clear().commit()
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep read recovery draft").putString("qualification:large-other-data","X".repeat(2_000_000)).commit()
  main{Pocket.init(c)};qa("visibility");qa("fail-acks")
  main{c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  compose.waitUntil(15000){Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")}
  compose.waitUntil(15000){PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}&&!PocketTranscript.loading}
  compose.onNodeWithText("Fresh response 1",useUnmergedTree=true).assertIsDisplayed()
  compose.waitUntil(15000){qa("state").getJSONArray("ackAttempts").length()>=2}
  assertEquals(0,qa("state").getJSONArray("acknowledgements").length())
  assertEquals(2,journal.all.keys.count{it.startsWith("presented-read:")})
  assertFalse(PocketNotificationReads.isRead(JSONObject().put("id",1).put("thread_id","same-thread").put("kind","complete")))
  prefs.edit().putInt("qualification:read-recovery-pid",android.os.Process.myPid()).commit()
  qa("silent")
  main{Pocket.closeTask();Pocket.open("same-thread")}
  compose.waitUntil(15000){PocketTranscript.rows.any{it.s("text")=="Fresh response 2"}&&!PocketTranscript.loading}
  compose.onNodeWithText("Fresh response 2",useUnmergedTree=true).assertIsDisplayed()
  compose.waitUntil(15000){journal.all.keys.count{it.startsWith("presented-read:")}==4}
  assertEquals("Newer seen view bypassed an older waiting receipt",0,qa("state").getJSONArray("acknowledgements").length())
  assertTrue("Journal rewrites the large unrelated preference store",c.applicationInfo.dataDir.let{java.io.File(it,"shared_prefs/presented-reads.xml").length()}<8192)
  qa("silent") // View3 is unseen; only the actually presented views1 and2 may recover.
 }
 @Test fun coldProcessRecoversOnlyThePreviouslyPresentedView(){
  guard();val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val journal=c.getSharedPreferences("presented-reads",Context.MODE_PRIVATE)
  assertNotEquals(prefs.getInt("qualification:read-recovery-pid",-1),android.os.Process.myPid())
  assertEquals(4,journal.all.keys.count{it.startsWith("presented-read:")})
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  compose.waitUntil(90000){qa("state").getJSONArray("acknowledgements").length()>=4}
  val state=qa("state");val rows=state.getJSONArray("acknowledgements").objects()
  assertEquals(listOf("capture-1","capture-2"),rows.filter{it.has("token")}.map{it.s("token")})
  assertEquals(listOf(1L,2L),rows.filter{it.has("throughId")}.map{it.optLong("throughId")})
  assertFalse(rows.any{it.s("token")=="capture-3"||it.optLong("throughId")==3L})
  assertTrue(PocketNotificationReads.isRead(JSONObject().put("id",1).put("thread_id","same-thread").put("kind","complete")))
  assertTrue(PocketNotificationReads.isRead(JSONObject().put("id",2).put("thread_id","same-thread").put("kind","complete")))
  assertFalse(PocketNotificationReads.isRead(JSONObject().put("id",3).put("thread_id","same-thread").put("kind","complete")))
  compose.waitUntil(5000){journal.all.keys.none{it.startsWith("presented-read:")}}
  assertNull(Pocket.selected);assertEquals("Keep read recovery draft",prefs.getString("draft:same-thread",null))
  assertEquals(0,state.getJSONArray("replies").length())
 }
}
