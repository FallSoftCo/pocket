package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only with the disposable real-backend harness; provider RPC is substituted. */
@RunWith(AndroidJUnit4::class)
class RealBackendJourneyNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device get()=UiDevice.getInstance(i)
 private val c get()=i.targetContext
 private fun main(f:()->Unit)=i.runOnMainSync(f)
 private fun await(f:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(f())return;Thread.sleep(50)};assertTrue("Real-backend journey timed out",f())}
 @Test fun sendEchoAndColdRestore(){
  val args=InstrumentationRegistry.getArguments()
  org.junit.Assume.assumeTrue("Requires explicit disposable backend harness",args.getString("realBackendPhase")!=null)
  check(args.getString("qaOwner")=="01a0f2bc-ff5a-70c2-b682-10f0987dab80")
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val phase=requireNotNull(args.getString("realBackendPhase"));check(phase in setOf("send","restore"))
  val token=requireNotNull(args.getString("pairedToken"));val deviceId=requireNotNull(args.getString("pairedDevice"))
  val tid="11111111-1111-4111-8111-111111111111"
  val input="Integration input";val draft="Retained integration draft"
  val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  if(phase=="send")prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token",token).putString("deviceId",deviceId).commit()
  else {
   assertEquals("Pairing changed across cold restart",token,prefs.getString("token",null))
   assertEquals("Draft lost across cold restart",draft,prefs.getString("draft:$tid",null))
  }
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.any{it.id==tid}}
  val card=By.desc("Open Chat for Synthetic integration work")
  assertTrue("Real catalog card missing",device.wait(Until.hasObject(card),5000))
  device.findObject(card).click();await{Pocket.selected==tid&&!PocketTranscript.updating}
  if(phase=="send"){
   assertTrue(device.wait(Until.hasObject(By.text("Keyboard")),3000));device.findObject(By.text("Keyboard")).click()
   assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000))
   device.findObject(By.clazz("android.widget.EditText")).text=input
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
   await{!Pocket.sending&&PocketTranscript.rows.any{it.s("text")=="Synthetic answer to $input"}}
   device.pressBack();await{Pocket.selected==null}
   assertTrue(device.wait(Until.hasObject(card),3000));device.findObject(card).click()
   await{Pocket.selected==tid&&!PocketTranscript.updating}
  }
  await{PocketTranscript.rows.any{it.s("kind")=="user"&&it.s("text")==input}&&PocketTranscript.rows.any{it.s("text")=="Synthetic answer to $input"}}
  val rows=PocketTranscript.rows
  assertEquals("Send echo duplicated user row",1,rows.count{it.s("kind")=="user"&&it.s("text")==input})
  assertTrue("Answer displayed before its input",rows.indexOfFirst{it.s("kind")=="user"&&it.s("text")==input}<rows.indexOfFirst{it.s("text")=="Synthetic answer to $input"})
  assertTrue("Single visible input missing",device.wait(Until.hasObject(By.text(input)),3000))
  assertEquals("Visible user body duplicated",1,device.findObjects(By.text(input)).size)
  assertTrue(device.wait(Until.hasObject(By.text("Keyboard")),3000));device.findObject(By.text("Keyboard")).click()
  assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000))
  val editor=device.findObject(By.clazz("android.widget.EditText"))
  if(phase=="send"){editor.text=draft;await{prefs.getString("draft:$tid",null)==draft}}
  else assertEquals("Cold restored editor lost draft",draft,editor.text)
  device.pressBack();await{Pocket.selected==null}
 }
}
