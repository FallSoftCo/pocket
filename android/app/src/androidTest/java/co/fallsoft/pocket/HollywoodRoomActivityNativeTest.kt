package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One UI journey: canonical aggregate -> read failure -> quiet snapshot, with routing intact. */
@RunWith(AndroidJUnit4::class)
class HollywoodRoomActivityNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun aggregateFailureAndQuietSnapshotNeverInventIdleOrLoseRoomTarget(){
  val i=InstrumentationRegistry.getInstrumentation();val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val room=JSONObject().put("id","00000000-0000-4000-8000-000000000111").put("name","Multiagent room")
  val activity=JSONObject().put("version",1).put("observedAt",1770000000000L)
   .put("pending",3).put("claimed",1).put("running",2).put("cancelRequested",1).put("unknown",1).put("waitingForHuman",2)
  val shown=mutableStateOf(JSONObject(room.toString()).put("activity",activity));val available=mutableStateOf(true)
  var opened:String?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{CompositionLocalProvider(LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
    MaterialTheme{Column(Modifier.statusBarsPadding()){HollywoodRoomCard(shown.value,available.value){opened=shown.value.s("id")}}}
   }}}
   compose.onNodeWithTag("native-room-activity:"+room.s("id"),useUnmergedTree=true).assertContentDescriptionEquals("3 Queued; 1 Claimed; 2 Running; 1 Stopping; 1 Unknown; 2 Needs you")
   assertTrue(d.takeScreenshot(i.targetContext.filesDir.resolve("room-activity-mixed.png")))
   for(label in HollywoodRoomActivity.labels)compose.onNodeWithText(PocketImmersion.label(label),useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithText(PocketImmersion.label("Idle"),useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithTag("native-room:"+room.s("id")).performClick();assertEquals(room.s("id"),opened)
   compose.runOnIdle{available.value=false}
   compose.onNodeWithText(PocketImmersion.label("Status unavailable"),useUnmergedTree=true).assertIsDisplayed()
   assertTrue(d.takeScreenshot(i.targetContext.filesDir.resolve("room-activity-unavailable.png")))
   compose.onNodeWithText(PocketImmersion.label("Idle"),useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithTag("native-room:"+room.s("id")).performClick();assertEquals(room.s("id"),opened)
   compose.runOnIdle{shown.value=JSONObject(room.toString())}
   compose.onNodeWithText(PocketImmersion.label("Status unavailable"),useUnmergedTree=true).assertIsDisplayed()
   compose.runOnIdle{
    shown.value=JSONObject(room.toString()).put("activity",JSONObject(activity.toString()).apply{HollywoodRoomActivity.keys.forEach{put(it,0)}})
    available.value=true
   }
   compose.onNodeWithText(PocketImmersion.label("Idle"),useUnmergedTree=true).assertIsDisplayed()
   compose.onNodeWithText(PocketImmersion.label("Running"),useUnmergedTree=true).assertDoesNotExist()
   compose.onNodeWithText(PocketImmersion.label("Status unavailable"),useUnmergedTree=true).assertDoesNotExist()
   assertTrue(d.takeScreenshot(i.targetContext.filesDir.resolve("room-activity-quiet.png")))
  }
 }
}
