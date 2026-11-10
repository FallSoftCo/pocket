package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsDrawerNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun drawerPreservesWindowDraftAndReachableControls(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  requireOwnedNativeTarget(device)
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(condition:()->Boolean){compose.waitUntil(15000,condition);compose.waitForIdle()}
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device-a").putString("draft:same-thread","Drawer preserves my unsent work").commit()
  main{Pocket.init(c);PocketImmersion.setEnabled(false);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
  await{Pocket.tasks.isNotEmpty()}
  fun open(){compose.onNodeWithContentDescription("Settings, app updates and usage").performClick();compose.onNodeWithTag("settings-drawer-content").assertIsDisplayed()}
  open()
  compose.onNodeWithText("2 banked resets available",useUnmergedTree=true).assertIsDisplayed()
  assertTrue("Settings must retain the Activity window for voice keys",AppWindowVisibility.focused)
  compose.onNodeWithText("App updates",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNodeWithText("Check for updates",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
  // Inspecting the drawer never downloads/installs an update or submits work.
  device.pressBack()
  await{compose.onAllNodesWithTag("settings-drawer-content").fetchSemanticsNodes().isEmpty()}
  main{Pocket.open("same-thread")}
  await{Pocket.selected=="same-thread"&&PocketTranscript.rows.isNotEmpty()}
  val original=Pocket.captureEnvironment()
  open()
  compose.onNodeWithText("Rename",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNode(hasSetTextAction()).assertExists()
  device.pressBack()
  // Dismiss any IME without modifying the name/draft, then close the dialog.
  main{Pocket.closeTask()}
  assertEquals("Drawer preserves my unsent work",prefs.getString("draft:same-thread",null))
  assertTrue(requireNotNull(Pocket.captureEnvironment()).matches(requireNotNull(original)))
 }
}
