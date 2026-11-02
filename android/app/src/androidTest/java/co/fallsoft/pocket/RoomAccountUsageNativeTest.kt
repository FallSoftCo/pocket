package co.fallsoft.pocket

import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
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

/** Real shared dock/editor/control components on the leased synthetic fixture;
 * no profile writes, recording, volume changes or task submissions. Full room saved-draft/voice
 * routing remains an independent integration gate.
 */
@RunWith(AndroidJUnit4::class)
class RoomAccountUsageNativeTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun visibleAccountFactsDoNotDisplaceControlsOrStealTheDraft(){
        val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i)
        requireOwnedNativeTarget(device)
        check(Pocket.token.isBlank()||Pocket.base.startsWith("http://127.0.0.1:")){
            "Component acceptance requires an unpaired or approved loopback synthetic fixture"
        }
        check(!PocketImmersion.enabled){
            "Use the approved fixture with immersion disabled; this test never changes retained settings or requests translations"
        }
        val width=mutableIntStateOf(320);val font=mutableFloatStateOf(1f)
        val now=System.currentTimeMillis()
        val known=WeeklyUsage(remainingPercent=75.0,resetsAt=now/1000+86400,updatedAt=now,
            credits=CreditUsage(balance="1200",updatedAt=now,stale=false))
        val usage=mutableStateOf(known);val room=mutableStateOf(true);val menu=mutableStateOf(false)
        val draft=mutableStateOf(TextFieldValue("Retained room draft"))
        var available=0;var density=1f;var keyboard:SoftwareKeyboardController?=null
        val records=JSONArray()
        fun imeVisible()=i.uiAutomation.windows.any{it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        fun textVisible(text:String){
            val node=compose.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed()
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertTrue("Missing text layout for $text",layouts.isNotEmpty())
            if(layouts.any{it.hasVisualOverflow}){
                val detail=layouts.joinToString { "size=${it.size} overflowWidth=${it.didOverflowWidth} overflowHeight=${it.didOverflowHeight} input=${it.layoutInput.constraints} style=${it.layoutInput.style}" }
                device.takeScreenshot(i.targetContext.filesDir.resolve("room-usage-intrinsic-overflow.png"))
                println("ACCOUNT_INTRINSIC_OVERFLOW $text width=${width.intValue} font=${font.floatValue} ime=${imeVisible()} $detail")
            }
            // FlowRow intrinsic measurement can report didOverflowWidth when
            // paragraph width exceeds the final text box although every glyph fits.
            // Verify the actual full text and glyph boxes, not that intermediate flag.
            assertTrue("Clipped visible account text: $text",layouts.all{layout->
                layout.lineCount>0 && layout.getLineEnd(layout.lineCount-1,true)==layout.layoutInput.text.length &&
                    (0 until layout.lineCount).none{layout.isLineEllipsized(it)} &&
                    layout.layoutInput.text.indices.all{index->val box=layout.getBoundingBox(index)
                        box.left>=-1f&&box.top>=-1f&&box.right<=layout.size.width+1f&&box.bottom<=layout.size.height+1f}
            })
        }
        ActivityScenario.launch(NativeComponentFixtureActivity::class.java).use{scenario->
            scenario.onActivity{it.setContent{
                val actualDensity=LocalDensity.current;density=actualDensity.density
                keyboard=LocalSoftwareKeyboardController.current
                CompositionLocalProvider(LocalDensity provides Density(density,font.floatValue),
                    LocalImmersionMotionState provides false,LocalImmersionTransitionAnimated provides false){
                    MaterialTheme{Surface(color=Ink){BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()){
                        available=maxWidth.value.toInt();val list=rememberLazyListState(initialFirstVisibleItemIndex=3)
                        Column(Modifier.width(width.intValue.dp).fillMaxHeight().testTag("usage-test-root")){
                            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list){items(30){Text("Synthetic room passage $it",Modifier.padding(12.dp))}}
                            Box(Modifier.padding(horizontal=16.dp,vertical=6.dp)){ConversationComposerRow{
                                ConversationDraftField(draft.value,{draft.value=it},Modifier.weight(1f).testTag("usage-test-editor"),onSend={},placeholder={Text("Draft")})
                                ConversationSendButton(true,true,false,"Send message"){}
                            }}
                            ConversationFooter{
                                InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
                                ConversationActionsMenu(menu.value,{menu.value=it},modifier=Modifier.testTag("usage-test-dock"),
                                    statusIcon="Codex",statusLabel="Idle",contextLabel="Original room",
                                    accountUsage=usage.value,accountSourceLabel=if(room.value)"Account · CPU"else null){
                                    TextButton({}){Text("Original room action")}
                                }
                                TextButton({}){Text("Latest")}
                                ReaderPagingButtons(list)
                            }
                        }
                    }}}
                }
            }}
            compose.waitUntil(5000){available>=320}
            for(w in listOf(320,available).distinct())for(f in listOf(1f,2f))for(ime in listOf(false,true)){
                compose.runOnIdle{width.intValue=w;font.floatValue=f}
                compose.onNodeWithTag("usage-test-editor").performClick()
                compose.runOnIdle{keyboard?.show()};compose.waitUntil(5000){imeVisible()}
                if(!ime)compose.runOnIdle{keyboard?.hide()}
                compose.waitUntil(5000){imeVisible()==ime}
                textVisible("75%");textVisible(requireNotNull(known.credits.compact()))
                compose.onNodeWithText(PocketImmersion.label("Account · CPU"),useUnmergedTree=true).assertIsDisplayed()
                val root=compose.onNodeWithTag("usage-test-root").fetchSemanticsNode().boundsInRoot
                for(selector in listOf(hasTestTag("reader-page-up"),hasTestTag("reader-page-down"),
                    hasContentDescription("Speech volume"),hasContentDescription("Settings, app updates, usage and activity"))){
                    val bounds=compose.onNode(selector).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    assertTrue("Shrunk control at $w/$f/$ime",bounds.width>=48*density-1&&bounds.height>=48*density-1)
                    assertTrue("Clipped control at $w/$f/$ime",bounds.left>=root.left-1&&bounds.right<=root.right+1&&bounds.top>=root.top-1&&bounds.bottom<=root.bottom+1)
                }
                compose.onNodeWithText("Latest").assertIsDisplayed()
                // Real pointer interaction with the original non-focusable menu.
                compose.onNodeWithContentDescription("Settings, app updates, usage and activity").performTouchInput{click()}
                compose.onNodeWithText("Original room action").assertIsDisplayed()
                compose.runOnIdle{menu.value=false}
                compose.onNodeWithTag("usage-test-editor").assertIsFocused()
                assertEquals("Retained room draft",draft.value.text);assertEquals(ime,imeVisible())
                records.put(JSONObject().put("width",w).put("font",f).put("ime",ime))
                assertTrue(device.takeScreenshot(i.targetContext.filesDir.resolve("room-usage-$w-$f-$ime.png")))
            }
            compose.runOnIdle{keyboard?.hide();font.floatValue=1f}
            for((name,value) in listOf("zero" to known.copy(remainingPercent=0.0),"stale" to known.copy(stale=true),
                "loading" to WeeklyUsage(),"unavailable" to WeeklyUsage(state="unavailable"),
                "foreign-source" to accountUsageForToolbar(null,null,null,known))){
                compose.runOnIdle{usage.value=value}
                val facts=accountToolbarFacts(value,System.currentTimeMillis(),Pocket.connected&&Pocket.codexOnline)
                textVisible(facts.primary);facts.credits?.let(::textVisible)
                if(facts.freshness.isNotBlank())compose.onNodeWithText(PocketImmersion.label(facts.freshness),useUnmergedTree=true).assertIsDisplayed()
                if(value.remainingPercent==null)compose.onNodeWithText("75%",useUnmergedTree=true).assertDoesNotExist()
                assertTrue(device.takeScreenshot(i.targetContext.filesDir.resolve("room-usage-$name.png")))
            }
            // Default session component keeps its existing exhausted-credit primary mode.
            compose.runOnIdle{room.value=false;usage.value=known.copy(remainingPercent=0.0)}
            textVisible(requireNotNull(known.credits.compact()))
            val semantics=compose.onNodeWithContentDescription("Settings, app updates, usage and activity").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue(semantics.contains("0% left"));assertTrue(semantics.contains("Resets"))
            assertEquals("Retained room draft",draft.value.text)
        }
        i.targetContext.filesDir.resolve("room-usage-matrix.json").writeText(JSONObject().put("scope","Native components only, not full gateway/send/voice or phone proof").put("scenarios",records).toString(2))
    }
}
