package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real app/backend journey; only the isolated provider RPC boundary is substituted. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RealBackendJourneyNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device get()=UiDevice.getInstance(i)
 private val c get()=i.targetContext
 private fun main(f:()->Unit)=i.runOnMainSync(f)
 private fun await(f:()->Boolean){compose.waitUntil(15000){f()};compose.waitForIdle()}
 private fun visibleText(text:String){
  compose.waitUntil(15000){compose.onAllNodesWithText(text,useUnmergedTree=true).fetchSemanticsNodes().any{!it.boundsInRoot.isEmpty}}
  compose.onNodeWithText(text,useUnmergedTree=true).assertIsDisplayed()
 }
 private fun editor()=compose.onNode(hasSetTextAction())
 private fun editorText()=editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
 @Test fun sendEchoAndColdRestore(){
  val args=InstrumentationRegistry.getArguments()
  org.junit.Assume.assumeTrue("Requires disposable backend harness",args.getString("realBackendPhase")!=null)
  check(args.getString("qaOwner")=="01a0f2bc-ff5a-70c2-b682-10f0987dab80")
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val phase=requireNotNull(args.getString("realBackendPhase"));check(phase in setOf("send","restore"))
  val history=args.getString("historyJourney")=="1"
  val token=requireNotNull(args.getString("pairedToken"));val deviceId=requireNotNull(args.getString("pairedDevice"))
  val tid="11111111-1111-4111-8111-111111111111"
  val input="Integration input";val answer="Synthetic answer to $input";val draft="Retained integration draft"
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  if(phase=="send")prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token",token).putString("deviceId",deviceId).putBoolean("diagnostic:scroll",history).commit()
  else {assertEquals(token,prefs.getString("token",null));assertEquals(draft,prefs.getString("draft:$tid",null))}
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.any{it.id==tid}}
  fun openCard(){
   val description="Open Chat for Synthetic integration work"
   compose.waitUntil(15000){compose.onAllNodesWithContentDescription(description,useUnmergedTree=true).fetchSemanticsNodes().any{!it.boundsInRoot.isEmpty}}
   val target=compose.onNodeWithContentDescription(description,useUnmergedTree=true)
   java.io.File(c.cacheDir,"real-catalog-observation.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
   target.assertIsDisplayed().performClick();await{Pocket.selected==tid&&!PocketTranscript.updating}
  }
  openCard()
  if(phase=="send"){
   compose.onNodeWithText("Keyboard").performClick();editor().assertIsDisplayed().performClick().performTextReplacement(input)
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
   await{!Pocket.sending&&PocketTranscript.rows.any{it.s("text")==answer}}
   device.pressBack();await{Pocket.selected==null};openCard()
  }
  await{PocketTranscript.rows.any{it.s("kind")=="user"&&it.s("text")==input}&&PocketTranscript.rows.any{it.s("text")==answer}}
  val rows=PocketTranscript.rows
  assertEquals("User row duplicated",1,rows.count{it.s("kind")=="user"&&it.s("text")==input})
  assertTrue("Answer before input",rows.indexOfFirst{it.s("kind")=="user"&&it.s("text")==input}<rows.indexOfFirst{it.s("text")==answer})
  if(!history||phase=="send"){visibleText(input);assertEquals(1,compose.onAllNodesWithText(input,useUnmergedTree=true).fetchSemanticsNodes().size)}
  if(history&&phase=="restore"){
   val anchor=requireNotNull(prefs.getString("qa-history-anchor",null))
   visibleText(anchor)
   val bounds=compose.onNodeWithText(anchor,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
   assertEquals("Cold reading passage moved",prefs.getInt("qa-history-top",-1).toFloat(),bounds.top,24f)
   assertTrue("Loaded local history hidden",PocketTranscript.rows.map{it.s("id")}.containsAll(prefs.getStringSet("qa-history-ids",emptySet()).orEmpty()))
  }
  compose.onNodeWithText("Keyboard").performClick();editor().assertIsDisplayed()
  if(phase=="send"){editor().performTextReplacement(draft);await{prefs.getString("draft:$tid",null)==draft}}
  else assertEquals("Cold editor lost draft",draft,editorText())
  device.pressBack();await{Pocket.selected==null}
  if(history&&phase=="send"){
   openCard();compose.onNodeWithTag("conversation-history").assertIsDisplayed()
   var gestures=0
   while(gestures<12&&PocketTranscript.rows.count{it.s("text").startsWith("History passage ")}<3){
    val cursor=PocketTranscript.before
    compose.onNodeWithTag("conversation-history").performTouchInput{swipeDown()};gestures++
    compose.waitForIdle()
    val atEdge=compose.onNodeWithTag("conversation-history").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()<=0.001f
    if(atEdge&&PocketTranscript.earlier)await{PocketTranscript.before!=cursor&&!PocketTranscript.loading&&!PocketTranscript.updating}
    await{PocketTranscript.readPosition?.follow==false&&!PocketTranscript.loading&&!PocketTranscript.updating}
   }
   java.io.File(c.cacheDir,"real-history-observation.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
   assertTrue("Thumb gestures did not load retained history",PocketTranscript.rows.count{it.s("text").startsWith("History passage ")}>=3)
   val viewport=compose.onNodeWithTag("conversation-history").fetchSemanticsNode().boundsInRoot
   val passage=compose.onAllNodes(hasText("History passage ",substring=true),useUnmergedTree=true).fetchSemanticsNodes().firstOrNull{val b=it.boundsInRoot;!b.isEmpty&&b.top>=viewport.top&&b.bottom<=viewport.bottom}
   assertNotNull("No fully visible reading passage",passage)
   val visible=passage!!
   val text=visible.config[SemanticsProperties.Text].joinToString(""){it.text}
   prefs.edit().putString("qa-history-anchor",text).putInt("qa-history-top",visible.boundsInRoot.top.toInt()).putStringSet("qa-history-ids",PocketTranscript.rows.map{it.s("id")}.toSet()).commit()
   val cache=TranscriptDiskCache(java.io.File(c.noBackupFilesDir,"transcript-cache-v1"))
   await{cache.read(PocketTranscript.scopeKey(),tid)?.contains(text)==true}
   assertFalse("Reading follows latest",PocketTranscript.readPosition?.follow?:true)
  }
 }
}
