package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.view.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NativeReadingParityNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test(timeout=150000) fun slotsGalleryAndDecisionDrawerPreserveInputAndSource(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  requireOwnedNativeTarget(device)
  fun main(action:()->Unit){i.runOnMainSync(action);compose.waitForIdle()}
  fun waitPhrase(label:String):Boolean {
   compose.waitUntil(5000){compose.onAllNodesWithContentDescription(label,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
   return true
  }
  fun tapPhrase(label:String){
   waitPhrase(label)
   compose.onNodeWithContentDescription(label,useUnmergedTree=true).performTouchInput{click()}
  }

  fun await(message:String,condition:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+30000;while(android.os.SystemClock.elapsedRealtime()<end){if(condition()){compose.waitForIdle();return};Thread.sleep(50)};assertTrue(message+" [selected="+Pocket.selected+", native="+PocketTranscript.canonicalMode+", verified="+PocketTranscript.currentHistoryVerified+", updating="+PocketTranscript.updating+", loading="+PocketTranscript.loading+", readError="+PocketTranscript.readError+", error="+Pocket.error+"]",condition())}
  fun click(selector:BySelector){
   val end=android.os.SystemClock.elapsedRealtime()+10000;var bounds:android.graphics.Rect?=null;var stableAt=0L
   while(android.os.SystemClock.elapsedRealtime()<end){
    val node=device.findObject(selector);val next=node?.visibleBounds;val now=android.os.SystemClock.elapsedRealtime()
    if(next!=null&&!next.isEmpty&&node.isEnabled){
     if(next==bounds&&now-stableAt>=350){node.click();compose.waitForIdle();return}
     if(next!=bounds){bounds=android.graphics.Rect(next);stableAt=now}
    }else{bounds=null;stableAt=now}
    Thread.sleep(75)
   }
   fail("Control did not settle after presentation transition: $selector")
  }
  fun qa(action:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$action",JSONObject())}
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76-reading").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Native unsent draft").commit()
  main{Pocket.init(c);PocketImmersion.setEnabled(false);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
  val focusEvidence=JSONObject()
  val inputMode=java.util.concurrent.atomic.AtomicReference<InputModeManager>(null)
  val phraseLayout=java.util.concurrent.atomic.AtomicReference<JSONObject>(null)
  val phraseWidth=java.util.concurrent.atomic.AtomicInteger(0)
  try {
   await("Canonical conversation did not open"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
   qa("question")
   await("Question did not enter canonical metadata"){Pocket.detail?.optJSONArray("pending")?.length()==1}
   if(!device.wait(Until.hasObject(By.text("Native pending question")),5000)){
    val control=device.wait(Until.findObject(By.desc("Questions and results")),5000)
    if(control!=null){control.click();compose.waitForIdle()} else assertTrue("Question entry is not reachable",device.hasObject(By.text("Native pending question")))
   }
   assertTrue("One tap did not reveal the pending question",device.wait(Until.hasObject(By.text("Native pending question")),5000))
   assertTrue("Drawer stole the activity window used for volume-key capture",AppWindowVisibility.focused)
   val option=device.wait(Until.findObject(By.text("Proceed")),2000);assertNotNull(option);option!!.click();compose.waitForIdle()
   assertTrue(device.wait(Until.hasObject(By.text("Proceed").clazz("android.widget.EditText")),3000))
   click(By.desc("Close questions"))
   assertTrue("Questions control must stay reachable",device.wait(Until.hasObject(By.desc("Questions and results")),3000))
   click(By.desc("Questions and results"))
   assertTrue("Answer draft was lost when closing the drawer",device.wait(Until.hasObject(By.text("Proceed").clazz("android.widget.EditText")),3000))
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-questions.png"))
   val send=device.wait(Until.findObject(By.text("Send answers")),3000);assertNotNull(send);send!!.click();compose.waitForIdle()
   await("Decision not submitted exactly once"){qa("state").optInt("answers")==1}
   assertEquals("Native unsent draft",c.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
   qa("result");main{Pocket.refreshDetail()}
   await("Saved result did not enter canonical metadata"){Pocket.detail?.optJSONArray("notes")?.let{notes->(0 until notes.length()).any{notes.optJSONObject(it)?.optString("id")=="answer-one"}}==true}
   assertTrue("Saved result actions not visible",device.wait(Until.hasObject(By.text("Saved results")),10000))
   assertEquals("User input must not be a saved result",1,device.findObjects(By.text("Reply")).size)
   click(By.text("Reply"))
   await("Reply did not preserve the existing draft"){c.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread","").orEmpty().let{it.contains("About this result:")&&it.contains("Native unsent draft")}}
   await("Reply keyboard did not become visible"){i.uiAutomation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
   click(By.desc("Questions and results"))
   click(By.text("In conversation"))
   click(By.desc("Questions and results"))
   click(By.text("Dismiss"))
   await("Dismiss did not apply its canonical notes receipt"){Pocket.detail?.optJSONArray("notes")?.length()==0}
   main{Pocket.closeTask();PocketLive.stop()}
   val linkTaps=java.util.concurrent.atomic.AtomicInteger(0)
   fun form(values:List<String>)=buildAnnotatedString{values.forEachIndexed{at,text->
    if(at>0)append(' ');val begin=length;append(text);addStringAnnotation("immersion-reserve","part:$at",begin,length)
    if(at==3)addLink(LinkAnnotation.Clickable("reading-fixture-link",TextLinkStyles(),linkInteractionListener={linkTaps.incrementAndGet()}),begin,length)
   }}
   val forms=requireNotNull(immersionFlipForms(form(listOf("La correzione","è pronta","per la distribuzione","in questa conversazione")),form(listOf("The correction","is ready","for the distribution","in this conversation"))))
   val fontScale=mutableFloatStateOf(1f)
   val phase=mutableIntStateOf(0);val boxes=java.util.concurrent.atomic.AtomicReference<List<androidx.compose.ui.geometry.Rect?>>(null)
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.setContent{
    val currentInputMode=LocalInputModeManager.current
    SideEffect{inputMode.set(currentInputMode)}
    val density=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density,fontScale.floatValue)){
    val actualDensity=LocalDensity.current;val renderedPhase=phase.intValue
    MaterialTheme{Column(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp)){
     SelectionContainer{StableImmersionPhrases(forms,phase.intValue,modifier=Modifier.widthIn(max=320.dp).onGloballyPositioned{phraseWidth.set(it.size.width)},onTextLayout={result->
      boxes.set(result.placeholderRects)
      phraseLayout.set(JSONObject().put("phase",renderedPhase).put("composeDensity",actualDensity.density).put("composeFontScale",actualDensity.fontScale)
       .put("layoutDensity",result.layoutInput.density.density).put("layoutFontScale",result.layoutInput.density.fontScale)
       .put("layoutWidth",result.size.width).put("layoutHeight",result.size.height).put("constraintMaxWidth",result.layoutInput.constraints.maxWidth)
       .put("resourceDensity",c.resources.displayMetrics.density).put("placeholderRects",org.json.JSONArray(result.placeholderRects.map{r->r?.let{JSONObject().put("left",it.left).put("top",it.top).put("right",it.right).put("bottom",it.bottom).put("width",it.width).put("height",it.height)}?:JSONObject.NULL})))
     })}
     Spacer(Modifier.height(16.dp))
     GalleryCardPreview(MarkdownImage("http://127.0.0.1:18880/8bar/#native-preview","Native synthetic gallery",gallery=true))
    }}}
   }}
   await("Phrase layout did not appear"){boxes.get()?.size==4}
   fun capture(name:String){compose.waitForIdle();assertTrue("Screenshot failed: $name",device.takeScreenshot(java.io.File(c.cacheDir,name)));device.dumpWindowHierarchy(java.io.File(c.cacheDir,name.removeSuffix(".png")+".xml"))}
   capture("native-reading-slots-english.png")
   val before=boxes.get()
   val rects=requireNotNull(before).filterNotNull();val bands=rects.groupBy{it.top}.values.sortedBy{it.first().top}
   assertTrue("The reading fixture must wrap to exercise line spacing",bands.size>=2)
   assertTrue("Reserved phrase rows overlap",bands.zipWithNext().all{(prior,next)->next.minOf{it.top}>=prior.maxOf{it.bottom}-.5f})
   for(value in 1..3){main{phase.intValue=value};Thread.sleep(250);assertEquals("Phrase geometry changed on flip",before,boxes.get())}
   main{phase.intValue=1};Thread.sleep(250)
   assertTrue(waitPhrase("La correzione"))
   capture("native-reading-slots-mixed.png")
   main{phase.intValue=0}
   // Synchronize with rendered frames, not a 200ms host-speed assumption.
   val phaseDrawn=java.util.concurrent.CountDownLatch(1)
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.window.decorView.postOnAnimation{activity.window.decorView.postOnAnimation{phaseDrawn.countDown()}}}
   assertTrue("No frame after source-language phase",phaseDrawn.await(3,java.util.concurrent.TimeUnit.SECONDS))
   tapPhrase("The correction")
   assertTrue("Direct phrase tap did not change language",waitPhrase("La correzione"))
   for(value in listOf(1,2,3,0)){
    main{phase.intValue=value};Thread.sleep(250)
    assertTrue("Global flip erased the manually chosen language",compose.onAllNodesWithContentDescription("La correzione",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())
    assertEquals("Held phrase changed geometry",before,boxes.get())
    if(value==3)compose.onNodeWithText("in questa conversazione",useUnmergedTree=true).assertIsDisplayed()
   }
   tapPhrase("La correzione")
   assertTrue("Second tap must hold English",waitPhrase("The correction"))
   assertEquals(before,boxes.get())
   assertTrue("Gallery poster not rendered inline",device.wait(Until.hasObject(By.desc("Native material study")),10000))
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-gallery-slots.png"))
   // Exercise a real linked phrase: its action must not become a language toggle.
   compose.onNodeWithText("in this conversation",useUnmergedTree=true).performTouchInput{click()};compose.waitForIdle()
   await("Phrase link did not retain its action"){linkTaps.get()==1}
   compose.onNodeWithText("in this conversation",useUnmergedTree=true).assertIsDisplayed()
   assertEquals(before,boxes.get())
   main{phase.intValue=2;fontScale.floatValue=2f}
   await("Enlarged phrase layout did not settle"){phraseLayout.get()?.let{it.optInt("phase")==2&&it.optDouble("composeFontScale")==2.0&&it.optDouble("layoutFontScale")==2.0}==true&&boxes.get()?.size==4}
   compose.waitForIdle()
   // Wait for drawing after the observed 2x layout, not an earlier phase-only layout.
   val drawn=java.util.concurrent.CountDownLatch(1)
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.window.decorView.postOnAnimation{activity.window.decorView.postOnAnimation{drawn.countDown()}}}
   assertTrue("No frame after enlarged layout",drawn.await(3,java.util.concurrent.TimeUnit.SECONDS))
   val layoutEvidence=requireNotNull(phraseLayout.get()).put("containerWidth",phraseWidth.get())
   java.io.File(c.cacheDir,"native-reading-phrase-layout.json").writeText(layoutEvidence.toString())
   val enlarged=boxes.get();val enlargedRects=requireNotNull(enlarged).filterNotNull()
   assertTrue("Large-font phrase rows overlap",enlargedRects.groupBy{it.top}.values.sortedBy{it.first().top}.zipWithNext().all{(a,b)->b.minOf{it.top}>=a.maxOf{it.bottom}-.5f})
   assertTrue("Large-font phrase exceeded reserved width: $layoutEvidence",enlargedRects.all{it.left>=0&&it.width<=320*c.resources.displayMetrics.density+1&&it.right<=phraseWidth.get()+1})
   assertTrue(waitPhrase("The correction"))
   capture("native-reading-slots-large-english.png")
   main{phase.intValue=3};Thread.sleep(250);assertEquals(enlarged,boxes.get())
   compose.onNodeWithText("in questa conversazione",useUnmergedTree=true).assertIsDisplayed()
   capture("native-reading-slots-large-mixed.png")
   main{phase.intValue=0};Thread.sleep(250)
   // Tab navigation and Enter go through the actual native input path, not performClick semantics.
   // Compose semantics put focus, click and description on the same phrase node.
   // Android accessibility synthesizes an extra child: do not confuse the two trees.
   val firstPhraseFocus=isFocused() and hasClickAction() and hasContentDescription("The correction")
   fun firstPhraseFocused()=compose.onAllNodes(firstPhraseFocus,useUnmergedTree=true).fetchSemanticsNodes().size==1
   fun commitFocusFrames(){
    val committed=java.util.concurrent.CountDownLatch(1)
    main{
     val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single()
     val view=activity.window.decorView
     assertTrue("Focus capture requires hardware frame-commit observation",view.isHardwareAccelerated)
     view.viewTreeObserver.registerFrameCommitCallback{
      view.post{
       view.viewTreeObserver.registerFrameCommitCallback{committed.countDown()}
       view.postInvalidateOnAnimation()
      }
     }
     view.postInvalidateOnAnimation()
    }
    assertTrue("Post-focus frames did not commit",committed.await(3,java.util.concurrent.TimeUnit.SECONDS))
   }
   assertFalse("First phrase already focused before keyboard navigation",firstPhraseFocused())
   commitFocusFrames()
   capture("native-reading-slots-large-before-focus.png")
   var focused=false
   for(attempt in 0 until 6){
    device.pressKeyCode(KeyEvent.KEYCODE_TAB);compose.waitForIdle()
    java.io.File(c.cacheDir,"native-reading-focus-semantics-$attempt.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
    if(firstPhraseFocused()){focused=true;break}
   }
   assertTrue("Keyboard could not focus the first phrase",focused)
   main{focusEvidence.put("inputMode",inputMode.get()?.inputMode.toString());assertEquals("Tab focus must enter keyboard input mode",InputMode.Keyboard,inputMode.get()?.inputMode)}
   val windowBounds=compose.onNode(firstPhraseFocus,useUnmergedTree=true).fetchSemanticsNode().boundsInWindow
   val focusedBounds=android.graphics.Rect(windowBounds.left.toInt(),windowBounds.top.toInt(),windowBounds.right.toInt(),windowBounds.bottom.toInt())
   focusEvidence.put("bounds",focusedBounds.toShortString()).put("postFocusFramesRequested",2)
   commitFocusFrames()
   assertTrue("Phrase lost focus before capture",firstPhraseFocused())
   capture("native-reading-slots-large-focused.png")
   assertTrue("Phrase lost focus during capture",firstPhraseFocused())
   assertEquals("Focus feedback changed phrase geometry",enlarged,boxes.get())
   val baseline=requireNotNull(android.graphics.BitmapFactory.decodeFile(java.io.File(c.cacheDir,"native-reading-slots-large-before-focus.png").path)){"Baseline screenshot did not decode"}
   val focusedImage=requireNotNull(android.graphics.BitmapFactory.decodeFile(java.io.File(c.cacheDir,"native-reading-slots-large-focused.png").path)){"Focused screenshot did not decode"}
   try{
    assertEquals(baseline.width,focusedImage.width);assertEquals(baseline.height,focusedImage.height)
    assertTrue("Focused phrase bounds exceed screenshot",focusedBounds.left>=0&&focusedBounds.top>=0&&focusedBounds.right<=baseline.width&&focusedBounds.bottom<=baseline.height)
    var changed=0
    for(y in focusedBounds.top until focusedBounds.bottom)for(x in focusedBounds.left until focusedBounds.right)if(baseline.getPixel(x,y)!=focusedImage.getPixel(x,y))changed++
    focusEvidence.put("committedFrames",2).put("changedPhrasePixels",changed).put("exactWrapperFocusedBeforeAndAfter",true)
    java.io.File(c.cacheDir,"native-reading-focus-observation.json").writeText(focusEvidence.toString())
    assertTrue("Keyboard focus produced no visible phrase feedback: $focusEvidence",changed>0)
   }finally{baseline.recycle();focusedImage.recycle()}
   device.pressKeyCode(KeyEvent.KEYCODE_ENTER);compose.waitForIdle()
   assertTrue("Keyboard activation did not switch the focused phrase",waitPhrase("La correzione"))
   assertEquals("Keyboard activation changed phrase geometry",enlarged,boxes.get())
   assertEquals("Keyboard phrase activation fired the unrelated link",1,linkTaps.get())
   // Exercise the actual Markdown link path used by standalone review pages,
   // not a direct component invocation or a substituted catalog-card link.
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.setContent{
    MaterialTheme{Column(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp)){
     RichText("Here is the review.\n\n[Standalone review](http://127.0.0.1:18880/8bar/review/index.html#details)\n\nThe original conversation continues below.")
    }}
   }}
   await("Standalone page did not load its original image"){qa("state").optInt("galleryPageReads")==1&&device.hasObject(By.desc("Standalone review"))}
   compose.waitForIdle()
   assertFalse("Standalone preview failed",device.hasObject(By.text("Preview unavailable")))
   assertFalse("Standalone image failed",device.hasObject(By.text("Image unavailable")))
   capture("native-gallery-page.png")
   click(By.desc("Standalone review"))
   assertTrue("Inline page image did not open native viewer",device.wait(Until.hasObject(By.text("Close image")),10000))
   click(By.text("Close image"))
   assertTrue(device.wait(Until.hasObject(By.text("The original conversation continues below.")),10000))
   val state=qa("state");assertEquals(1,state.optInt("galleryMetadataReads"));assertEquals(1,state.optInt("galleryPageReads"));assertTrue(state.optInt("galleryImageReads")>=1);assertEquals(0,state.optInt("galleryCredentialLeaks"));assertEquals(0,state.optInt("workSubmissions"))
  }catch(error:Throwable){
   java.io.File(c.cacheDir,"native-reading-focus-observation.json").writeText(focusEvidence.put("failure",error.message).toString())
   phraseLayout.get()?.let{java.io.File(c.cacheDir,"native-reading-phrase-layout.json").writeText(it.put("containerWidth",phraseWidth.get()).toString())}
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-failure.png"))
   device.dumpWindowHierarchy(java.io.File(c.cacheDir,"native-reading-failure.xml"))
   java.io.File(c.cacheDir,"native-reading-failure.json").writeText(JSONObject().put("selected",Pocket.selected).put("detail",Pocket.detail).put("readError",PocketTranscript.readError).put("appError",Pocket.error).put("captureBusy",PocketVoice.captureBusy).put("focused",AppWindowVisibility.focused).toString())
   throw error
  }finally{main{Pocket.closeTask();PocketLive.stop()}}
 }
}
