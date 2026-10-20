package co.fallsoft.pocket

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WorkCardReadabilityNativeTest {
 @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
 @Test(timeout=60000) fun idleIsChromaticNotDisabledAndStillOpens(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val openedSession=AtomicInteger();val openedRoom=AtomicInteger()
  val monitor=i.addMonitor(NativeComponentFixtureActivity::class.java.name,null,false)
  c.startActivity(android.content.Intent(c,NativeComponentFixtureActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
  val activity=i.waitForMonitorWithTimeout(monitor,10000) as? NativeComponentFixtureActivity
  assertNotNull(activity)
  try{
   i.runOnMainSync{
    // Pixel contrast and stable tap IDs must work with either retained language preference.
    activity!!.setContent{MaterialTheme{
     Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().padding(12.dp).semantics{testTagsAsResourceId=true},
       verticalArrangement=Arrangement.spacedBy(8.dp)){
      for((state,title,needs) in listOf(Triple("idle","Idle session",false),Triple("active","Running session",false),Triple("idle","Session needs you",true))){
       TaskCard(Task("qa-"+title,title,"",state,0,false,preview="Available to open and reply",needsInput=needs),
        modifier=Modifier.testTag("qa-"+title),onOpen={openedSession.incrementAndGet()},onRename=null,onWatch=null)
      }
      for((state,title) in listOf("idle" to "Idle room","running" to "Running room","waiting" to "Room needs you")){
       val counts=JSONObject().put("version",1).put("observedAt",System.currentTimeMillis())
       HollywoodRoomActivity.keys.forEach{counts.put(it,0)}
       if(state=="running")counts.put("running",1)
       if(state=="waiting")counts.put("waitingForHuman",1)
       HollywoodRoomCard(JSONObject().put("id",state).put("name",title).put("purpose","Available to open and reply").put("activity",counts),true){openedRoom.incrementAndGet()}
      }
     }
    }}
   }
   assertTrue(device.wait(Until.hasObject(By.res("qa-Idle session")),10000))
   assertTrue(device.wait(Until.hasObject(By.res("native-room:idle")),10000))
   Thread.sleep(350)
   val file=c.filesDir.resolve("work-card-idle-chroma.png");assertTrue(device.takeScreenshot(file))
   val bitmap=android.graphics.BitmapFactory.decodeFile(file.path)
   fun surface(tag:String):Int{
    val b=device.findObject(By.res(tag)).visibleBounds
    return bitmap.getPixel(b.left+8,b.centerY())
   }
   val sessionColor=surface("qa-Idle session");val roomColor=surface("native-room:idle")
   assertEquals("Idle session hue lost",0xff152137.toInt(),sessionColor)
   assertEquals("Rooms must share the identical idle hue",sessionColor,roomColor)
   fun luminance(v:Int):Double{
    fun linear(n:Int):Double{val x=n/255.0;return if(x<=.04045)x/12.92 else Math.pow((x+.055)/1.055,2.4)}
    return .2126*linear(android.graphics.Color.red(v))+.7152*linear(android.graphics.Color.green(v))+.0722*linear(android.graphics.Color.blue(v))
   }
   assertTrue("Idle must not be dimmer than available baseline",luminance(sessionColor)>=luminance(0xff181818.toInt()))
   assertTrue("White text contrast below WCAG normal-text threshold",1.05/(luminance(sessionColor)+.05)>=4.5)
   device.findObject(By.res("qa-Idle session")).click()
   device.findObject(By.res("native-room:idle")).click()
   val deadline=SystemClock.elapsedRealtime()+3000
   while((openedSession.get()!=1||openedRoom.get()!=1)&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(30)
   assertEquals(1,openedSession.get());assertEquals(1,openedRoom.get())
   c.filesDir.resolve("work-card-idle-chroma.json").writeText(JSONObject()
    .put("sameIdleHueAcrossBackends",true).put("idleNotDimmer",true).put("normalTextContrast",1.05/(luminance(sessionColor)+.05))
    .put("sessionOpenCount",openedSession.get()).put("roomOpenCount",openedRoom.get())
    .put("actualNativeCards",true).put("physicalPhone",false).toString())
  }finally{i.runOnMainSync{activity?.finish()};i.removeMonitor(monitor)}
 }
}
