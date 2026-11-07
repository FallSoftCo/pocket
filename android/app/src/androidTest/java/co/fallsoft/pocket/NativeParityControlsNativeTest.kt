package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
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
    private fun qa(action:String,body:JSONObject?=null):JSONObject{
        val connection=URL("http://127.0.0.1:18880/_qa/$action").openConnection() as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=5000
        connection.setRequestProperty("Authorization","Bearer ${tokens[0]}")
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

    @Test fun nativeControlsPreserveRequestSourceReceiptsAndDrafts(){
        prepare()
        try{
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
            // Resolving the request keeps the review drawer open for inspection.
            // Close its actual visible control before interacting with the footer.
            visible("No unanswered questions")
            compose.onNodeWithContentDescription("Close questions").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Close questions").assertIsNotDisplayed()

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
            tap("Close",inDialog=true)
            assertEquals("Alpha edited draft\n\nAlpha original request",prefs.getString("draft:$thread",null))
        }catch(failure:Throwable){runCatching{capture("failure")};throw failure}
    }
}
