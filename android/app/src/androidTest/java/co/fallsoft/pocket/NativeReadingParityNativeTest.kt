package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.view.KeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeReadingParityNativeTest {
 @Test(timeout=150000) fun slotsGalleryAndDecisionDrawerPreserveInputAndSource(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  requireOwnedNativeTarget(device)
  fun main(action:()->Unit)=i.runOnMainSync(action)
  fun await(message:String,condition:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+30000;while(android.os.SystemClock.elapsedRealtime()<end&&!condition())Thread.sleep(50);assertTrue(message+" [selected="+Pocket.selected+", native="+PocketTranscript.canonicalMode+", verified="+PocketTranscript.currentHistoryVerified+", updating="+PocketTranscript.updating+", loading="+PocketTranscript.loading+", readError="+PocketTranscript.readError+", error="+Pocket.error+"]",condition())}
  fun click(selector:BySelector){
   val end=android.os.SystemClock.elapsedRealtime()+10000;var bounds:android.graphics.Rect?=null;var stableAt=0L
   while(android.os.SystemClock.elapsedRealtime()<end){
    val node=device.findObject(selector);val next=node?.visibleBounds;val now=android.os.SystemClock.elapsedRealtime()
    if(next!=null&&!next.isEmpty&&node.isEnabled){
     if(next==bounds&&now-stableAt>=350){node.click();return}
     if(next!=bounds){bounds=android.graphics.Rect(next);stableAt=now}
    }else{bounds=null;stableAt=now}
    Thread.sleep(75)
   }
   fail("Control did not settle after presentation transition: $selector")
  }
  fun qa(action:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$action",JSONObject())}
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76-reading").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Native unsent draft").commit()
  main{Pocket.init(c);PocketImmersion.setEnabled(false);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.open("same-thread")}
  val phraseLayout=java.util.concurrent.atomic.AtomicReference<JSONObject>(null)
  val phraseWidth=java.util.concurrent.atomic.AtomicInteger(0)
  try {
   await("Canonical conversation did not open"){PocketTranscript.currentHistoryVerified&&!PocketTranscript.updating}
   qa("question")
   await("Question did not enter canonical metadata"){Pocket.detail?.optJSONArray("pending")?.length()==1}
   if(!device.wait(Until.hasObject(By.text("Native pending question")),5000)){
    val control=device.wait(Until.findObject(By.desc("Questions and results")),5000)
    if(control!=null)control.click() else assertTrue("Question entry is not reachable",device.hasObject(By.text("Native pending question")))
   }
   assertTrue("One tap did not reveal the pending question",device.wait(Until.hasObject(By.text("Native pending question")),5000))
   assertTrue("Drawer stole the activity window used for volume-key capture",AppWindowVisibility.focused)
   val option=device.wait(Until.findObject(By.text("Proceed")),2000);assertNotNull(option);option!!.click()
   assertTrue(device.wait(Until.hasObject(By.text("Proceed").clazz("android.widget.EditText")),3000))
   click(By.desc("Close questions"))
   assertTrue("Questions control must stay reachable",device.wait(Until.hasObject(By.desc("Questions and results")),3000))
   click(By.desc("Questions and results"))
   assertTrue("Answer draft was lost when closing the drawer",device.wait(Until.hasObject(By.text("Proceed").clazz("android.widget.EditText")),3000))
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-questions.png"))
   val send=device.wait(Until.findObject(By.text("Send answers")),3000);assertNotNull(send);send!!.click()
   await("Decision not submitted exactly once"){qa("state").optInt("answers")==1}
   assertEquals("Native unsent draft",c.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
   qa("result");main{Pocket.refreshDetail()}
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
   fun capture(name:String){device.waitForIdle();assertTrue("Screenshot failed: $name",device.takeScreenshot(java.io.File(c.cacheDir,name)));device.dumpWindowHierarchy(java.io.File(c.cacheDir,name.removeSuffix(".png")+".xml"))}
   capture("native-reading-slots-english.png")
   val before=boxes.get()
   val rects=requireNotNull(before).filterNotNull();val bands=rects.groupBy{it.top}.values.sortedBy{it.first().top}
   assertTrue("The reading fixture must wrap to exercise line spacing",bands.size>=2)
   assertTrue("Reserved phrase rows overlap",bands.zipWithNext().all{(prior,next)->next.minOf{it.top}>=prior.maxOf{it.bottom}-.5f})
   for(value in 1..3){main{phase.intValue=value};Thread.sleep(250);assertEquals("Phrase geometry changed on flip",before,boxes.get())}
   main{phase.intValue=1};Thread.sleep(250)
   assertTrue(device.wait(Until.hasObject(By.desc("La correzione")),2000))
   capture("native-reading-slots-mixed.png")
   main{phase.intValue=0};Thread.sleep(200)
   val phrase=device.wait(Until.findObject(By.desc("The correction")),2000);assertNotNull(phrase);phrase!!.click()
   assertTrue("Direct phrase tap did not change language",device.wait(Until.hasObject(By.desc("La correzione")),2000))
   assertEquals(before,boxes.get())
   assertTrue("Gallery poster not rendered inline",device.wait(Until.hasObject(By.desc("Native material study")),10000))
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-gallery-slots.png"))
   // Exercise a real linked phrase: its action must not become a language toggle.
   val linked=device.wait(Until.findObject(By.text("in this conversation")),2000);assertNotNull(linked);linked!!.click()
   await("Phrase link did not retain its action"){linkTaps.get()==1}
   assertTrue(device.hasObject(By.text("in this conversation")))
   assertEquals(before,boxes.get())
   main{phase.intValue=2;fontScale.floatValue=2f}
   await("Enlarged phrase layout did not settle"){phraseLayout.get()?.let{it.optInt("phase")==2&&it.optDouble("composeFontScale")==2.0&&it.optDouble("layoutFontScale")==2.0}==true&&boxes.get()?.size==4}
   device.waitForIdle()
   // Wait for drawing after the observed 2x layout, not an earlier phase-only layout.
   val drawn=java.util.concurrent.CountDownLatch(1)
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.window.decorView.postOnAnimation{activity.window.decorView.postOnAnimation{drawn.countDown()}}}
   assertTrue("No frame after enlarged layout",drawn.await(3,java.util.concurrent.TimeUnit.SECONDS))
   val layoutEvidence=requireNotNull(phraseLayout.get()).put("containerWidth",phraseWidth.get())
   java.io.File(c.cacheDir,"native-reading-phrase-layout.json").writeText(layoutEvidence.toString())
   val enlarged=boxes.get();val enlargedRects=requireNotNull(enlarged).filterNotNull()
   assertTrue("Large-font phrase rows overlap",enlargedRects.groupBy{it.top}.values.sortedBy{it.first().top}.zipWithNext().all{(a,b)->b.minOf{it.top}>=a.maxOf{it.bottom}-.5f})
   assertTrue("Large-font phrase exceeded reserved width: $layoutEvidence",enlargedRects.all{it.left>=0&&it.width<=320*c.resources.displayMetrics.density+1&&it.right<=phraseWidth.get()+1})
   assertTrue(device.wait(Until.hasObject(By.desc("The correction")),2000))
   capture("native-reading-slots-large-english.png")
   main{phase.intValue=3};Thread.sleep(250);assertEquals(enlarged,boxes.get())
   assertTrue(device.wait(Until.hasObject(By.text("in questa conversazione")),2000))
   capture("native-reading-slots-large-mixed.png")
   main{phase.intValue=0};Thread.sleep(250)
   // Tab navigation and Enter go through the actual native input path, not performClick semantics.
   // Compose exposes focus on the clickable wrapper, while the exact phrase
   // description is its child. Do not accept focus on the whole paragraph/gallery.
   val firstPhraseFocus=By.clickable(true).focusable(true).focused(true).hasChild(By.desc("The correction"))
   var focused=false
   for(attempt in 0 until 6){
    device.pressKeyCode(KeyEvent.KEYCODE_TAB)
    if(device.wait(Until.hasObject(firstPhraseFocus),500)){focused=true;break}
   }
   assertTrue("Keyboard could not focus the first phrase",focused)
   capture("native-reading-slots-large-focused.png")
   device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
   assertTrue("Keyboard activation did not switch the focused phrase",device.wait(Until.hasObject(By.desc("La correzione")),2000))
   assertEquals("Keyboard activation changed phrase geometry",enlarged,boxes.get())
   assertEquals("Keyboard phrase activation fired the unrelated link",1,linkTaps.get())
   val state=qa("state");assertEquals(1,state.optInt("galleryMetadataReads"));assertTrue(state.optInt("galleryImageReads")>=1);assertEquals(0,state.optInt("galleryCredentialLeaks"));assertEquals(0,state.optInt("workSubmissions"))
  }catch(error:Throwable){
   phraseLayout.get()?.let{java.io.File(c.cacheDir,"native-reading-phrase-layout.json").writeText(it.put("containerWidth",phraseWidth.get()).toString())}
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-failure.png"))
   device.dumpWindowHierarchy(java.io.File(c.cacheDir,"native-reading-failure.xml"))
   java.io.File(c.cacheDir,"native-reading-failure.json").writeText(JSONObject().put("selected",Pocket.selected).put("detail",Pocket.detail).put("readError",PocketTranscript.readError).put("appError",Pocket.error).put("captureBusy",PocketVoice.captureBusy).put("focused",AppWindowVisibility.focused).toString())
   throw error
  }finally{main{Pocket.closeTask();PocketLive.stop()}}
 }
}
