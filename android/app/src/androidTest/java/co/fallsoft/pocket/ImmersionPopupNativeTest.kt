package co.fallsoft.pocket
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A real small native popup near the bottom, not a second full-screen canvas. */
@RunWith(AndroidJUnit4::class)
class ImmersionPopupNativeTest {
 @Test fun bottomPopupJoinsTheRootClockAndKeepsItsAction(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").commit()
  i.runOnMainSync{Pocket.init(c)}
  val motion=mutableStateOf(false);val opened=mutableStateOf(false)
  var clock:ImmersionReadingWaveClock?=null
  var taps=0
  val app=c.applicationContext as Application
  val callback=object:Application.ActivityLifecycleCallbacks {
   override fun onActivityCreated(a:Activity,b:Bundle?){}
   override fun onActivityPostCreated(a:Activity,b:Bundle?){
    if(a !is MainActivity)return
    PocketImmersion.setEnabled(true);PocketImmersion.setMotionEnabled(true);PocketImmersion.setSupportEnabled(true)
    a.setContent{
     val ready=rememberImmersionMotionEnvironment()
     CompositionLocalProvider(LocalImmersionMotionState provides (ready&&motion.value),LocalImmersionTransitionAnimated provides true){
      MaterialTheme{ImmersionReadingWaveHost{
       clock=LocalImmersionReadingWave.current
       Surface(Modifier.fillMaxSize(),color=Ink){
        Column(Modifier.fillMaxSize().statusBarsPadding()){
         TextButton(onClick={opened.value=true}){Text("Open popup")}
        }
        if(opened.value)Popup(alignment=Alignment.BottomCenter,onDismissRequest={opened.value=false},properties=PopupProperties(focusable=true)){
         Surface(color=Panel){TextButton(onClick={taps++;opened.value=false},modifier=Modifier.padding(12.dp)){
          BilingualLabel("Full permissions for new tasks",centered=false,maxLines=3)
         }}
        }
       }
      }}
     }
    }
   }
   override fun onActivityStarted(a:Activity){}
   override fun onActivityResumed(a:Activity){}
   override fun onActivityPaused(a:Activity){}
   override fun onActivityStopped(a:Activity){}
   override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
   override fun onActivityDestroyed(a:Activity){}
  }
  app.registerActivityLifecycleCallbacks(callback)
  try{
   ActivityScenario.launch(MainActivity::class.java).use{
    i.runOnMainSync{motion.value=true;opened.value=true}
    val seen=mutableSetOf<String>();val trace=org.json.JSONArray()
    val start=android.os.SystemClock.elapsedRealtime()
    while(android.os.SystemClock.elapsedRealtime()-start<22000){
     val node=device.findObject(By.pkg(c.packageName).textContains("per i"))
     if(node!=null){
      assertTrue("Popup was not actually near screen bottom",node.visibleBounds.top>device.displayHeight*.7)
      seen+=node.text
      trace.put(org.json.JSONObject().put("clockMs",clock?.elapsedMs).put("text",node.text).put("bounds",node.visibleBounds.toShortString()))
     }
     Thread.sleep(200)
    }
    java.io.File(c.cacheDir,"immersion-popup-trace.json").writeText(trace.toString())
    assertTrue("Bottom popup did not show both complete languages: "+seen,seen.containsAll(listOf("Permessi completi per i nuovi incarichi","Permissions full for the new tasks")))
    val node=requireNotNull(device.findObject(By.pkg(c.packageName).textContains("per i")))
    device.click(node.visibleBounds.centerX(),node.visibleBounds.centerY())
    i.runOnMainSync{assertEquals(1,taps);assertFalse(opened.value)}
   }
  }finally{app.unregisterActivityLifecycleCallbacks(callback);i.runOnMainSync{motion.value=false}}
 }
}
