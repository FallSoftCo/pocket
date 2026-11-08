package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class QueuedMessagesNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device get()=UiDevice.getInstance(i)
 private val c get()=i.targetContext
 private fun main(action:()->Unit)=i.runOnMainSync(action)
 private fun await(condition:()->Boolean){compose.waitUntil(15000,condition);compose.waitForIdle()}
 @Test fun queuedMessagesStayVisibleAndRemoveWithoutHistory(){
  val args=InstrumentationRegistry.getArguments()
  org.junit.Assume.assumeTrue("Requires real isolated queue backend",args.getString("pairedToken")!=null)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val tid="11111111-1111-4111-8111-111111111111"
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880")
   .putString("token",args.getString("pairedToken")).putString("deviceId",args.getString("pairedDevice")).commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.any{it.id==tid}}
  compose.onNodeWithContentDescription("Open Chat for Queued message study",useUnmergedTree=true).performClick()
  await{Pocket.selected==tid&&Pocket.detail?.optJSONArray("outgoing")?.length()==3}
  compose.waitUntil(10000){compose.onAllNodesWithTag("outgoing-queued-ui-3").fetchSemanticsNodes().any{!it.boundsInRoot.isEmpty}}
  compose.onNodeWithTag("outgoing-queued-ui-3").assertIsDisplayed()
  compose.onNodeWithText("Third queued request",useUnmergedTree=true).assertIsDisplayed()
  compose.onNodeWithText("Keyboard").performClick()
  compose.onNode(hasSetTextAction()).performTextReplacement("Keep my unsent draft")
  val started=android.os.SystemClock.elapsedRealtime()
  compose.onNode(hasText("Remove") and hasAnyAncestor(hasTestTag("outgoing-queued-ui-3"))).performClick()
  await{Pocket.detail?.optJSONArray("outgoing")?.length()==2}
  compose.onNodeWithTag("outgoing-queued-ui-3").assertDoesNotExist()
  assertEquals("Keep my unsent draft",compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
  assertEquals("Keep my unsent draft",prefs.getString(Pocket.key("draft:$tid"),null))
  java.io.File(c.cacheDir,"queued-message-native.json").writeText(org.json.JSONObject().put("visiblePendingRows",3).put("afterRemove",2).put("removeMs",android.os.SystemClock.elapsedRealtime()-started).put("draftPreserved",true).toString())
  device.takeScreenshot(java.io.File(c.cacheDir,"queued-message-native.png"))
 }
}
