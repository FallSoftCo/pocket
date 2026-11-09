package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real MainActivity and HTTP/WebSocket paths; synthetic authority, never a model or phone.
 * Run alone in a fresh instrumentation process against native-parity-controls.mjs.
 * Preferences below only bootstrap disposable pairings and pre-existing drafts.
 * Navigation, form input, confirmations and receipt recovery use actual UI/intent entry points.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NativeParityControlsNativeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val device get()=UiDevice.getInstance(instrumentation)
    private val runId=UUID.randomUUID().toString()
    private val tokens=listOf("synthetic-parity-0-$runId","synthetic-parity-1-$runId")
    private val thread="parity-thread"
    private val prefs get()=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
    private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
    private fun await(condition:()->Boolean){compose.waitUntil(15000,condition);compose.waitForIdle()}
    private fun visible(text:String){
        await{compose.onAllNodesWithText(text,useUnmergedTree=true).fetchSemanticsNodes().any{!it.boundsInRoot.isEmpty}}
        compose.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed()
    }
    private fun tap(text:String,inDialog:Boolean=false){
        val action=hasText(text) and hasClickAction() and isEnabled()
        val matcher=if(inDialog)action and hasAnyAncestor(isDialog()) else action
        await{compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val target=compose.onAllNodes(matcher).onFirst()
        // Only offscreen actions need scrolling. A failed scroll is a test failure,
        // not permission to click a clipped or covered control.
        if(runCatching{target.assertIsDisplayed()}.isFailure)target.performScrollTo()
        target.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun absent(text:String)=compose.onAllNodesWithText(text,useUnmergedTree=true).assertCountEquals(0)
    private fun editor()=compose.onNode(hasSetTextAction())
    private fun editorText()=editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun menu(action:String,captureMenu:Boolean=false){
        compose.onNodeWithContentDescription("Settings, app updates, usage and activity").assertIsDisplayed().performClick()
        // These work controls must be in the initial viewport, before any
        // performScrollTo in tap() could hide an ordering regression.
        for(label in listOf("Context & files","Background terminals","Model, effort, tier & mode","Codex settings & agents"))
            compose.onNode(hasText(label) and hasClickAction()).assertIsDisplayed().assertIsEnabled()
        if(captureMenu)capture("00-menu-first-viewport")
        tap(action)
    }
    private fun contextTools(){
        menu("Context & files")
        visible("Conversation & workspace")
        // Material opens this tall sheet at its partial anchor. Its measured
        // scroll viewport extends below the display until the sheet is expanded;
        // scrolling a descendant alone cannot establish an on-screen action.
        val handle=device.findObject(By.desc("Drag handle"))
        assertNotNull("Context sheet drag handle missing",handle)
        val bounds=handle.visibleBounds
        if(bounds.centerY()>device.displayHeight/4){
            assertTrue("Could not drag the visible context sheet",device.swipe(bounds.centerX(),bounds.centerY(),bounds.centerX(),device.displayHeight/8,40))
            await{device.findObject(By.desc("Drag handle"))?.visibleBounds?.centerY()?.let{it<device.displayHeight/4}==true}
        }
        compose.waitForIdle()
        visible("Conversation & workspace")
    }
    private fun qa(action:String,body:JSONObject?=null,origin:Int=0):JSONObject{
        val connection=URL("http://127.0.0.1:${18880+origin}/_qa/$action").openConnection() as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=5000
        connection.setRequestProperty("Authorization","Bearer ${tokens[origin]}")
        if(body!=null){connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use{it.write(body.toString().toByteArray())}}
        return try{check(connection.responseCode==200){"Fixture control failed: ${connection.responseCode}"};JSONObject(connection.inputStream.bufferedReader().use{it.readText()})}finally{connection.disconnect()}
    }
    private fun calls(path:String,method:String="POST")=qa("state").getJSONArray("calls").objects().filter{it.s("path")==path&&it.s("method")==method}
    private fun capture(stage:String){
        val folder=context.cacheDir.resolve("native-parity-$runId").apply{mkdirs()}
        assertTrue(device.takeScreenshot(folder.resolve("$stage.png")))
        device.dumpWindowHierarchy(folder.resolve("$stage.xml"))
        folder.resolve("calls.json").writeText(qa("state").toString(2))
    }
    private fun navigate(environment:String,id:String=thread){
        // Same entry used by a notification from another environment while a dialog is open.
        main{context.startActivity(Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("environment",environment).putExtra("thread",id))}
        await{Pocket.environmentId==environment&&Pocket.selected==id&&PocketTranscript.currentHistoryVerified}
        compose.onNodeWithTag("conversation-history").assertIsDisplayed()
    }
    private fun nativeAnswers()=qa("state").getJSONArray("nativeAnswers").objects()
    private fun outputNode()=compose.onNodeWithTag("native-command-output",useUnmergedTree=true)
    private fun outputText()=outputNode().fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(""){it.text}
    private fun outputStarts(prefix:String)=compose.onAllNodesWithTag("native-command-output",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()&&outputText().startsWith(prefix)
    private fun outputScroll()=outputNode().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun openOutput(){
        if(compose.onAllNodesWithText("Read command output").fetchSemanticsNodes().isEmpty())tap("Runs")
        tap("Read command output");visible("Command output")
    }
    private fun prepare(){
        check(InstrumentationRegistry.getArguments().getString("nativeParityFixture")=="loopback"){"Explicit disposable fixture opt-in required"}
        requireOwnedNativeTarget(device)
        check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.FINGERPRINT.contains("emu")){"Never run on a physical phone"}
        device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
        prefs.edit().clear()
            .putString("server","http://127.0.0.1:18880").putString("token",tokens[0]).putString("deviceId","parity-device-0")
            .putString("environment:parity_beta:server","http://127.0.0.1:18881").putString("environment:parity_beta:token",tokens[1]).putString("environment:parity_beta:deviceId","parity-device-1")
            .putString("environments:v1",JSONArray().put(JSONObject().put("id","workstation").put("name","Alpha")).put(JSONObject().put("id","parity_beta").put("name","Beta")).toString())
            .putString("draft:$thread","Alpha retained draft").putString("environment:parity_beta:draft:$thread","Beta retained draft").commit()
        main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
        await{compose.onAllNodesWithContentDescription("Open Chat for Alpha parity work").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Open Chat for Alpha parity work").assertIsDisplayed().performClick()
        visible("Alpha response")
        assertTrue("Exercise the current native timeline path",PocketTranscript.canonicalMode)
    }

    private fun workflow(name:String,actions:()->Unit){
        try{
            prepare()
            actions()
        }catch(failure:Throwable){
            // Keep the original setup/assertion failure even if diagnostics fail.
            runCatching{capture("$name-failure")}.exceptionOrNull()?.let{failure.addSuppressed(it)}
            runCatching{
                val folder=context.cacheDir.resolve("native-parity-$runId").apply{mkdirs()}
                folder.resolve("$name-failure-stacks.txt").writeText(Thread.getAllStackTraces().entries
                    .sortedBy{it.key.name}.joinToString("\n\n"){(thread,stack)->
                        "${thread.name} (${thread.state})\n"+stack.joinToString("\n"){"  at $it"}
                    })
            }.exceptionOrNull()?.let{failure.addSuppressed(it)}
            throw failure
        }
    }
    private fun assertOriginalDraftsAndNoReplies(){
        assertEquals("Alpha retained draft",prefs.getString("draft:$thread",null))
        assertEquals("Beta retained draft",prefs.getString("environment:parity_beta:draft:$thread",null))
        assertTrue("Inspection submitted a human reply",calls("/api/threads/$thread/reply").isEmpty())
    }

    @Test fun requestsKeepOriginalIdentityAndReturnToCommandOutput(){
        workflow("requests"){
            // Same request ID is replaced while its secret field is being edited.
            qa("question",JSONObject().put("version",1));visible("Synthetic secret version 1")
            editor().performTextReplacement("synthetic-old-secret")
            assertFalse("Secret leaked into persistent preferences",prefs.all.values.any{it.toString().contains("synthetic-old-secret")})
            qa("question",JSONObject().put("version",2));visible("Synthetic secret version 2")
            assertEquals("Replacement request inherited the old secret","",editorText())
            editor().performTextReplacement("synthetic-new-secret")
            capture("01-replaced-request")
            tap("Send answers")
            await{calls("/api/requests/same-native-request/answer").size==1}
            val answer=calls("/api/requests/same-native-request/answer").single().getJSONObject("input")
            assertEquals("request-token-2",answer.getString("requestToken"))
            assertEquals("synthetic-new-secret",answer.getJSONObject("answers").getString("secret"))
            await{compose.onAllNodesWithText("Synthetic secret version 2",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            assertFalse("Submitted secret persisted",prefs.all.values.any{it.toString().contains("synthetic-new-secret")})
            qa("question",JSONObject().put("version",3));visible("Synthetic native form")
            editor().performTextReplacement("Selected synthetic files")
            tap("Submit form")
            await{calls("/api/requests/same-native-request/answer").size==2}
            val form=calls("/api/requests/same-native-request/answer").last().getJSONObject("input")
            assertEquals("request-token-3",form.s("requestToken"));assertEquals("accept",form.s("actionId"))
            assertEquals("Selected synthetic files",form.getJSONObject("content").s("scope"))
            await{compose.onAllNodesWithText("Synthetic native form",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            // The action comes from an actual composed approval card. Retain its
            // callback to model a UI event queued before navigation/replacement;
            // never manufacture a decision or invoke a delivery helper directly.
            qa("question",JSONObject().put("version",4));visible("Alpha synthetic command approval 4")
            absent("Allow once")
            val approvalControl=compose.onNode(hasText("Allow with command rule") and hasClickAction())
            approvalControl.performScrollTo().assertIsDisplayed()
            val delayedApproval=requireNotNull(approvalControl.fetchSemanticsNode().config[SemanticsActions.OnClick].action)
            await{prefs.all.filterKeys{it.startsWith("decision:")}.values.none{it=="sending"}}
            val decisionsBefore=prefs.all.filterKeys{it.startsWith("decision:")}
            val approvalPostsBefore=calls("/api/requests/same-native-request/answer").size
            qa("question",JSONObject().put("version",4),origin=1)
            navigate("parity_beta");visible("Beta synthetic command approval 4");absent("Alpha synthetic command approval 4")
            val betaProblem=Pocket.error
            main{delayedApproval()};compose.waitForIdle()
            assertEquals("Stale card changed delivery state in another environment",decisionsBefore,prefs.all.filterKeys{it.startsWith("decision:")})
            assertEquals("Stale card raised an error in the new environment",betaProblem,Pocket.error)
            assertEquals(approvalPostsBefore,calls("/api/requests/same-native-request/answer").size)
            tap("Allow with command rule")
            await{nativeAnswers().size==3&&prefs.all.filterKeys{it.startsWith("decision:")}.values.none{it=="sending"}}
            val betaApproval=nativeAnswers().last()
            assertEquals("Beta",betaApproval.s("origin"));assertEquals(thread,betaApproval.s("threadId"));assertEquals("beta-turn",betaApproval.s("turnId"));assertEquals("shared-command",betaApproval.s("itemId"))
            assertEquals("request-token-4",betaApproval.s("requestToken"))
            assertEquals("original",betaApproval.getJSONObject("result").getJSONObject("decision").getJSONObject("acceptWithExecpolicyAmendment").getJSONArray("execpolicy_amendment").getString(1))
            navigate("workstation",id="parity-other")
            val otherReceipts=prefs.all.filterKeys{it.startsWith("decision:")};val otherProblem=Pocket.error
            main{delayedApproval()};compose.waitForIdle()
            assertEquals("Stale card changed delivery state in another thread",otherReceipts,prefs.all.filterKeys{it.startsWith("decision:")})
            assertEquals(otherProblem,Pocket.error);assertEquals(approvalPostsBefore+1,calls("/api/requests/same-native-request/answer").size)
            navigate("workstation");visible("Alpha synthetic command approval 4")
            qa("question",JSONObject().put("version",5));visible("Alpha synthetic command approval 5");absent("Alpha synthetic command approval 4")
            main{delayedApproval()}
            await{calls("/api/requests/same-native-request/answer").size==approvalPostsBefore+2}
            assertEquals("A stale request was approved",3,nativeAnswers().size)
            val rejected=calls("/api/requests/same-native-request/answer").last().getJSONObject("input")
            assertEquals("request-token-4",rejected.s("requestToken"));assertEquals("0",rejected.s("actionId"))
            // The same label now denotes a different offered index and rule.
            tap("Allow with command rule");await{nativeAnswers().size==4}
            val replacementApproval=nativeAnswers().last()
            assertEquals("Alpha",replacementApproval.s("origin"));assertEquals("alpha-turn",replacementApproval.s("turnId"));assertEquals("request-token-5",replacementApproval.s("requestToken"))
            assertEquals("replacement",replacementApproval.getJSONObject("result").getJSONObject("decision").getJSONObject("acceptWithExecpolicyAmendment").getJSONArray("execpolicy_amendment").getString(1))
            assertEquals("1",calls("/api/requests/same-native-request/answer").last().getJSONObject("input").s("actionId"))
            capture("01b-structured-approval-origin-and-replacement")
            // Automatic attention yields back to the conversation on resolution.
            // Deliberate review is separate: it remains open for inspection even
            // without pending questions, until its visible close control is used.
            await{runCatching{compose.onNodeWithContentDescription("Close questions").assertIsNotDisplayed()}.isSuccess}
            compose.onNodeWithContentDescription("Questions and results").assertIsDisplayed().performClick()
            visible("No unanswered questions")
            compose.onNodeWithContentDescription("Close questions").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Close questions").assertIsNotDisplayed()

            // Preserve the resolved-request drawer -> canonical command output
            // transition. Full pagination and failure modes belong to execution.
            qa("command",JSONObject().put("enabled",true))
            await{compose.onAllNodesWithText("Runs").fetchSemanticsNodes().isNotEmpty()}
            openOutput();await{outputStarts("Alpha retained output page one")}
            val outputRead=calls("/api/threads/$thread/command-output","GET").single()
            assertEquals("Alpha",outputRead.s("origin"))
            assertTrue(outputRead.s("query").contains("turnId=alpha-turn")&&outputRead.s("query").contains("itemId=shared-command")&&outputRead.s("query").contains("offset=0"))
            capture("01e-request-to-command-output");tap("Close",inDialog=true)
            await{compose.onAllNodesWithText("Command output",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            assertOriginalDraftsAndNoReplies()
        }
    }

    @Test fun executionInspectionAndTerminationStaySourceBound(){
        workflow("execution"){
            // Expand the actual canonical command row and read real adapter
            // pages. Page replacement must reset scrolling without appending text.
            qa("command",JSONObject().put("enabled",true));qa("command",JSONObject().put("enabled",true),origin=1)
            await{compose.onAllNodesWithText("Runs").fetchSemanticsNodes().isNotEmpty()}
            openOutput();await{compose.onAllNodesWithTag("native-command-output",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            assertTrue(outputText().startsWith("Alpha retained output page one"))
            outputNode().performTouchInput{swipeUp()};await{outputScroll()>0f}
            tap("Next page");await{outputStarts("Alpha retained output page two")}
            assertEquals("A replaced output page retained the previous scroll offset",0f,outputScroll(),0.5f)
            assertFalse(outputText().contains("page one"));assertTrue(outputText().length<=24000)
            val pages=calls("/api/threads/$thread/command-output","GET")
            assertEquals(2,pages.size);assertTrue(pages.all{it.s("origin")=="Alpha"&&it.s("query").contains("turnId=alpha-turn")&&it.s("query").contains("itemId=shared-command")})
            assertTrue(pages[0].s("query").contains("offset=0"));assertTrue(pages[1].s("query").contains("offset=24000")&&pages[1].s("query").contains("revision="))
            tap("Previous page");await{outputStarts("Alpha retained output page one")};assertEquals(0f,outputScroll(),0.5f)
            capture("01c-retained-output-pagination");tap("Close",inDialog=true)
            qa("output-mode",JSONObject().put("mode","hold"));openOutput()
            await{calls("/api/threads/$thread/command-output","GET").size==4}
            navigate("parity_beta");absent("Command output")
            qa("output-release",JSONObject());openOutput();await{outputStarts("Beta retained output page one")}
            assertFalse(outputText().contains("Alpha"));assertEquals("Beta",calls("/api/threads/$thread/command-output","GET").last().s("origin"))
            tap("Close",inDialog=true)
            qa("output-mode",JSONObject().put("mode","unavailable"),origin=1)
            val readsBeforeUnavailable=calls("/api/threads/$thread/command-output","GET").size
            openOutput();visible("Synthetic runtime has no retained-output support.")
            absent("Next page");absent("Previous page");compose.onAllNodesWithTag("native-command-output",useUnmergedTree=true).assertCountEquals(0)
            assertEquals("Unavailable runtime received an output request",readsBeforeUnavailable,calls("/api/threads/$thread/command-output","GET").size)
            assertTrue(calls("/api/native/capabilities","GET").last().let{it.s("origin")=="Beta"&&it.s("query").contains("threadId=$thread")})
            capture("01d-output-source-and-unavailable");tap("Close",inDialog=true)
            qa("command",JSONObject().put("enabled",false));qa("command",JSONObject().put("enabled",false),origin=1)
            qa("output-mode",JSONObject().put("mode","qualified"));qa("output-mode",JSONObject().put("mode","qualified"),origin=1)
            navigate("workstation")

            // Inspection remains useful when mutation is explicitly unavailable,
            // and an absent descriptor must not silently grant termination.
            qa("terminal-mode",JSONObject().put("mode","read-only"))
            menu("Background terminals",captureMenu=true);visible("Alpha synthetic watcher")
            visible("Synthetic runtime permits terminal inspection only.")
            assertTrue("Native terminal read did not negotiate presentation",calls("/api/threads/$thread/terminals","GET").last().s("query").contains("terminalPresentation=1"))
            absent("Terminate");absent("Confirm terminate");absent("Terminate this process?")
            assertTrue("Read-only inspection dispatched termination",calls("/api/threads/$thread/terminals/terminate").isEmpty())
            capture("02a-terminals-read-only")
            qa("terminal-mode",JSONObject().put("mode","missing"));tap("Refresh terminals")
            visible("Read-only inspection. Termination is unavailable on this runtime.")
            visible("Alpha synthetic watcher")
            absent("Terminate");absent("Confirm terminate");absent("Terminate this process?")
            assertTrue("Missing descriptor dispatched termination",calls("/api/threads/$thread/terminals/terminate").isEmpty())
            capture("02b-terminals-missing-descriptor")

            // Restoring the qualified runtime restores the actual action. Its
            // confirmation on A must still disappear on same-ID source B.
            qa("terminal-mode",JSONObject().put("mode","qualified"));tap("Refresh terminals")
            visible("Alpha synthetic watcher");tap("Terminate");visible("Terminate this process?")
            navigate("parity_beta");absent("Terminate this process?");absent("Alpha synthetic watcher")
            assertTrue(calls("/api/threads/$thread/terminals/terminate").isEmpty())
            menu("Background terminals");visible("Beta synthetic watcher");tap("Terminate");tap("Confirm terminate")
            visible("Termination accepted. Refresh to inspect current state.")
            assertEquals("Beta",calls("/api/threads/$thread/terminals/terminate").single().s("origin"))
            capture("02-terminal-origin")

            // Preserve terminal-dialog invalidation when returning to another
            // source's Context sheet, without carrying state into the next case.
            navigate("workstation");absent("Terminate this process?");absent("Beta synthetic watcher")
            compose.onAllNodes(hasText("Background terminals") and hasAnyAncestor(isDialog())).assertCountEquals(0)
            contextTools();visible("Working directory: /synthetic/alpha");absent("Beta original request")
            assertTrue("Inspection submitted a context operation",calls("/api/threads/$thread/conversation-operations").isEmpty())
            capture("02c-terminal-to-context");tap("Close",inDialog=true)
            await{compose.onAllNodesWithText("Conversation & workspace",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            assertOriginalDraftsAndNoReplies()
        }
    }

    @Test fun contextRecoveryRevertAndSettingsPreserveDrafts(){
        workflow("context"){
            // A turn boundary chosen in A cannot become a confirmation for B.
            navigate("workstation");contextTools()
            visible("Conversation & workspace");tap("Branch after selected turn");visible("Confirm conversation action")
            navigate("parity_beta");absent("Confirm conversation action")
            assertTrue(calls("/api/threads/$thread/conversation-operations").isEmpty())
            contextTools();visible("Working directory: /synthetic/beta");absent("Alpha original request")
            capture("03-context-origin")

            // Lost fork acknowledgement is inspected by the original ID, never resubmitted.
            navigate("workstation");contextTools();tap("Branch after selected turn");tap("Confirm")
            await{calls("/api/threads/$thread/conversation-operations").size==1}
            val forkCall=calls("/api/threads/$thread/conversation-operations").single()
            val forkRequest=forkCall.getJSONObject("input")
            assertEquals("Alpha",forkCall.s("origin"));assertEquals("alpha-turn",forkRequest.s("turnId"))
            val originalId=forkRequest.s("id")
            await{prefs.getString("conversationOperation:$thread",null)?.contains(originalId)==true}
            tap("Close",inDialog=true)
            await{compose.onAllNodesWithText("Conversation & workspace",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            assertEquals("Alpha retained draft",prefs.getString("draft:$thread",null))
            qa("resolve-fork",JSONObject())
            contextTools();tap("Check saved operation")
            await{Pocket.selected=="parity-fork"&&PocketTranscript.currentHistoryVerified}
            visible("Alpha branch response")
            assertEquals("Receipt recovery repeated the fork",1,calls("/api/threads/$thread/conversation-operations").size)
            assertTrue("Original receipt was not inspected",calls("/api/threads/$thread/conversation-operations/$originalId","GET").isNotEmpty())
            capture("04-recovered-fork")

            // Revert preserves an edited draft and inserts the original message without sending.
            navigate("workstation");tap("Keyboard");editor().performTextReplacement("Alpha edited draft")
            await{prefs.getString("draft:$thread",null)=="Alpha edited draft"}
            contextTools();tap("Revert before turn & edit");tap("Confirm")
            await{calls("/api/threads/$thread/conversation-operations").size==2}
            tap("Close",inDialog=true)
            await{compose.onAllNodesWithText("Conversation & workspace",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            await{editorText().contains("Alpha original request")}
            assertEquals("Alpha edited draft\n\nAlpha original request",editorText())
            assertEquals("Beta retained draft",prefs.getString("environment:parity_beta:draft:$thread",null))
            assertTrue("Restored draft was submitted",calls("/api/threads/$thread/reply").isEmpty())
            capture("05-restored-draft")

            // Native runtime catalog must be reachable through the ordinary footer menu.
            menu("Codex settings & agents");visible("Codex settings & agents")
            visible("Requested permissions: full")
            tap("Skills");visible("Synthetic skill")
            assertTrue("Settings did not request the selected host",calls("/api/native/configuration","GET").any{it.s("origin")=="Alpha"&&it.s("query").contains("section=skills")})
            capture("06-native-settings")
            // Apps are read-only discovery here; installed, enabled and callable stay distinct.
            tap("Apps");visible("Alpha app 0")
            visible("Installed: Yes · Enabled: Yes · Callable: No")
            tap("Browse apps");visible("Available apps · discovery is not installation")
            tap("App details");compose.onNodeWithText("Synthetic app detail 0").performScrollTo().assertIsDisplayed();compose.onNodeWithText("Authentication: unverified").performScrollTo().assertIsDisplayed()
            tap("Next apps page");visible("Alpha app 30");absent("Alpha app 0")
            tap("Refresh apps");visible("Alpha app 0")
            qa("apps-hold");val appsBefore=calls("/api/native/configuration","GET").size
            tap("Next apps page")
            await{calls("/api/native/configuration","GET").size>appsBefore}
            navigate("parity_beta");qa("apps-release",origin=0)
            absent("Alpha app 30");absent("Codex settings & agents")
            assertTrue("App discovery wrote configuration",calls("/api/native/configuration").isEmpty())
            assertEquals("App discovery changed draft","Alpha edited draft\n\nAlpha original request",prefs.getString("draft:$thread",null))
            navigate("workstation");menu("Codex settings & agents")
            tap("Plugins");visible("Installed plugins · 0–0 of 0")
            tap("Browse catalog");visible("Catalog · 1–30 of 10074")
            tap("Next plugin page");visible("Catalog · 31–60 of 10074")
            compose.onAllNodes(hasText("catalog plugin",substring=true)).assertCountEquals(30)
            absent("Alpha catalog plugin 00000");absent("Beta catalog plugin 00000")
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput("synthetic plugin")
            tap("Search catalog");visible("Catalog · 1–1 of 1")
            tap("Review plugin");visible("Plugin details")
            assertTrue("Catalog page did not retain selected source",calls("/api/native/configuration","GET").any{it.s("origin")=="Alpha"&&it.s("query").contains("cursor=")})
            tap("Review installation");visible("Synthetic plugin capability notice")
            compose.onNode(hasText("Confirm installation") and hasClickAction()).assertIsNotEnabled()
            compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            // The same plugin identity exists on both hosts; this confirmation belongs to Alpha.
            navigate("parity_beta");absent("Plugin details")
            assertTrue("Source switch dispatched a plugin installation",calls("/api/native/configuration").isEmpty())
            menu("Codex settings & agents");tap("Plugins");visible("Installed plugins · 0–0 of 0")
            tap("Browse catalog");visible("Catalog · 1–30 of 10074")
            tap("Next plugin page");visible("Catalog · 31–60 of 10074")
            compose.onAllNodes(hasText("catalog plugin",substring=true)).assertCountEquals(30)
            absent("Alpha catalog plugin 00000");absent("Beta catalog plugin 00000")
            compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput("synthetic plugin")
            tap("Search catalog");visible("Catalog · 1–1 of 1")
            tap("Review plugin");visible("Plugin details")
            tap("Review installation")
            compose.onNode(isToggleable() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
            tap("Confirm installation")
            visible("Installation acknowledged. Some apps need authentication. Host authentication may still be pending. Readiness is unverified.")
            val installation=calls("/api/native/configuration").single()
            assertEquals("Beta",installation.s("origin"));assertEquals(thread,installation.getJSONObject("input").s("threadId"))
            assertTrue(installation.getJSONObject("input").optBoolean("confirmInterstitial"))
            assertTrue(installation.getJSONObject("input").s("attemptId").isNotBlank())
            tap("Reload plugin details")
            compose.onNodeWithText("Apps requiring authentication: Synthetic connector").performScrollTo().assertIsDisplayed()
            capture("06b-plugin-auth-pending")
            tap("Back to plugins");tap("Close",inDialog=true)
            fun authNotice(text:String){
                await{compose.onAllNodesWithText(text,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            }
            // Real new dialog against the production receipt controller and synthetic native auth.
            // A reviewed Alpha source must disappear before any Beta action is admitted.
            navigate("workstation");menu("Codex settings & agents");tap("MCP");tap("Review sign-in")
            visible("MCP sign-in");visible("Start sign-in on original host")
            assertTrue(qa("state").getJSONArray("nativeAuthStarts").objects().isEmpty())
            navigate("parity_beta");absent("MCP sign-in")
            menu("Codex settings & agents");tap("MCP");tap("Review sign-in");tap("Start sign-in on original host")
            authNotice("Sign-in started. Complete authorization on the original host; its callback may be host-local. Reload this receipt afterward.")
            compose.onNodeWithText("https://auth.invalid/Beta/synthetic-sign-in").performScrollTo().assertIsDisplayed()
            absent("Native sign-in completed. Reload MCP status to inspect tool readiness.")
            capture("06c-mcp-sign-in-pending")
            val authStarts=qa("state").getJSONArray("nativeAuthStarts").objects()
            assertEquals(1,authStarts.size);assertEquals("Beta",authStarts.single().s("origin"))
            val loginId=authStarts.single().s("loginId")
            navigate("workstation");absent("MCP sign-in");absent("https://auth.invalid/Beta/synthetic-sign-in")
            // Wrong source completion cannot complete Beta; reopening only reads its saved receipt.
            qa("mcp-completion",JSONObject().put("name","fixture-mcp").put("threadId",thread).put("loginId",loginId).put("success",true))
            navigate("parity_beta");menu("Codex settings & agents");tap("MCP");tap("Review sign-in")
            authNotice("Sign-in started. Complete authorization on the original host; its callback may be host-local. Reload this receipt afterward.")
            qa("mcp-completion",JSONObject().put("name","fixture-mcp").put("threadId",thread).put("loginId",loginId).put("success",true),origin=1)
            tap("Reload sign-in receipt");authNotice("Native sign-in completed. Reload MCP status to inspect tool readiness.")
            absent("https://auth.invalid/Beta/synthetic-sign-in")
            assertEquals("Receipt reload repeated auth",1,qa("state").getJSONArray("nativeAuthStarts").length())
            assertEquals("Beta retained draft",prefs.getString("environment:parity_beta:draft:$thread",null))
            assertTrue("Authentication submitted the draft",calls("/api/threads/$thread/reply").isEmpty())
            capture("06d-mcp-sign-in-completed")
            tap("Close",inDialog=true);tap("Close",inDialog=true)
            fun reloadNotice(text:String){
                await{compose.onAllNodesWithText(text,substring=true,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
                compose.onAllNodesWithText(text,substring=true).onFirst().performScrollTo().assertIsDisplayed()
            }
            // Host-wide reload uses the production durable receipt controller. Merely
            // reviewing Alpha must never submit a reload to either environment.
            navigate("workstation");menu("Codex settings & agents");tap("MCP");tap("Review host MCP reload")
            visible("Host MCP reload");reloadNotice("across this host’s loaded conversations")
            assertEquals(0,qa("state").getJSONArray("nativeReloadStarts").length())
            navigate("parity_beta");absent("Host MCP reload")
            menu("Codex settings & agents");tap("MCP");tap("Review host MCP reload");tap("Reload MCP across this host")
            reloadNotice("Waiting for the original native reload reply")
            tap("Check reload receipts");reloadNotice("Waiting for the original native reload reply")
            val reloadStarts=qa("state").getJSONArray("nativeReloadStarts").objects()
            assertEquals(1,reloadStarts.size);assertEquals("Beta",reloadStarts.single().s("origin"))
            val originalReloadId=reloadStarts.single().s("attemptId")
            capture("06e-mcp-reload-pending")
            navigate("workstation");absent("Host MCP reload")
            qa("mcp-reload-loss",JSONObject(),origin=1)
            navigate("parity_beta");menu("Codex settings & agents");tap("MCP");tap("Review host MCP reload")
            reloadNotice("Reload outcome unconfirmed")
            assertEquals(originalReloadId,qa("state",origin=1).getJSONArray("reloadReceipts").getJSONObject(0).s("id"))
            // A different account cannot bypass the original host-wide unknown effect.
            qa("mcp-account",JSONObject().put("account","secondary"),origin=1)
            await{compose.onAllNodesWithText("Host MCP reload",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            tap("Review host MCP reload");reloadNotice("Return to that original account")
            absent("Reload MCP across this host");absent("I inspected the host; review dismissal")
            assertEquals(1,qa("state").getJSONArray("nativeReloadStarts").length())
            assertEquals("unknown",qa("state",origin=1).getJSONArray("reloadReceipts").getJSONObject(0).s("state"))
            capture("06f-mcp-reload-account-blocked")
            qa("mcp-account",JSONObject().put("account","original"),origin=1)
            await{compose.onAllNodesWithText("Host MCP reload",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
            tap("Review host MCP reload");reloadNotice("Reload outcome unconfirmed")
            tap("I inspected the host; review dismissal");reloadNotice("does not cancel the reload")
            tap("Dismiss inspected reload receipt");reloadNotice("Uncertain reload inspected and dismissed")
            assertEquals(1,qa("state").getJSONArray("nativeReloadStarts").length())
            qa("mcp-reload-mode",JSONObject().put("mode","accepted"),origin=1)
            tap("Check reload receipts");tap("Reload MCP across this host");reloadNotice("Native reload acknowledged")
            val finalStarts=qa("state").getJSONArray("nativeReloadStarts").objects()
            assertEquals(2,finalStarts.size);assertTrue(finalStarts.all{it.s("origin")=="Beta"})
            assertTrue(finalStarts.last().s("attemptId")!=originalReloadId)
            val reloadReceipts=qa("state",origin=1).getJSONArray("reloadReceipts").objects()
            assertEquals("acknowledged",reloadReceipts.single{it.s("id")==originalReloadId}.s("state"))
            assertEquals("accepted",reloadReceipts.single{it.s("id")==finalStarts.last().s("attemptId")}.s("state"))
            assertEquals("Beta retained draft",prefs.getString("environment:parity_beta:draft:$thread",null))
            assertTrue("Reload submitted the draft",calls("/api/threads/$thread/reply").isEmpty())
            capture("06g-mcp-reload-accepted")
            tap("Close",inDialog=true);tap("Close",inDialog=true)
            navigate("workstation")
            assertEquals("Alpha edited draft\n\nAlpha original request",prefs.getString("draft:$thread",null))
        }
    }

}
