package co.fallsoft.pocket

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationFilterNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device=UiDevice.getInstance(i)
 private fun main(block:()->Unit)=i.runOnMainSync(block)
 private fun await(condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue(condition())}
 @Test fun filterPreservesVisiblePassageAndReturnsWithoutJumping(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val context=i.targetContext
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{
   for(n in 0..29){
    val row=JSONObject().put("id","passage-$n").put("turnId","passages").put("kind","message").put("phase",if(n%2==0)"final_answer" else "commentary").put("text","Passage $n: a meaningful synthetic result with enough text to occupy a normal conversation row and test reading position.")
    PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","passages").put("version",100+n).put("row",row))
   }
  }
  assertTrue(device.wait(Until.hasObject(By.textContains("Passage 29:")),5000))
  // Read older work, using the same vertical gesture as one-handed scrolling.
  repeat(3){device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*2/3,25);Thread.sleep(250)}
  fun visibleFinal()=device.findObjects(By.textContains("Passage ")).filter{it.text.substringAfter("Passage ").substringBefore(":").toIntOrNull()?.rem(2)==0&&it.visibleBounds.top>50&&it.visibleBounds.bottom<device.displayHeight*3/4}.firstOrNull()
  val passage=requireNotNull(visibleFinal()){"No complete final passage visible after scroll"}
  val text=passage.text;val top=passage.visibleBounds.top
  device.findObject(By.text("Important")).click();Thread.sleep(400)
  val filtered=device.findObject(By.text(text));assertNotNull("Important filter lost reading passage",filtered)
  assertTrue("Important filter moved passage from $top to ${filtered.visibleBounds.top}",kotlin.math.abs(filtered.visibleBounds.top-top)<=24)
  device.findObject(By.text("Important")).click();Thread.sleep(400)
  val restored=device.findObject(By.text(text));assertNotNull("Unfilter lost reading passage",restored)
  assertTrue("Unfilter moved reading passage",kotlin.math.abs(restored.visibleBounds.top-top)<=24)
  fun end(status:String)=main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","states").put("version",500+listOf("failed","interrupted","completed").indexOf(status)).put("turn",JSONObject().put("id","states").put("status",status)))}
  end("failed");assertTrue("Failed turn mislabeled",device.wait(Until.hasObject(By.text("Failed")),3000))
  end("interrupted");assertTrue("Interrupted turn mislabeled",device.wait(Until.hasObject(By.text("Stopped")),3000))
  end("completed");assertTrue("Completed turn implies input required",device.wait(Until.hasObject(By.text("Ready")),3000))
  main{Pocket.detail=JSONObject(requireNotNull(Pocket.detail).toString()).put("pending",org.json.JSONArray().put(JSONObject().put("id","question")))}
  assertTrue("Pending question not distinguished",device.wait(Until.hasObject(By.text("Needs you")),3000))

 }
}
