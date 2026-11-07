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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
        val usage=mutableStateOf(WeeklyUsage())
        val editor=mutableStateOf(TextFieldValue("Unsent footer regression draft"))
        var availableWidth=0
        var pixelsPerDp=1f
        var keyboard:SoftwareKeyboardController?=null
        var activity:NativeComponentFixtureActivity?=null
        var stage="launch"
        var caseLabel="initial"
        var windowReadyOnEntry:Boolean?=null
        lateinit var list:LazyListState
        fun imeVisible()=i.uiAutomation.windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        fun bounds(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val records=JSONArray()
        val imeEvents=JSONArray()
        fun windowReady()=compose.runOnUiThread{
            activity?.let{it.lifecycle.currentState==androidx.lifecycle.Lifecycle.State.RESUMED&&
                it.window.decorView.isAttachedToWindow&&it.hasWindowFocus()}==true
        }
        fun imeEvidence():JSONObject{
            val evidence=compose.runOnUiThread{
                JSONObject().put("stage",stage).put("case",caseLabel).put("at",System.currentTimeMillis())
                    .put("windowReadyOnEntry",windowReadyOnEntry)
                    .put("lifecycle",activity?.lifecycle?.currentState?.name)
                    .put("windowFocus",activity?.hasWindowFocus()).put("attached",activity?.window?.decorView?.isAttachedToWindow)
                    .put("focusedView",activity?.currentFocus?.javaClass?.name).put("keyboardController",keyboard!=null)
            }
            evidence.put("editorFocused",runCatching{compose.onNodeWithTag("footer-test-editor").fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Focused)}.getOrNull())
            evidence.put("windows",JSONArray().apply{i.uiAutomation.windows.forEach{window->
                val rect=android.graphics.Rect();window.getBoundsInScreen(rect)
                put(JSONObject().put("type",window.type).put("active",window.isActive).put("focused",window.isFocused).put("bounds",rect.toShortString()))
            }})
            return evidence
        }
        fun recordIme(){
            val evidence=imeEvidence();imeEvents.put(evidence)
            i.targetContext.filesDir.resolve("footer-ime-events.json").writeText(imeEvents.toString(2))
            android.util.Log.i("FooterImeQA",evidence.toString())
        }
        ActivityScenario.launch(NativeComponentFixtureActivity::class.java).use{scenario->
            try{
            scenario.onActivity{fixture->activity=fixture;fixture.setContent{
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
                                    ConversationActionsMenu(false,{},statusIcon="Codex",statusLabel="Idle",contextLabel="QA",accountUsage=usage.value){}
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
                    caseLabel="width=$w font=$f ime=$ime"
                    // A measured Compose tree does not establish Android window
                    // focus on a cold ActivityScenario launch. Do not issue a
                    // best-effort IME request before its actual prerequisites.
                    stage="await-resumed-window"
                    windowReadyOnEntry=windowReady()
                    compose.waitUntil(5000){windowReady()}
                    compose.onNodeWithTag("footer-test-editor").assertIsDisplayed().assertIsEnabled().performClick()
                    stage="await-editor-focus"
                    compose.waitUntil(5000){windowReady()&&compose.onAllNodes(hasTestTag("footer-test-editor") and isFocused()).fetchSemanticsNodes().isNotEmpty()}
                    stage="before-keyboard-show";recordIme()
                    compose.runOnIdle{checkNotNull(keyboard){"Software keyboard controller missing"}.show()}
                    // Observe the real show before hide; do not pass a hidden-IME
                    // case merely because an asynchronous show has not arrived yet.
                    stage="await-visible-ime"
                    compose.waitUntil(5000){imeVisible()}
                    stage="ime-visible";recordIme()
                    if(!ime)compose.runOnIdle{keyboard?.hide()}
                    stage="await-requested-ime-state"
                    compose.waitUntil(5000){imeVisible()==ime}
                    compose.waitForIdle()
                    stage="layout-and-paging"
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
                    val footerTargets=listOf("Speech volume","Questions and results","Settings, app updates, usage and activity").map{description->
                        val node=compose.onNodeWithContentDescription(description).assertIsDisplayed()
                        val target=node.fetchSemanticsNode().boundsInRoot
                        assertTrue("$label shrank $description",target.width>=48*pixelsPerDp-1&&target.height>=48*pixelsPerDp-1)
                        assertTrue("$label clipped $description",contained(container,target)&&contained(root,target))
                        target
                    }
                    if(w>=400&&f==1f){
                        // With no numeric usage and normal text, this portrait
                        // width fits the real controls without a paging-only row.
                        for(target in footerTargets)assertTrue("$label unnecessarily wrapped paging",up.top<target.bottom&&up.bottom>target.top)
                    }
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
            stage="hide-ime-for-usage"
            compose.runOnIdle{keyboard?.hide()}
            compose.waitUntil(5000){!imeVisible()}
            // Real numeric modes still reserve their rendered bold value and
            // reset line. Large text may wrap the footer, never truncate usage.
            for(value in listOf(
                WeeklyUsage(remainingPercent=0.05,resetsAt=1794017100),
                WeeklyUsage(remainingPercent=0.0,credits=CreditUsage(balance="99900"))
            ))for(f in listOf(1f,2f)){
                stage="numeric-usage-layout";caseLabel="usage=${value.remainingPercent} font=$f"
                compose.runOnIdle{width.intValue=availableWidth;font.floatValue=f;usage.value=value}
                compose.waitForIdle()
                val primary=if(value.remainingPercent==0.0)value.credits.compact()!! else value.remainingLabel().replace(" left","")
                val text=compose.onNodeWithText(primary,useUnmergedTree=true).assertIsDisplayed()
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                text.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                assertTrue("Usage layout missing for $primary font=$f",layouts.isNotEmpty())
                // FlowRow's intrinsic pass can report overflow even when the
                // final glyph boxes fit; inspect the rendered text itself.
                assertTrue("Usage clipped for $primary font=$f",layouts.all{layout->
                    layout.lineCount>0&&layout.getLineEnd(layout.lineCount-1,true)==layout.layoutInput.text.length&&
                        (0 until layout.lineCount).none{layout.isLineEllipsized(it)}&&
                        layout.layoutInput.text.indices.all{index->val box=layout.getBoundingBox(index)
                            box.left>=-1f&&box.top>=-1f&&box.right<=layout.size.width+1f&&box.bottom<=layout.size.height+1f}
                })
                compose.onNodeWithContentDescription("Settings, app updates, usage and activity").assertIsDisplayed()
                assertEquals("Numeric usage orphaned Page down",bounds("reader-page-up").top,bounds("reader-page-down").top,1f)
                records.put(JSONObject().put("widthDp",availableWidth).put("fontScale",f).put("ime",false)
                    .put("usage",primary).put("pageUp",bounds("reader-page-up").toString()).put("pageDown",bounds("reader-page-down").toString()))
                assertTrue(device.takeScreenshot(i.targetContext.filesDir.resolve("footer-usage-${if(value.remainingPercent==0.0)"credits" else "allowance"}-$f.png")))
            }
            }catch(failure:Throwable){
                // Retain the live failure before ActivityScenario closes the
                // window and makes IME/focus evidence describe cleanup instead.
                runCatching{recordIme()}.onFailure{failure.addSuppressed(it)}
                runCatching{device.takeScreenshot(i.targetContext.filesDir.resolve("footer-ime-failure.png"))}.onFailure{failure.addSuppressed(it)}
                runCatching{device.dumpWindowHierarchy(i.targetContext.filesDir.resolve("footer-ime-failure.xml"))}.onFailure{failure.addSuppressed(it)}
                runCatching{i.targetContext.filesDir.resolve("footer-ime-failure-input-method.txt").writeText(device.executeShellCommand("dumpsys input_method"))}.onFailure{failure.addSuppressed(it)}
                throw failure
            }
        }
        i.targetContext.filesDir.resolve("footer-paging-matrix.json").writeText(JSONObject()
            .put("scope","Native component fixture, not full conversation send or physical phone proof")
            .put("scenarios",records).toString(2))
    }
}
