package co.fallsoft.pocket

import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

/** Component-only native proof; no profiles, gateway, send, audio or volume mutations.
 * The real stock footer controls and editor are mounted, not substitute fixed boxes.
 * Full room accepted-send/next-draft proof remains HollywoodMixedCatalogNativeTest.
 */
@RunWith(AndroidJUnit4::class)
class ReaderPagingFooterNativeTest {
    @get:Rule val compose=createEmptyComposeRule()

    @Test fun bothPagesStayTogetherAndUsableWithNarrowLargeFontAndIme(){
        val i=InstrumentationRegistry.getInstrumentation()
        val device=UiDevice.getInstance(i)
        requireOwnedNativeTarget(device)
        val width=mutableIntStateOf(320)
        val font=mutableFloatStateOf(1f)
        val editor=mutableStateOf(TextFieldValue("Unsent footer regression draft"))
        var availableWidth=0
        var pixelsPerDp=1f
        var keyboard:SoftwareKeyboardController?=null
        lateinit var list:LazyListState
        fun imeVisible()=i.uiAutomation.windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        fun bounds(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val records=JSONArray()
        ActivityScenario.launch(NativeComponentFixtureActivity::class.java).use{scenario->
            scenario.onActivity{activity->activity.setContent{
                val density=LocalDensity.current
                pixelsPerDp=density.density
                keyboard=LocalSoftwareKeyboardController.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,font.floatValue),
                    LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
                    MaterialTheme{Surface(color=Ink){
                        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
                            availableWidth=maxWidth.value.toInt()
                            list=rememberLazyListState(initialFirstVisibleItemIndex=8)
                            Column(Modifier.width(width.intValue.dp).fillMaxHeight().testTag("footer-test-bounds")){
                                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("footer-test-history"),state=list){
                                    items(80){Text("Synthetic passage $it · A complete readable line for paging.",Modifier.padding(12.dp))}
                                }
                                Box(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)){
                                    ConversationComposerRow{
                                        ConversationDraftField(editor.value,{editor.value=it},
                                            Modifier.weight(1f).testTag("footer-test-editor"),onSend={},placeholder={Text("Draft")})
                                        ConversationSendButton(true,true,false,"Send message"){}
                                    }
                                }
                                ConversationFooter{
                                    InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
                                    ConversationNotesControls(2,1,{},true,{"Synthetic passage"})
                                    ConversationActionsMenu(false,{},statusIcon="Codex",statusLabel="Idle"){}
                                    ReaderPagingButtons(list)
                                }
                            }
                        }
                    }}
                }
            }}
            compose.waitUntil(5000){availableWidth>=320}
            // Include the actual retained-emulator width, without changing global wm/font settings.
            for(w in listOf(320,360,availableWidth).filter{it<=availableWidth}.distinct())for(f in listOf(1f,2f)){
                compose.runOnIdle{width.intValue=w;font.floatValue=f}
                for(ime in listOf(false,true)){
                    compose.onNodeWithTag("footer-test-editor").performClick()
                    compose.runOnIdle{keyboard?.show()}
                    // Observe the real show before hide; do not pass a hidden-IME
                    // case merely because an asynchronous show has not arrived yet.
                    compose.waitUntil(5000){imeVisible()}
                    if(!ime)compose.runOnIdle{keyboard?.hide()}
                    compose.waitUntil(5000){imeVisible()==ime}
                    compose.waitForIdle()
                    val label="width=$w font=$f ime=$ime"
                    val container=bounds("bottom-control-bar")
                    val up=bounds("reader-page-up")
                    val down=bounds("reader-page-down")
                    val root=bounds("footer-test-bounds")
                    fun contained(outer:Rect,inner:Rect)=inner.left>=outer.left-1&&inner.right<=outer.right+1&&
                        inner.top>=outer.top-1&&inner.bottom<=outer.bottom+1
                    for(tag in listOf("reader-page-up","reader-page-down")){
                        compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()
                        val target=bounds(tag)
                        assertTrue("$label shrank $tag",target.width>=48*pixelsPerDp-1&&target.height>=48*pixelsPerDp-1)
                        assertTrue("$label clipped $tag",contained(container,target)&&contained(root,target))
                    }
                    assertEquals("$label orphaned Page down",up.top,down.top,1f)
                    assertEquals("$label changed page spacing",2*pixelsPerDp,down.left-up.right,1f)
                    for(description in listOf("Speech volume","Questions and results","Settings, app updates, usage and activity"))
                        compose.onNodeWithContentDescription(description).assertIsDisplayed()
                    compose.onNodeWithContentDescription(PocketImmersion.label("Speak visible conversation")).assertIsDisplayed()
                    val before=compose.runOnIdle{list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset}
                    compose.onNodeWithTag("reader-page-down").performTouchInput{click()}
                    compose.waitUntil(5000){list.firstVisibleItemIndex>before.first||
                        list.firstVisibleItemIndex==before.first&&list.firstVisibleItemScrollOffset>before.second}
                    compose.onNodeWithTag("reader-page-up").performTouchInput{click()}
                    compose.waitUntil(5000){list.firstVisibleItemIndex==before.first&&
                        kotlin.math.abs(list.firstVisibleItemScrollOffset-before.second)<=2}
                    assertEquals("$label changed draft","Unsent footer regression draft",editor.value.text)
                    compose.onNodeWithTag("footer-test-editor").assertIsFocused()
                    assertEquals("$label paging changed IME visibility",ime,imeVisible())
                    records.put(JSONObject().put("widthDp",w).put("fontScale",f).put("ime",ime)
                        .put("pageUp",up.toString()).put("pageDown",down.toString()))
                    assertTrue(device.takeScreenshot(i.targetContext.filesDir.resolve("footer-paging-$w-$f-$ime.png")))
                }
            }
            compose.runOnIdle{keyboard?.hide()}
        }
        i.targetContext.filesDir.resolve("footer-paging-matrix.json").writeText(JSONObject()
            .put("scope","Native component fixture, not full conversation send or physical phone proof")
            .put("scenarios",records).toString(2))
    }
}
