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
  requireOwnedNativeTarget(device)
  val tid="same-thread"
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880")
   .putString("token","synthetic-system76-queue").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);PocketImmersion.setEnabled(false)}
  val destination=requireNotNull(Pocket.captureEnvironment())
  fun qa(action:String)=kotlinx.coroutines.runBlocking{Pocket.apiEnvironment(destination,"/_qa/$action",org.json.JSONObject())}
  assertTrue("Owned queue fixture setup missing",qa("queue-setup").optBoolean("ok"))
  try{
   main{c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open(tid)}
   await{PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&Pocket.detail?.optJSONArray("outgoing")?.length()==3}
   fun row(id:String)=compose.onNodeWithTag("outgoing-$id")
   fun action(id:String,label:String)=compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("outgoing-$id")))
   row("queue-promote").assertIsDisplayed()
   compose.onNodeWithText("Original queued steering text",useUnmergedTree=true).assertIsDisplayed()
   action("queue-promote","Steer now").assertIsEnabled()
   compose.onNodeWithText("Keyboard").performClick()
   val editor=compose.onNode(hasSetTextAction())
   editor.performClick();editor.performTextReplacement("Keep my unsent draft")
   await{i.uiAutomation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
   action("queue-remove","Remove").performScrollTo().performClick()
   await{Pocket.detail?.optJSONArray("outgoing")?.length()==2}
   row("queue-remove").assertDoesNotExist()
   assertEquals("Keep my unsent draft",prefs.getString(Pocket.key("draft:$tid"),null))
   val promote=action("queue-promote","Steer now");promote.performScrollTo();promote.assertIsEnabled()
   assertTrue(device.takeScreenshot(java.io.File(c.cacheDir,"queued-message-native-before.png")))
   // Inject two actual touches through the node coordinate transform; root
   // coordinates are not screen coordinates when system bars inset the app.
   promote.performTouchInput{doubleClick()}
   await{qa("queue-state").optJSONArray("calls")?.length()==2}
   compose.onNode(hasText("Steer now") and hasAnyAncestor(hasTestTag("outgoing-queue-promote"))).assertIsNotEnabled()
   assertEquals("Keep my unsent draft",editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
   assertTrue("Queue action dismissed IME",i.uiAutomation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD})
   val pending=qa("queue-state");val calls=pending.getJSONArray("calls")
   assertEquals("queue-remove",calls.getJSONObject(0).getString("id"));assertEquals("remove",calls.getJSONObject(0).getString("action"))
   assertEquals("queue-promote",calls.getJSONObject(1).getString("id"));assertEquals("send",calls.getJSONObject(1).getString("action"))
   assertEquals("Original queued steering text",calls.getJSONObject(1).getString("text"))
   assertEquals("native-turn",calls.getJSONObject(1).getString("expectedActiveTurn"))
   // Leave the originating conversation before its delayed original ACK.
   main{Pocket.closeTask()};qa("queue-release")
   await{qa("queue-state").getJSONObject("outbox").getJSONArray("items").length()==1}
   assertTrue("Late ACK reopened its conversation",Pocket.selected==null)
   main{Pocket.open(tid)}
   await{PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating&&Pocket.detail?.optJSONArray("outgoing")?.length()==1}
   row("queue-promote").assertDoesNotExist();row("queue-keep").assertIsDisplayed()
   assertEquals("held",qa("queue-state").getJSONObject("outbox").getJSONArray("items").getJSONObject(0).getString("state"))
   assertEquals(2,qa("queue-state").getJSONArray("calls").length())
   assertEquals("Keep my unsent draft",prefs.getString(Pocket.key("draft:$tid"),null))
   java.io.File(c.cacheDir,"queued-message-native.json").writeText(org.json.JSONObject().put("syntheticUiFixture",true).put("nativeRpcDeliveryProven",false).put("calls",calls).put("originalAckRemovedPromotion",true).put("otherQueueRemainsHeld",true).put("draftAndImePreservedBeforeNavigation",true).put("lateAckDidNotReopen",true).toString())
   assertTrue(device.takeScreenshot(java.io.File(c.cacheDir,"queued-message-native.png")))
  }catch(error:Throwable){
   device.takeScreenshot(java.io.File(c.cacheDir,"queued-message-native-failure.png"));device.dumpWindowHierarchy(java.io.File(c.cacheDir,"queued-message-native-failure.xml"));throw error
  }finally{qa("queue-release");main{Pocket.closeTask();PocketLive.stop()}}

 }
}
