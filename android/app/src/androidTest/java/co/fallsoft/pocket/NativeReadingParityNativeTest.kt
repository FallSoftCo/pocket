package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
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
   fun form(values:List<String>)=buildAnnotatedString{values.forEachIndexed{at,text->if(at>0)append(' ');val begin=length;append(text);addStringAnnotation("immersion-reserve","part:$at",begin,length)}}
   val forms=requireNotNull(immersionFlipForms(form(listOf("La correzione","è pronta")),form(listOf("The correction","is ready"))))
   val phase=mutableIntStateOf(0);val boxes=java.util.concurrent.atomic.AtomicReference<List<androidx.compose.ui.geometry.Rect?>>(null)
   main{val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single();activity.setContent{
    MaterialTheme{Column(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp)){
     SelectionContainer{StableImmersionPhrases(forms,phase.intValue,onTextLayout={boxes.set(it.placeholderRects)})}
     Spacer(Modifier.height(16.dp))
     GalleryCardPreview(MarkdownImage("http://127.0.0.1:18880/8bar/#native-preview","Native synthetic gallery",gallery=true))
    }}
   }}
   await("Phrase layout did not appear"){boxes.get()?.size==2}
   val before=boxes.get()
   for(value in 1..3){main{phase.intValue=value};Thread.sleep(250);assertEquals("Phrase geometry changed on flip",before,boxes.get())}
   main{phase.intValue=0};Thread.sleep(200)
   val phrase=device.wait(Until.findObject(By.desc("The correction")),2000);assertNotNull(phrase);phrase!!.click()
   assertTrue("Direct phrase tap did not change language",device.wait(Until.hasObject(By.desc("La correzione")),2000))
   assertEquals(before,boxes.get())
   assertTrue("Gallery poster not rendered inline",device.wait(Until.hasObject(By.desc("Native material study")),10000))
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-gallery-slots.png"))
   val state=qa("state");assertEquals(1,state.optInt("galleryMetadataReads"));assertTrue(state.optInt("galleryImageReads")>=1);assertEquals(0,state.optInt("galleryCredentialLeaks"));assertEquals(0,state.optInt("workSubmissions"))
  }catch(error:Throwable){
   device.takeScreenshot(java.io.File(c.cacheDir,"native-reading-failure.png"))
   device.dumpWindowHierarchy(java.io.File(c.cacheDir,"native-reading-failure.xml"))
   java.io.File(c.cacheDir,"native-reading-failure.json").writeText(JSONObject().put("selected",Pocket.selected).put("detail",Pocket.detail).put("readError",PocketTranscript.readError).put("appError",Pocket.error).put("captureBusy",PocketVoice.captureBusy).put("focused",AppWindowVisibility.focused).toString())
   throw error
  }finally{main{Pocket.closeTask();PocketLive.stop()}}
 }
}
