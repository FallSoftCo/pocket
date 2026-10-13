package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual message renderers on the owned emulator; fixture links never open a browser. */
@RunWith(AndroidJUnit4::class)
class MarkdownTableNativeTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun ordinaryAndImmersionTablesRetainColumnsScrollingLinksAndLargeText(){
        val i=InstrumentationRegistry.getInstrumentation()
        val c=i.targetContext
        val device=UiDevice.getInstance(i)
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
        device.executeShellCommand("pm grant "+c.packageName+" android.permission.POST_NOTIFICATIONS")
        c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
            .putString("server","http://127.0.0.1:9").commit()
        i.runOnMainSync{Pocket.init(c);check(Pocket.token.isBlank()&&Pocket.base=="http://127.0.0.1:9")}
        val raw="| Role | Fit | Evidence |\n| :--- | ---: | :---: |\n| **Engineer** | 92% | [Portfolio](https://example.com/table-proof) |\n| Researcher with a long contextual description | 8% | Details |"
        val immersion=mutableStateOf(false)
        val large=mutableStateOf(false)
        val opened=mutableListOf<String>()
        val handler=object:UriHandler{override fun openUri(uri:String){opened+=uri}}
        val tableMatcher=SemanticsMatcher("three-column table"){
            it.config.getOrNull(SemanticsProperties.CollectionInfo)?.columnCount==3
        }
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->
                activity.setContent{
                    val density=LocalDensity.current
                    CompositionLocalProvider(LocalUriHandler provides handler,
                        LocalDensity provides Density(density.density,if(large.value)2f else 1f),
                        LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
                        MaterialTheme{
                            SelectionContainer{
                                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)){
                                    Box(Modifier.width(240.dp)){
                                        if(immersion.value)BilingualMessage("table-native-fixture",raw)else RichText(raw)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("Role").assertIsDisplayed()
            compose.onNodeWithText("92%").assertIsDisplayed()
            val role=compose.onNodeWithText("Role").fetchSemanticsNode().boundsInRoot
            val engineer=compose.onNodeWithText("Engineer").fetchSemanticsNode().boundsInRoot
            assertEquals("Body column must align with its header",role.left,engineer.left,1f)
            val fitLayouts=mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("92%").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(fitLayouts)}
            assertTrue("Right-aligned numeric cell needs visible leading space",fitLayouts.single().getLineLeft(0)>0f)
            device.takeScreenshot(java.io.File(c.cacheDir,"markdown-table-plain.png"))
            compose.onNode(tableMatcher,useUnmergedTree=true).performTouchInput{swipeLeft()}
            compose.onNodeWithText("Portfolio").assertIsDisplayed().performTouchInput{click(center)}
            compose.runOnIdle{assertEquals(listOf("https://example.com/table-proof"),opened)}
            compose.onNode(tableMatcher,useUnmergedTree=true).performTouchInput{swipeRight()}
            i.runOnMainSync{
                PocketImmersion.setEnabled(true);PocketImmersion.setMotionEnabled(false)
                PocketImmersion.setSupportEnabled(true)
                val start=raw.indexOf("Engineer")
                val translated=raw.replace("Engineer","Ingegnere")
                PocketImmersion.accept(JSONObject().put("translations",JSONArray().put(JSONObject()
                    .put("id","table-native-fixture").put("version",immersionSourceHash(raw))
                    .put("original",raw).put("text",translated).put("density",PocketImmersion.density)
                    .put("planVersion","inline-replacement-v3").put("spans",JSONArray().put(JSONObject()
                        .put("start",start).put("end",start+8).put("source","Engineer").put("target","Ingegnere"))))))
                check(PocketImmersion.presentation("table-native-fixture",raw).text==translated)
                immersion.value=true
            }
            compose.waitForIdle()
            compose.onNodeWithText("Ingegnere",substring=true).assertIsDisplayed()
            val target=compose.onNodeWithText("Ingegnere",substring=true).fetchSemanticsNode().boundsInRoot
            device.takeScreenshot(java.io.File(c.cacheDir,"markdown-table-immersion.png"))
            i.runOnMainSync{PocketImmersion.revealOriginal("table-native-fixture")}
            compose.waitForIdle()
            val support=compose.onNodeWithText("Engineer",substring=true).fetchSemanticsNode().boundsInRoot
            assertEquals("Language handoff must preserve column origin",target.left,support.left,1f)
            assertEquals("Language handoff must preserve column width",target.width,support.width,1f)
            device.takeScreenshot(java.io.File(c.cacheDir,"markdown-table-source.png"))
            i.runOnMainSync{large.value=true}
            compose.waitForIdle()
            compose.onNodeWithText("Role").assertIsDisplayed()
            val largeRole=compose.onNodeWithText("Role").fetchSemanticsNode().boundsInRoot
            assertTrue("Large text must use larger readable row height",largeRole.height>role.height)
            compose.onNode(tableMatcher,useUnmergedTree=true).performTouchInput{swipeLeft()}
            compose.onNodeWithText("Portfolio",substring=true).assertIsDisplayed().performTouchInput{click(center)}
            compose.runOnIdle{assertEquals(2,opened.size);assertTrue(opened.all{it=="https://example.com/table-proof"})}
            device.takeScreenshot(java.io.File(c.cacheDir,"markdown-table-large.png"))
        }
    }
}
