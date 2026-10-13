package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingNavigationNativeTest{
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context get()=instrumentation.targetContext
 private val device get()=UiDevice.getInstance(instrumentation)
 private fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
 private fun await(condition:()->Boolean){
  val until=System.currentTimeMillis()+15000
  while(System.currentTimeMillis()<until){if(condition())return;Thread.sleep(50)}
  if(!condition()){device.dumpWindowHierarchy(java.io.File(context.cacheDir,"pairing-condition-timeout.xml"));device.takeScreenshot(java.io.File(context.cacheDir,"pairing-condition-timeout.png"))}
  assertTrue("Pairing/navigation condition timed out",condition())
 }
 @Test fun cancelledPairingPrefillCannotHijackConversationIntent(){
  pairedPrefill()
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  repeat(4){if(!device.hasObject(By.text("Cancel")))device.swipe(device.displayWidth/2,(device.displayHeight*.82f).toInt(),device.displayWidth/2,(device.displayHeight*.38f).toInt(),35)}
  assertEquals("Pairing prefill lost existing token","synthetic-system76",Pocket.token)
  device.dumpWindowHierarchy(java.io.File(context.cacheDir,"pairing-cancel-regression.xml"))
  assertNotNull("Pairing cancellation inaccessible",device.findObject(By.text("Cancel")))
  device.findObject(By.text("Cancel")).click()
  await{!Pocket.pairingMode}
  main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("thread","same-thread"))}
  await{Pocket.selected=="same-thread"&&!Pocket.pairingMode&&PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}}
  assertTrue("Conversation intent reopened pairing",device.wait(Until.hasObject(By.text("Fresh response 1")),3000))
  assertFalse(device.hasObject(By.text("One-time pairing code")))
  assertEquals("synthetic-system76",Pocket.token)
  assertEquals("Preserved pairing navigation draft",prefs.getString("draft:same-thread",null))
 }

 private fun pairedPrefill(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Preserved pairing navigation draft").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("server","http://127.0.0.1:18880").putExtra("code","SYNTHETIC-PREFILL"))}
  assertEquals("Paired fixture token was not restored","synthetic-system76",Pocket.token)
  await{Pocket.pairingMode&&device.hasObject(By.text("One-time pairing code"))}
 }
 @Test fun pairingActionsRemainReachableWithKeyboardAndLongError(){
  pairedPrefill()
  val previousKeyboard=device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
  device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
  try{
  val input=device.findObject(By.clazz("android.widget.EditText").text("SYNTHETIC-PREFILL"))
  assertNotNull(input);input.click();input.text="Typed synthetic code"
  await{device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
  val imePackage=device.executeShellCommand("settings get secure default_input_method").trim().substringBefore("/")
  await{device.findObjects(By.pkg(imePackage)).any{it.visibleBounds.height()>device.displayHeight/6}}
  main{Pocket.error="Synthetic pairing validation detail. ".repeat(40)}
  await{device.hasObject(By.text("Connect Codex"))&&device.hasObject(By.text("Cancel"))}
  assertTrue("Primary action clipped by keyboard/error",device.findObject(By.text("Connect Codex")).visibleBounds.height()>20)
  assertTrue("Cancel clipped by keyboard/error",device.findObject(By.text("Cancel")).visibleBounds.height()>20)
  await{device.findObjects(By.pkg(imePackage)).any{it.visibleBounds.height()>device.displayHeight/6}}
  assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"pairing-actions-ime.png")))
  device.findObject(By.text("Cancel")).click()
  await{!Pocket.pairingMode}
  assertEquals("synthetic-system76",Pocket.token)
  assertEquals("Preserved pairing navigation draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  }finally{device.executeShellCommand(if(previousKeyboard=="null")"settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard "+previousKeyboard)}
 }
}
