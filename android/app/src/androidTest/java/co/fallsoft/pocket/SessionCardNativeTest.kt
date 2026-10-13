package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real card composition and dispatch; never contacts a production environment. */
@RunWith(AndroidJUnit4::class)
class SessionCardNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun childBadgeExpandsInPlaceWithoutOpeningParentAndRetainsCompactHeader() {
  val i=InstrumentationRegistry.getInstrumentation()
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device").commit()
  i.runOnMainSync{Pocket.init(context);Pocket.selected=null}
  val parent=Task("card-parent","Review the release","/synthetic","idle",1000,false,preview="Ready for review")
  val child=Task("card-child","Check native controls","/synthetic","idle",1000,false,
   preview="Native controls verified",parentThreadId=parent.id,isChild=true,agentNickname="Control reviewer")
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{
    CompositionLocalProvider(LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
     MaterialTheme{Column{SessionGroupCard(SessionGroup(parent,listOf(child)),1000)}}
    }
   }}
   val closed="1 agents, 0 working, 0 unread; show agents"
   val open="1 agents, 0 working, 0 unread; hide agents"
   compose.onNodeWithContentDescription(closed).assertExists().assertHasClickAction()
   val bounds=compose.onNodeWithContentDescription(closed).fetchSemanticsNode().boundsInRoot
   compose.onNodeWithText("Native controls verified",substring=true).assertDoesNotExist()
   compose.onNodeWithContentDescription(closed).performClick()
   compose.onNodeWithContentDescription(open).assertExists()
   compose.onNodeWithText("Native controls verified",substring=true).assertExists()
   assertEquals(bounds,compose.onNodeWithContentDescription(open).fetchSemanticsNode().boundsInRoot)
   assertNull("Agent disclosure must not navigate to parent",Pocket.selected)
   compose.onNodeWithContentDescription(open).performClick()
   compose.onNodeWithText("Native controls verified",substring=true).assertDoesNotExist()
   compose.onNodeWithContentDescription(closed).assertExists()
   assertNull(Pocket.selected)
  }
 }
 @Test fun workingIdleAndInputBlockedCardsRemainReadableAndCompact() {
  val i=InstrumentationRegistry.getInstrumentation()
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device").commit()
  i.runOnMainSync{Pocket.init(context)}
  val tasks=listOf(
   Task("visual-working","Working session","/synthetic","active",1000,false,preview="Checking the release candidate"),
   Task("visual-idle","Idle session","/synthetic","idle",1000,false,preview="Checks completed and results ready"),
   Task("visual-blocked","Waiting for your answer","/synthetic","idle",1000,false,preview="Which environment should this use?",needsInput=true))
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{
    CompositionLocalProvider(LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
     MaterialTheme{Column(Modifier.statusBarsPadding()){tasks.forEach{TaskCard(it,1000)}}}
    }
   }}
   compose.onNodeWithContentDescription("Working").assertExists()
   compose.onNodeWithContentDescription("Idle").assertExists()
   compose.onNodeWithContentDescription("Needs your input").assertExists()
   tasks.forEach{compose.onNodeWithText(it.title).assertIsDisplayed()}
   compose.waitForIdle()
   assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"session-state-native.png")))
   // Retain this actual card composition across hosts with the same thread ID.
   // Editing host A must not carry an unsent rename into host B.
   val shown=androidx.compose.runtime.mutableStateOf(tasks[1])
   scenario.onActivity{it.setContent{
    CompositionLocalProvider(LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
     MaterialTheme{Column(Modifier.statusBarsPadding()){TaskCard(shown.value,1000)}}
    }
   }}
   compose.onNodeWithText("Idle session").performClick()
   compose.onNode(hasSetTextAction()).performTextReplacement("Unsaved host A name")
   compose.onNodeWithText("Unsaved host A name").assertExists()
   i.runOnMainSync{
    context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit()
     .putString("environment:remote_card_qa:server","http://127.0.0.1:18880")
     .putString("environment:remote_card_qa:token","synthetic-system76")
     .putString("environment:remote_card_qa:deviceId","synthetic-device-b")
     .putString("environments:v1",org.json.JSONArray().put(org.json.JSONObject().put("id","workstation").put("name","Host A")).put(org.json.JSONObject().put("id","remote_card_qa").put("name","Host B")).toString())
     .putString("activeEnvironment","remote_card_qa").commit()
    Pocket.init(context)
    shown.value=tasks[1].copy(title="Host B same-thread session")
   }
   compose.onNodeWithText("Host B same-thread session").assertIsDisplayed()
   compose.onNodeWithText("Unsaved host A name").assertDoesNotExist()
   compose.onNode(hasSetTextAction()).assertDoesNotExist()
   assertEquals("remote_card_qa",Pocket.environmentId)

  }
 }

}
