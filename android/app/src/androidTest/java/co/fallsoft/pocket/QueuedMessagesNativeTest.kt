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
  assertTrue(qa("attention-setup").optBoolean("ok"))
  try{
   main{c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await{Pocket.tasks.any{it.id==tid&&it.needsReview()}&&Pocket.tasks.any{it.id=="ordinary-work"}}
   compose.onNodeWithText("Work updates",useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithTag("needs-you-filter").performClick()
   compose.onNodeWithText("Ordinary completed work",useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithText("All sessions",useUnmergedTree=true).assertIsDisplayed()
   assertTrue(device.takeScreenshot(java.io.File(c.cacheDir,"needs-you-native-list.png")))
   // Needs you keeps the named parent and expands both affected child rows.
   prefs.edit().putString(Pocket.key("draft:$tid"),"Preserve parent guidance draft").commit()
   compose.onNodeWithText("Review runtime error",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
   compose.onNodeWithText("Review agent",useUnmergedTree=true).performScrollTo().performClick()
   await{Pocket.selected=="attention-error"&&Pocket.detail?.optJSONObject("thread")?.s("id")=="attention-error"}
   compose.onNodeWithText("Parent: Native canonical work",useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithText("Keyboard",useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithText("Open parent task",useUnmergedTree=true).performClick()
   await{Pocket.selected==tid&&PocketTranscript.currentHistoryVerified}
   assertTrue(requireNotNull(Pocket.captureEnvironment()).matches(destination))
   assertEquals("Preserve parent guidance draft",prefs.getString(Pocket.key("draft:$tid"),null))
   compose.onNode(hasSetTextAction()).assertTextEquals("Preserve parent guidance draft")
   val parentOpened=qa("attention-state")
   assertEquals("Opening parent must not send guidance",0,parentOpened.optInt("workSubmissions"))
   assertEquals(0,parentOpened.getJSONArray("calls").length())
   assertEquals(0,parentOpened.getJSONArray("answers").length())
   device.pressBack() // Hide the parent's keyboard before returning to the catalog.
   main{Pocket.closeTask();UnifiedWorkCatalog.refresh()}
   await{Pocket.selected==null&&Pocket.tasks.any{it.id=="attention-question"&&it.needsReview()}}
   compose.onNodeWithText("Review request",useUnmergedTree=true).performScrollTo().performClick()
   await{Pocket.selected=="attention-question"&&Pocket.detail?.optJSONArray("pending")?.length()==1}
   compose.onNodeWithText("Parent: Native canonical work",useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithText("Which path should this child take?",useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithText("Keyboard",useUnmergedTree=true).assertDoesNotExist()
   assertTrue(device.takeScreenshot(java.io.File(c.cacheDir,"needs-you-child-request.png")))
   compose.onNodeWithText("Proceed",useUnmergedTree=true).performScrollTo().performClick()
   compose.onNodeWithText("Send answers",useUnmergedTree=true).performScrollTo().performClick()
   await{qa("attention-state").getJSONArray("answers").length()==1&&Pocket.detail?.optJSONArray("pending")?.length()==0}
   val childReceipt=qa("attention-state")
   val answer=childReceipt.getJSONArray("answers").getJSONObject(0)
   assertEquals("attention-question",answer.getString("threadId"))
   assertEquals("attention-child-request",answer.getString("requestId"))
   assertEquals("original",answer.getString("origin"));assertEquals("Proceed",answer.getString("answer"))
   assertEquals(0,childReceipt.optInt("workSubmissions"));assertEquals(0,childReceipt.getJSONArray("calls").length())
   assertEquals("Preserve parent guidance draft",prefs.getString(Pocket.key("draft:$tid"),null))
   java.io.File(c.cacheDir,"needs-you-child-receipt.json").writeText(childReceipt.toString())
   main{Pocket.closeTask();UnifiedWorkCatalog.refresh()}
   await{Pocket.selected==null&&Pocket.tasks.firstOrNull{it.id=="attention-question"}?.needsReview()==false}
   compose.onNodeWithContentDescription("Open Chat for Native canonical work").performScrollTo().performClick()
   await{Pocket.selected==tid&&PocketTranscript.currentHistoryVerified&&Pocket.detail?.optJSONArray("outgoing")?.length()==1}
   compose.onNodeWithTag("outgoing-needs-review-original").assertIsDisplayed()
   assertEquals("Review opened without resubmitting",0,qa("queue-state").getJSONArray("calls").length())
   val resolved=qa("attention-resolve");assertEquals(0,resolved.optInt("reportReads"))
   main{Pocket.closeTask();UnifiedWorkCatalog.refresh()}
   await{Pocket.tasks.firstOrNull{it.id==tid}?.needsReview()==false}
   compose.onNodeWithText("All sessions",useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithTag("needs-you-filter").performClick()
   await{compose.onAllNodesWithText("Ordinary completed work",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
   assertEquals(0,qa("attention-end").optInt("reportReads"))
   assertTrue("Owned queue fixture setup missing",qa("queue-setup").optBoolean("ok"))
   main{Pocket.open(tid)}
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
