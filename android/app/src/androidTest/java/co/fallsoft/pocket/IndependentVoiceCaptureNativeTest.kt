package co.fallsoft.pocket

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual AudioRecord on the retained emulator; no pairing changes or task dispatch. */
@RunWith(AndroidJUnit4::class)
class IndependentVoiceCaptureNativeTest {
 @Test fun captureStartsAndStopsIndependentlyOfPresentationState() {
  val i=InstrumentationRegistry.getInstrumentation();val device=UiDevice.getInstance(i);val c=i.targetContext
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  fun await(label:String,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(50)};assertTrue(label,predicate())}
  i.runOnMainSync { Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  try {
   i.runOnMainSync { PocketVoice.start(c,recordImmediately=false,inPlace=true) }
   await("Voice service ready"){PocketVoice.active}
   repeat(2) {
    i.runOnMainSync { PocketVoice.state="Thinking";PocketVoice.record() }
    await("Actual microphone recording visible"){PocketVoice.captureBusy&&PocketVoice.state=="Listening"}
    Thread.sleep(300)
    if(it==0)i.runOnMainSync { PocketVoice.record() } else device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
    await("Microphone released"){!PocketVoice.captureBusy&&PocketVoice.state=="Ready"}
   }
  }finally{i.runOnMainSync{PocketVoice.stop()};await("Own service stopped"){PocketVoice.service==null}}
 }
}
