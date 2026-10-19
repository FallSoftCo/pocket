package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@RunWith(AndroidJUnit4::class)
class OnDemandSpeechNativeTest {
 @Test fun legacyBacklogRetiresWithoutAcknowledgementAndClearAllIsDirect(){
  val i=InstrumentationRegistry.getInstrumentation()
  val c=ApplicationProvider.getApplicationContext<Context>()
  val storage=c.getSharedPreferences("speech-playback",Context.MODE_PRIVATE)
  val usage=c.getSharedPreferences("native-speech-usage",Context.MODE_PRIVATE)
  val generatedBefore=usage.getLong("generated",0)
  val saved=storage.all.toMap()
  val immersion=PocketImmersion.enabled
  val device=UiDevice.getInstance(i)
  assertEquals("Only the owned QA emulator is allowed","nextcomp_env_qa",device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim())
  val scenario=ActivityScenario.launch(MainActivity::class.java)
  val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
  fun main(block:()->Unit)=i.runOnMainSync(block)
  try{
   main{PocketImmersion.setEnabled(false)}
   val profile=PocketSpeech.ownerProfile()
   val rows=JSONArray();repeat(550){rows.put(JSONObject().put("id",900000L+it).put("title","Unrequested update").put("kind","update").put("text","Original text stays in its conversation").put("threadId","synthetic-old-source"))}
   storage.edit().putString("queueProfile",profile).putString("queue:"+profile,JSONObject().put("messages",rows).toString())
    .putString("sessions:"+profile,JSONObject().put("activeSource","synthetic-old-source").put("groups",JSONArray().put(JSONObject().put("threadId","synthetic-old-source").put("messages",rows))).toString())
    .putString("heard:"+profile,"[91]").commit()
   main{PocketSpeech.init()}
   assertEquals("Automatic backlog survived migration",0,PocketSpeech.count)
   assertEquals("Migration falsely acknowledged speech","[91]",storage.getString("heard:"+profile,null))
   main{PocketSpeech.init()}
   assertEquals("Restart resurrected retired backlog",0,PocketSpeech.count)
   val notice=JSONObject().put("id",999998L).put("kind","update").put("title","Incoming update").put("body","Text remains available").put("created_at",System.currentTimeMillis())
   main{Pocket.acceptNotification(notice,"socket")}
   assertEquals("Notification arrival admitted playback",0,PocketSpeech.count)
   assertNull("Unrequested notification started playback service",PocketSpeech.service)
   assertFalse("Retirement marked the incoming update read",PocketNotificationReads.isRead(notice))
   main{
    PocketSpeech.sessions.enqueue(SpokenMessage(-1,"Requested preview A","preview","A"))
    PocketSpeech.sessions.enqueue(SpokenMessage(-2,"Requested preview B","preview","B"))
    PocketSpeech.publish()
   }
   scenario.onActivity{activity->activity.setContent{SpeechPlayer()}}
   assertTrue("Clear all requires a menu",device.wait(Until.hasObject(By.desc("Clear all saved speech")),5000))
   device.findObject(By.desc("Clear all saved speech")).click()
   val end=System.currentTimeMillis()+3000
   while(PocketSpeech.count!=0&&System.currentTimeMillis()<end)Thread.sleep(20)
   assertEquals("Direct clear left another source queued",0,PocketSpeech.count)
   assertEquals("Clear falsely acknowledged speech","[91]",storage.getString("heard:"+profile,null))
   // Actual shared controls, their 48dp targets and the painted rim at both
   // text sizes; no worker, message submission or speech generation is needed.
   for(scale in listOf(1f,2f)){
    device.executeShellCommand("settings put system font_scale "+scale)
    val deadline=System.currentTimeMillis()+5000
    while(kotlin.math.abs(c.resources.configuration.fontScale-scale)>.05f&&System.currentTimeMillis()<deadline)Thread.sleep(50)
    scenario.onActivity{activity->activity.setContent{
     NextCompDepthRoot{Column(Modifier.fillMaxSize()){
      Spacer(Modifier.weight(1f))
      ConversationFooter{
       InlineSpeechVolume(Modifier.width(inlineVolumeControlWidth()))
       ConversationNotesControls(false,{},false,{""})
       ConversationActionsMenu(false,{}){}
       ReaderPagingButtons(rememberLazyListState())
      }
     }}
    }}
    assertTrue("Shared footer missing",device.wait(Until.hasObject(By.res("bottom-control-bar")),5000))
    Thread.sleep(300)
    val bar=device.findObject(By.res("bottom-control-bar")).visibleBounds
    val focus=device.findObject(By.desc("Important")).visibleBounds
    val px=c.resources.displayMetrics.density
    assertTrue("Focus button lost touch height",focus.height()>=48*px-2)
    assertTrue("Focus touches top rim",focus.top-bar.top>=6*px-2)
    assertTrue("Focus touches bottom rim",bar.bottom-focus.bottom>=6*px-2)
    assertTrue("Focus escapes horizontal rim",focus.left>=bar.left+8*px-2&&focus.right<=bar.right-8*px+2)
    assertTrue(device.takeScreenshot(java.io.File(c.filesDir,"toolbar-insets-"+scale+".png")))
   }
   assertEquals("Unrequested audio was generated",generatedBefore,usage.getLong("generated",0))
   java.io.File(c.filesDir,"on-demand-speech-result.json").writeText(JSONObject().put("retired",550).put("restoredAgain",0).put("notificationAdmitted",false).put("heardUnchanged",true).put("directClearAll",true).put("playbackServiceStarted",PocketSpeech.service!=null).put("generatedDelta",usage.getLong("generated",0)-generatedBefore).toString())
  }finally{
   main{PocketSpeech.clearAll()}
   val edit=storage.edit().clear()
   saved.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Boolean->edit.putBoolean(k,v);is Int->edit.putInt(k,v);is Long->edit.putLong(k,v);is Float->edit.putFloat(k,v)}}
   edit.commit();main{PocketSpeech.init();PocketImmersion.setEnabled(immersion)}
   device.executeShellCommand("settings put system font_scale "+font)
   scenario.close()
  }
 }
}
