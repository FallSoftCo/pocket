package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
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
 // Async drawer effects must advance on the controlled test clock.
 @get:Rule val compose=createEmptyComposeRule(effectContext=StandardTestDispatcher())
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
  await{compose.onAllNodesWithText("Use a banked reset",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Use a banked reset",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNodeWithText("Cancel",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Use a banked reset",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Use reset now",useUnmergedTree=true).performClick()
  await{compose.onAllNodesWithText("Check reset result",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Check reset result",useUnmergedTree=true).performClick()
  await{compose.onAllNodesWithText("Reset applied. Refreshing account usage.",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  val resetState=(java.net.URL("http://127.0.0.1:18880/_qa/state").openConnection() as java.net.HttpURLConnection).run{
   setRequestProperty("Authorization","Bearer synthetic-system76");inputStream.bufferedReader().use{org.json.JSONObject(it.readText())}
  }.getJSONArray("resetRequests")
  assertEquals(2,resetState.length())
  assertEquals(resetState.getJSONObject(0).getString("id"),resetState.getJSONObject(1).getString("id"))
  assertFalse(prefs.contains("manualResetRequest"))
  assertTrue("Settings must retain the Activity window for voice keys",AppWindowVisibility.focused)
  // The primary update action is reachable without expanding a section.
  compose.onNodeWithText("Check for updates",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Usage details",useUnmergedTree=true).performScrollTo().performClick()
  compose.onAllNodesWithText("1 banked reset available",useUnmergedTree=true).assertCountEquals(1)
  // Inspecting the drawer never downloads/installs an update or submits work.
  device.pressBack()
  await{compose.onAllNodesWithTag("settings-drawer-content").fetchSemanticsNodes().isEmpty()}
  main{Pocket.open("same-thread")}
  await{Pocket.selected=="same-thread"&&PocketTranscript.rows.isNotEmpty()}
  val original=Pocket.captureEnvironment()
  open()
  compose.onNodeWithText("More actions",useUnmergedTree=true).assertDoesNotExist()
  compose.onNodeWithText("Queue message",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
  compose.onNodeWithText("Rename",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNode(hasSetTextAction()).assertExists()
  device.pressBack()
  // Dismiss any IME without modifying the name/draft, then close the dialog.
  main{Pocket.closeTask()}
  assertEquals("Drawer preserves my unsent work",prefs.getString("draft:same-thread",null))
  assertTrue(requireNotNull(Pocket.captureEnvironment()).matches(requireNotNull(original)))
  main{Pocket.composeTask()}
  await{compose.onAllNodesWithText("Provider: OpenAI",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Manage providers",useUnmergedTree=true).performScrollTo().performClick()
  await{compose.onAllNodesWithText("API key",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasSetTextAction().and(hasText("API key"))).performScrollTo().performTextInput("synthetic-native-provider-key")
  compose.onNodeWithText("Save provider",useUnmergedTree=true).performScrollTo().performClick()
  await{compose.onAllNodesWithText("Provider saved.",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Done",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Provider: OpenAI",useUnmergedTree=true).performScrollTo().performClick()
  compose.onNodeWithText("DeepSeek",useUnmergedTree=true).performClick()
  compose.onNode(hasSetTextAction().and(hasText("Provider model ID"))).assertTextContains("deepseek-flash")
  compose.onNode(hasSetTextAction().and(hasText("Project folder"))).performScrollTo().performTextReplacement("/synthetic")
  compose.onNode(hasSetTextAction().and(hasText("What would you like Codex to do?"))).performScrollTo().performTextInput("Synthetic provider task")
  compose.onNodeWithText("Start task",useUnmergedTree=true).performClick()
  await{Pocket.selected=="provider-thread"}
  val providerState=(java.net.URL("http://127.0.0.1:18880/_qa/state").openConnection() as java.net.HttpURLConnection).run{
   setRequestProperty("Authorization","Bearer synthetic-system76");inputStream.bufferedReader().use{org.json.JSONObject(it.readText())}
  }
  assertEquals(1,providerState.getJSONArray("providerWrites").length())
  val tasks=providerState.getJSONArray("taskRequests");assertEquals(1,tasks.length())
  assertEquals("System76",tasks.getJSONObject(0).getString("host"))
  assertEquals("deepseek",tasks.getJSONObject(0).getJSONObject("selection").getString("providerId"))
  assertEquals("deepseek-flash",tasks.getJSONObject(0).getJSONObject("selection").getString("model"))
  // Immersion chooses a provider independently without enabling or sending work.
  main{Pocket.closeTask()}
  open()
  compose.onNode(hasText("Settings").and(hasClickAction()),useUnmergedTree=true).performScrollTo().performClick()
  compose.onNodeWithText("Immersion provider & model",useUnmergedTree=true).performScrollTo().performClick()
  await{compose.onAllNodesWithText("Provider: OpenAI",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Provider: OpenAI",useUnmergedTree=true).performClick()
  compose.onNodeWithText("DeepSeek",useUnmergedTree=true).performClick()
  compose.onNodeWithText("Save immersion model",useUnmergedTree=true).performScrollTo().performClick()
  await{compose.onAllNodesWithText("Save immersion model",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
  assertFalse(PocketImmersion.enabled)
  compose.onNodeWithText("Immersion provider & model",useUnmergedTree=true).performScrollTo().performClick()
  await{compose.onAllNodesWithText("Provider: DeepSeek",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
  compose.onNode(hasSetTextAction().and(hasText("Provider model ID"))).assertTextContains("deepseek-flash")
  val immersionState=(java.net.URL("http://127.0.0.1:18880/_qa/state").openConnection() as java.net.HttpURLConnection).run{
   setRequestProperty("Authorization","Bearer synthetic-system76");inputStream.bufferedReader().use{org.json.JSONObject(it.readText())}
  }
  val immersionWrites=immersionState.getJSONArray("immersionWrites").objects().filter{it.has("selection")}
  assertEquals(1,immersionWrites.size);assertFalse(immersionWrites[0].getBoolean("enabled"))
  assertEquals("deepseek",immersionWrites[0].getJSONObject("selection").getString("providerId"))
  assertEquals(1,immersionState.getJSONArray("taskRequests").length())
  assertFalse(prefs.all.toString().contains("synthetic-native-provider-key"))
  assertEquals("Drawer preserves my unsent work",prefs.getString("draft:same-thread",null))
 }
}
