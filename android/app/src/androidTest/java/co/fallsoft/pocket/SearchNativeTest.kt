package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchNativeTest {
 @Test fun immersionSearchOpensTypesFiltersAndDismissesWithoutLosingDraft() {
  val i=InstrumentationRegistry.getInstrumentation()
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  val prefs=i.targetContext.getSharedPreferences("pocket",android.content.Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880")
   .putString("token","synthetic-system76").putString("deviceId","synthetic-device")
   .putString("draft:same-thread","Search must preserve this draft").commit()
  i.runOnMainSync {
    Pocket.init(i.targetContext)
    Pocket.tab=0
    PocketImmersion.setEnabled(true)
    i.targetContext.startActivity(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   }
   val find=device.wait(Until.findObject(By.desc("Find")),10000)
   assertNotNull("Find control missing",find)
   find.click()
   val input=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
   assertNotNull("Search did not open with immersion enabled",input)
   input.click()
   assertTrue("Typing keyboard did not open",device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")),15000))
   input.text="Transcript"
   assertEquals("Transcript",input.text)
   // Search chips are in a horizontally unbounded row. Keep the real screen
   // alive across language handoffs, not just its first static measure.
   Thread.sleep(7000)
   assertTrue(device.hasObject(By.pkg("co.fallsoft.pocket").clazz("android.widget.EditText")))
   device.pressBack()
   assertTrue("Typing keyboard did not dismiss",device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")),10000))
   // Wait for the IME to settle before dismissing its parent sheet.
   // Two immediate Back events can both reach the keyboard on slow AVDs.
   device.pressBack()
   assertNotNull("Search sheet did not close",device.wait(Until.findObject(By.desc("Find")),15000))
   assertEquals("Search must preserve this draft",prefs.getString("draft:same-thread",null))
 }
}
