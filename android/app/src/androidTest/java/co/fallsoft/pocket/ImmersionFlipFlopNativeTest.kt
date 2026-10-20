package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImmersionFlipFlopNativeTest {
 @Test(timeout=90000) fun allUnitsFlipWithoutAnimationAndTouchDoesNotSuspendClock(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun form(words:List<String>)=buildAnnotatedString{words.forEachIndexed{index,word->
   if(index>0)append(' ');val start=length;append(word)
   addStringAnnotation("immersion-reserve","lexical:"+index,start,length)
  }}
  val target=form(listOf("Puoi aprire","l’ultimo messaggio","in questa conversazione"));val meaning=form(listOf("Can-you open","the latest message","in this conversation"))
  val expected=immersionFlipForms(target,meaning)!!.map{it.text}.toSet()
  androidx.test.uiautomator.Configurator.getInstance().setWaitForIdleTimeout(300)
  var taps=0
  val allowMotion=mutableStateOf(true)
  ActivityScenario.launch(NativeComponentFixtureActivity::class.java).use{scenario->
   try {
   scenario.onActivity{activity->PocketImmersion.setEnabled(true);activity.setContent{
    CompositionLocalProvider(LocalImmersionMotionState provides allowMotion.value,LocalImmersionTransitionAnimated provides false){
     MaterialTheme{ImmersionReadingWaveHost{Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)){
      SharedInlineImmersionText(target,meaning,{1f})
      TextButton(onClick={taps++},modifier=Modifier.semantics{contentDescription="Flip fixture action"}){BilingualLabel("Full permissions for new tasks")}
     }}}
    }
   }}
   val seen=mutableSetOf<String>();val end=android.os.SystemClock.elapsedRealtime()+23000
   var tapped=false
   while(android.os.SystemClock.elapsedRealtime()<end&&seen!=expected){
    device.findObjects(By.pkg(c.packageName).text(java.util.regex.Pattern.compile("(Puoi|Can-you) .*"))).forEach{node->
     try{if(node.text in expected)seen+=node.text}catch(_:androidx.test.uiautomator.StaleObjectException){}
    }
    if(!tapped){val control=device.findObject(By.desc("Flip fixture action"));if(control!=null){control.click();tapped=true}}
    Thread.sleep(150)
   }
   device.takeScreenshot(java.io.File(c.cacheDir,"flip-flop-native.png"))
   assertEquals("Every lexical position must render both complementary assignments even with animations disabled",expected,seen)
   assertTrue("Touch remains actionable",taps>0)
   } finally {
    // Stop only the synthetic fixture clock before ActivityScenario waits for idle.
    // Live language cycling is intentionally perpetual; teardown must not wait for it.
    i.runOnMainSync { allowMotion.value=false }
   }
  }
 }
}
