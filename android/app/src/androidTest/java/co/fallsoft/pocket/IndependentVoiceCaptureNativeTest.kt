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
  requireOwnedNativeTarget(device)
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  fun await(label:String,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+12000;while(SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(50)};assertTrue(label+"; active="+PocketVoice.active+"; service="+(PocketVoice.service!=null)+"; state="+PocketVoice.state+"; problem="+PocketVoice.problem,predicate())}
  c.getSharedPreferences("pocket",0).edit().clear()
   .putString("server","http://127.0.0.1:18907").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-capture-device").commit()
  i.runOnMainSync { Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
  try {
   i.runOnMainSync { PocketVoice.start(c,recordImmediately=false,inPlace=true) }
   await("Voice service ready"){PocketVoice.active}
   // A cancelled Android service launch must not start later when its queued
   // start command finally reaches onStartCommand.
   i.runOnMainSync { PocketVoice.stop() }
   await("Previous voice service stopped"){PocketVoice.service==null}
   i.runOnMainSync {
    PocketVoice.start(c,recordImmediately=true,inPlace=true)
    PocketVoice.stop()
   }
   Thread.sleep(750)
   assertFalse("Cancelled launch did not acquire microphone",PocketVoice.captureBusy)
   assertFalse("Cancelled launch did not become active",PocketVoice.active)
   i.runOnMainSync { PocketVoice.start(c,recordImmediately=false,inPlace=true) }
   await("Fresh voice service ready"){PocketVoice.active}
   repeat(2) {
    i.runOnMainSync { PocketVoice.state="Thinking";PocketVoice.record() }
    await("Actual microphone recording visible"){PocketVoice.captureBusy&&PocketVoice.state=="Listening"}
    Thread.sleep(300)
    if(it==0)i.runOnMainSync { PocketVoice.record() } else device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
    await("Microphone released"){!PocketVoice.captureBusy&&PocketVoice.state=="Ready"}
   }
   i.runOnMainSync { PocketVoice.record() }
   await("Capture started before cancel"){PocketVoice.captureBusy&&PocketVoice.state=="Listening"}
   val cancelledKey=PocketVoice.latestRecordingKey
   i.runOnMainSync { PocketVoice.stop() }
   await("Cancelled microphone released"){!PocketVoice.captureBusy&&PocketVoice.service==null}
   assertNotNull(cancelledKey)
   // Android service destruction and the NonCancellable IO journal commit are
   // separate boundaries. Observe the durable result, not service visibility.
   await("Cancelled recording durably fenced"){
    VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use { store ->
     store.get(cancelledKey!!)?.let{it.capture==VoiceTurnStore.Capture.INTERRUPTED&&it.delivery==VoiceTurnStore.Delivery.CANCELLED}==true
    }
   }
   VoiceTurnStore(c,DurableVoiceWork.JOURNAL).use { store ->
    val cancelled=store.get(cancelledKey!!)!!
    assertEquals(VoiceTurnStore.Capture.INTERRUPTED,cancelled.capture)
    assertEquals(VoiceTurnStore.Delivery.CANCELLED,cancelled.delivery)
   }
  }finally{i.runOnMainSync{PocketVoice.stop()};await("Own service stopped"){PocketVoice.service==null}}
 }
}
