package co.fallsoft.pocket

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Existing account audio output through actual Android renderer/player while voice mode remains idle. */
@RunWith(AndroidJUnit4::class)
class VoiceResponsePlaybackNativeTest {
 @Test fun idleVoiceModeDoesNotBlockAttributedResponsePlayback() {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  i.runOnMainSync{Pocket.init(c)}
  check(Pocket.base.startsWith("http://127.0.0.1:18889")||Pocket.base.startsWith("http://127.0.0.1:18890")){"Only retained isolated QA profile is permitted"}
  val audio=c.getSystemService(AudioManager::class.java);val originalVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
  val usage=c.getSharedPreferences("native-speech-usage",0)
  val generated=usage.getLong("generated",0);val played=usage.getLong("playedMs",0);val cached=usage.getLong("cacheHits",0)
  val setup=usage.getLong("connectionSetupMs",0);val waiting=usage.getLong("audioWaitMs",0)
  val began=SystemClock.elapsedRealtime()
  val owner="native-voice-response-qa-20261017-r01"
  fun await(label:String,limit:Long,predicate:()->Boolean){val end=SystemClock.elapsedRealtime()+limit;while(SystemClock.elapsedRealtime()<end){if(predicate())return;if(PocketSpeech.displayedProblem.isNotBlank())fail(PocketSpeech.displayedProblem);Thread.sleep(100)};assertTrue(label+" · "+PocketSpeech.status+" · "+PocketSpeech.displayedProblem,predicate())}
  try {
   if(originalVolume==0)audio.setStreamVolume(AudioManager.STREAM_MUSIC,(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)/2).coerceAtLeast(1),0)
   i.runOnMainSync{
    c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    PocketVoice.start(c,recordImmediately=false,inPlace=true)
   }
   await("Idle voice mode did not start",10000){PocketVoice.active&&PocketVoice.state=="Ready"}
   i.runOnMainSync{PocketSpeech.speakDisplayed(owner,"Native room voice controls are ready.","Voice response qualification")}
   await("Native renderer/player did not complete",90000){
    (usage.getLong("generated",0)>generated||usage.getLong("cacheHits",0)>cached)&&
     usage.getLong("playedMs",0)>played+250&&PocketSpeech.displayedOwner==null
   }
   assertTrue("Playback unexpectedly closed voice mode",PocketVoice.active)
   assertFalse(PocketVoice.captureBusy)
   File(c.filesDir,"voice-response-playback-qa-result.json").writeText(JSONObject()
    .put("elapsedMs",SystemClock.elapsedRealtime()-began).put("connectionSetupMs",usage.getLong("connectionSetupMs",0)-setup).put("audioWaitMs",usage.getLong("audioWaitMs",0)-waiting)
    .put("generatedChunks",usage.getLong("generated",0)-generated).put("cached",usage.getLong("cacheHits",0)-cached)
    .put("playedMs",usage.getLong("playedMs",0)-played).put("idleVoiceModeRetained",PocketVoice.active).toString())
  }finally{
   i.runOnMainSync{PocketSpeech.stopDisplayed(owner);PocketVoice.stop()}
   if(audio.getStreamVolume(AudioManager.STREAM_MUSIC)!=originalVolume)audio.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0)
  }
 }
}
