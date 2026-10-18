package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real HTTP delivery with deliberately absent socket; real Android display clock. */
@RunWith(AndroidJUnit4::class)
class ImmersionDeliveryNativeTest {
 @Test fun missedSocketResultArrivesAndCyclesBothGrammarFormsWithoutResubmission(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",0).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","private-synthetic").commit()
  i.runOnMainSync{Pocket.init(c)}
  val seen=mutableSetOf<String>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->
    PocketImmersion.setEnabled(true);PocketImmersion.setSupportEnabled(true);PocketImmersion.setMotionEnabled(true)
    activity.setContent{
     CompositionLocalProvider(LocalImmersionMotionState provides true,LocalImmersionTransitionAnimated provides true){
      MaterialTheme{ImmersionReadingWaveHost{Surface(Modifier.fillMaxSize(),color=Ink){Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)){ImmersionSelection("Your connection is available."){BilingualMessage("delivery-body","Your connection is available.")}}}}}
     }
    }
   }
   val end=android.os.SystemClock.elapsedRealtime()+28000
   while(android.os.SystemClock.elapsedRealtime()<end){
    d.findObjects(By.pkg(c.packageName).text(java.util.regex.Pattern.compile(".*(connection|connessione).*"))).forEach{seen+=it.text}
    if(seen.containsAll(listOf("La tua connessione è disponibile.","The your connection is available.")))break
    Thread.sleep(150)
   }
   d.dumpWindowHierarchy(java.io.File(c.cacheDir,"immersion-delivery.xml"));d.takeScreenshot(java.io.File(c.cacheDir,"immersion-delivery.png"))
   java.io.File(c.cacheDir,"immersion-delivery-forms.json").writeText(org.json.JSONArray(seen.toList()).toString())
   assertTrue("Italian never rendered: $seen",seen.contains("La tua connessione è disponibile."))
   assertTrue("Italian-order English never rendered: $seen",seen.contains("The your connection is available."))
   assertTrue(PocketImmersion.hasTranslation("delivery-body","Your connection is available."))
  }
 }
}
