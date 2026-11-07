package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    private fun tap(text:String){
        val matcher=hasText(text) and hasClickAction() and isEnabled()
        await{compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val target=compose.onAllNodes(matcher).onFirst()
        // Dialog and footer menu actions can require a genuine scroll into view.
        runCatching{target.performScrollTo()}
        target.assertIsDisplayed().assertIsEnabled().performClick()
    }
    private fun absent(text:String)=compose.onAllNodesWithText(text,useUnmergedTree=true).assertCountEquals(0)
    private fun editor()=compose.onNode(hasSetTextAction())
    private fun editorText()=editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun menu(action:String){
        compose.onNodeWithContentDescription("Settings, app updates, usage and activity").assertIsDisplayed().performClick()
        tap(action)
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

            // A terminal confirmation opened on A must disappear on same-ID source B.
            menu("Background terminals");visible("Alpha synthetic watcher");tap("Terminate");visible("Terminate this process?")
            navigate("parity_beta");absent("Terminate this process?");absent("Alpha synthetic watcher")
            assertTrue(calls("/api/threads/$thread/terminals/terminate").isEmpty())
            menu("Background terminals");visible("Beta synthetic watcher");tap("Terminate");tap("Confirm terminate")
            visible("Termination accepted. Refresh to inspect current state.")
            assertEquals("Beta",calls("/api/threads/$thread/terminals/terminate").single().s("origin"))
            capture("02-terminal-origin")

            // A turn boundary chosen in A cannot become a confirmation for B.
            navigate("workstation");menu("Context & files")
            visible("Conversation & workspace");tap("Branch after selected turn");visible("Confirm conversation action")
            navigate("parity_beta");absent("Confirm conversation action")
            assertTrue(calls("/api/threads/$thread/conversation-operations").isEmpty())
            menu("Context & files");visible("Working directory: /synthetic/beta");absent("Alpha original request")
            capture("03-context-origin")

            // Lost fork acknowledgement is inspected by the original ID, never resubmitted.
            navigate("workstation");menu("Context & files");tap("Branch after selected turn");tap("Confirm")
            await{calls("/api/threads/$thread/conversation-operations").size==1}
            val forkCall=calls("/api/threads/$thread/conversation-operations").single()
            val forkRequest=forkCall.getJSONObject("input")
            assertEquals("Alpha",forkCall.s("origin"));assertEquals("alpha-turn",forkRequest.s("turnId"))
            val originalId=forkRequest.s("id")
            await{prefs.getString("conversationOperation:$thread",null)?.contains(originalId)==true}
            tap("Close")
            assertEquals("Alpha retained draft",prefs.getString("draft:$thread",null))
            qa("resolve-fork",JSONObject())
            menu("Context & files");tap("Check saved operation")
            await{Pocket.selected=="parity-fork"&&PocketTranscript.currentHistoryVerified}
            visible("Alpha branch response")
            assertEquals("Receipt recovery repeated the fork",1,calls("/api/threads/$thread/conversation-operations").size)
            assertTrue("Original receipt was not inspected",calls("/api/threads/$thread/conversation-operations/$originalId","GET").isNotEmpty())
            capture("04-recovered-fork")

            // Revert preserves an edited draft and inserts the original message without sending.
            navigate("workstation");tap("Keyboard");editor().performTextReplacement("Alpha edited draft")
            await{prefs.getString("draft:$thread",null)=="Alpha edited draft"}
            menu("Context & files");tap("Revert before turn & edit");tap("Confirm")
            await{calls("/api/threads/$thread/conversation-operations").size==2}
            tap("Close")
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
            tap("Close")
            assertEquals("Alpha edited draft\n\nAlpha original request",prefs.getString("draft:$thread",null))
        }catch(failure:Throwable){runCatching{capture("failure")};throw failure}
    }
}
