package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class ClientReplyRecoveryNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun await(b:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(b())return;Thread.sleep(50)};assertTrue("Delivery condition timed out",b())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun start(reset:Boolean=true,draft:String="Receipt test"){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  if(reset)context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread",draft).commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
 }
 private fun posts()=qa("state").getJSONArray("replies")
 @Test fun matchingUnknownReceiptRequiresExplicitRecovery(){
  start(draft="Unknown receipt test");qa("unknown-reply");main{Pocket.open("same-thread")};await{Pocket.detail!=null&&!PocketTranscript.updating}
  var completed=false;main{Pocket.reply("Unknown receipt test",onDone={completed=true})}
  await{!Pocket.sending&&PocketReplies.entries("same-thread").singleOrNull()?.state=="unknown"&&device.hasObject(By.text("Retry"))}
  assertFalse("Unknown receipt cleared draft callback",completed);assertEquals("Unknown receipt test",Pocket.prefs.getString("draft:same-thread",null))
  Thread.sleep(1000);assertEquals("Unknown receipt started another send",1,posts().length());assertEquals(0,qa("state").getJSONArray("recoveries").length())
  device.findObject(By.text("Retry")).click();assertEquals(0,qa("state").getJSONArray("recoveries").length())
  device.findObject(By.text("Check and retry")).click();await{PocketReplies.entries("same-thread").isEmpty()}
  assertEquals("Recovery posted original turn again",1,posts().length());val action=qa("state").getJSONArray("recoveries").getJSONObject(0)
  assertEquals(posts().getJSONObject(0).s("id"),action.s("id"));assertTrue(action.optBoolean("confirmUnknown"));assertFalse(Pocket.prefs.contains("draft:same-thread"))
 }
 @Test fun malformedAcknowledgementAndWrongThreadCannotEraseDraftOrResend(){
  start();qa("bad-reply");main{Pocket.open("same-thread",keyboard=true)}
  await{Pocket.detail!=null&&!PocketTranscript.updating&&device.hasObject(By.clazz("android.widget.EditText"))}
  var completed=false;main{Pocket.reply("Receipt test",onDone={completed=true})}
  await{!Pocket.sending&&PocketReplies.entries("same-thread").singleOrNull()?.state=="unconfirmed"}
  assertFalse("Malformed acknowledgement cleared UI callback",completed)
  assertEquals("Receipt test",Pocket.prefs.getString("draft:same-thread",null))
  val intent=PocketReplies.entries("same-thread").single();assertEquals(intent.id,posts().getJSONObject(0).s("id"))
  main{Pocket.reply("Receipt test")};Thread.sleep(1200);assertEquals("Unknown message automatically resent",1,posts().length())
  qa("wrong-receipt-thread");main{PocketReplies.check(intent)};await{intent.id !in PocketReplies.busy}
  assertEquals("Wrong-thread receipt erased draft","Receipt test",Pocket.prefs.getString("draft:same-thread",null))
  assertEquals(1,PocketReplies.entries("same-thread").size)
  device.findObject(By.clazz("android.widget.EditText")).text="Edited after send"
  await{Pocket.prefs.getString("draft:same-thread",null)=="Edited after send"}
  qa("release-receipt");main{PocketReplies.check(PocketReplies.entries("same-thread").single())};await{PocketReplies.entries("same-thread").isEmpty()}
  assertEquals("Reconciliation erased a newer draft","Edited after send",Pocket.prefs.getString("draft:same-thread",null))
  assertEquals("Edited after send",device.findObject(By.clazz("android.widget.EditText")).text)
  assertEquals("Receipt reconciliation submitted another turn",1,posts().length())
 }
 @Test fun explicitStopCancelsPreparedMessageBeforeDispatch(){
  start(draft="Cancelled prepared turn")
  val destination=requireNotNull(Pocket.captureEnvironment())
  val saved=ClientReplyIntent("synthetic-cancelled-id",Pocket.environmentId,backendOwner(destination.endpoint,destination.token),"same-thread","Cancelled prepared turn","auto","draft:same-thread")
  ClientReplyJournal({Pocket.prefs.getString("client-replies:v1","[]")!!},{Pocket.prefs.edit().putString("client-replies:v1",it).commit()}).put(saved)
  main{Pocket.open("same-thread");Pocket.interrupt()};await{!Pocket.sending&&!PocketTranscript.updating}
  Thread.sleep(1200);assertEquals("Stop failed to cancel undispatched turn",0,posts().length());assertTrue(PocketReplies.entries("same-thread").isEmpty())
  assertEquals("Stop erased editable text","Cancelled prepared turn",Pocket.prefs.getString("draft:same-thread",null))
 }
 @Test fun preparedButUndispatchedMessageContinuesOnce(){
  start(draft="Prepared continuation")
  val destination=requireNotNull(Pocket.captureEnvironment())
  val saved=ClientReplyIntent("synthetic-prepared-id",Pocket.environmentId,backendOwner(destination.endpoint,destination.token),"same-thread","Prepared continuation","auto","draft:same-thread")
  ClientReplyJournal({Pocket.prefs.getString("client-replies:v1","[]")!!},{Pocket.prefs.edit().putString("client-replies:v1",it).commit()}).put(saved)
  main{Pocket.open("same-thread")};await{posts().length()==1&&PocketReplies.entries("same-thread").isEmpty()&&!Pocket.sending}
  assertEquals(saved.id,posts().getJSONObject(0).s("id"));assertFalse(Pocket.prefs.contains("draft:same-thread"))
  main{PocketReplies.reconcile("same-thread")};Thread.sleep(1000);assertEquals("Prepared message submitted twice",1,posts().length())
 }
 @Test fun explicitRetryKeepsSavedIdentityAndRequiresConfirmation(){
  start(draft="Prepared turn")
  val destination=requireNotNull(Pocket.captureEnvironment())
  val saved=ClientReplyIntent("synthetic-unsent-id",Pocket.environmentId,backendOwner(destination.endpoint,destination.token),"same-thread","Prepared turn","auto","draft:same-thread",phase="dispatched")
  ClientReplyJournal({Pocket.prefs.getString("client-replies:v1","[]")!!},{Pocket.prefs.edit().putString("client-replies:v1",it).commit()}).put(saved)
  main{Pocket.open("same-thread")};await{Pocket.detail!=null&&!PocketTranscript.updating&&device.hasObject(By.text("Retry"))}
  assertEquals("Prepared turn sent without consent",0,posts().length())
  device.findObject(By.text("Retry")).click();assertTrue(device.wait(Until.hasObject(By.text("Check and retry this message?")),3000))
  device.findObject(By.text("Cancel")).click();assertTrue("Retry did not return after cancellation",device.wait(Until.hasObject(By.text("Retry")),3000));assertEquals(0,posts().length())
  device.findObject(By.text("Retry")).click();assertTrue("Retry confirmation did not open",device.wait(Until.hasObject(By.text("Check and retry")),3000));device.findObject(By.text("Check and retry")).click()
  await{posts().length()==1&&PocketReplies.entries("same-thread").isEmpty()&&!Pocket.sending}
  assertEquals("Retry invented a new identity",saved.id,posts().getJSONObject(0).s("id"))
  assertFalse(Pocket.prefs.contains("draft:same-thread"))
 }
 @Test fun identicalThreadOnAnotherEnvironmentCannotConsumeSavedReply(){
  start();qa("bad-reply");main{Pocket.open("same-thread")};await{Pocket.detail!=null&&!PocketTranscript.updating}
  main{Pocket.reply("Receipt test")};await{!Pocket.sending&&PocketReplies.entries("same-thread").isNotEmpty()};Thread.sleep(1200)
  val original=PocketReplies.entries("same-thread").single();val before=qa("state").getJSONArray("receiptReads").length()
  val p=Pocket.prefs
  p.edit().putString("environments:v1",JSONArray().put(JSONObject().put("id","workstation").put("name","Synthetic A")).put(JSONObject().put("id","remote-b").put("name","Synthetic B")).toString()).putString(Pocket.environmentKey("server","remote-b"),Pocket.base).putString(Pocket.environmentKey("token","remote-b"),Pocket.token).putString(Pocket.environmentKey("draft:same-thread","remote-b"),"Other environment draft").commit()
  main{Pocket.activateEnvironment("remote-b");Pocket.open("same-thread",keyboard=true)};await{Pocket.detail!=null&&!PocketTranscript.updating}
  assertTrue("Other environment exposed saved reply",PocketReplies.entries("same-thread").isEmpty())
  assertEquals("Other environment reconciled original receipt",before,qa("state").getJSONArray("receiptReads").length())
  assertEquals("Other environment draft",p.getString(Pocket.environmentKey("draft:same-thread","remote-b"),null))
  main{Pocket.activateEnvironment("workstation");Pocket.open("same-thread")};await{PocketReplies.entries("same-thread").any{it.id==original.id}&&!PocketTranscript.updating}
  qa("release-receipt");main{PocketReplies.check(original)};await{PocketReplies.entries("same-thread").isEmpty()}
  assertEquals("Other environment draft",p.getString(Pocket.environmentKey("draft:same-thread","remote-b"),null))
  assertEquals(1,posts().length())
 }
 @Test fun recoveryControlsRemainReachableAtLargeTextAndRemoveDoesNotResend(){
  val draft="Readability test "+"A deliberately long saved message. ".repeat(70)
  start(draft=draft);qa("bad-reply");main{Pocket.open("same-thread")};await{Pocket.detail!=null&&!PocketTranscript.updating}
  main{Pocket.reply(draft)};await{!Pocket.sending&&PocketReplies.entries("same-thread").isNotEmpty()};Thread.sleep(1100)
  val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
  try{
   device.executeShellCommand("settings put system font_scale 2.0");await{context.resources.configuration.fontScale>=1.9f&&PocketReplies.busy.isEmpty()}
   for(label in listOf("Check status","Retry","Edit draft","Remove saved copy")){
    assertTrue(label+" unavailable at large text",device.wait(Until.hasObject(By.text(label)),3000))
    var control:UiObject2?=device.findObject(By.text(label));repeat(6){if(control?.isClickable==false)control=control?.parent}
    assertTrue(label+" lacks touch target",control?.isClickable==true)
    assertTrue(label+" touch target clipped or too small",control!!.visibleBounds.height()>=48*context.resources.displayMetrics.density-2)
   }
   assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"reply-recovery-large.png")))
   device.findObject(By.text("Remove saved copy")).click();await{PocketReplies.entries("same-thread").isEmpty()}
   assertFalse("Remove left original local draft",Pocket.prefs.contains("draft:same-thread"))
   assertEquals("Remove resent the message",1,posts().length())
  }finally{device.executeShellCommand("settings put system font_scale "+font)}
 }
 @Test fun seedInterruptedDelivery(){
  start(draft="Interrupted turn");qa("hold-ack");main{Pocket.open("same-thread")};await{Pocket.detail!=null&&!PocketTranscript.updating}
  main{Pocket.reply("Interrupted turn")};await{posts().length()==1&&Pocket.sending}
  val saved=PocketReplies.entries("same-thread").single();assertEquals("dispatched",saved.phase)
  Pocket.prefs.edit().putString("diagnostic:interrupted-id",saved.id).putInt("diagnostic:seed-pid",android.os.Process.myPid()).commit()
  assertEquals("Interrupted turn",Pocket.prefs.getString("draft:same-thread",null))
 }
 @Test fun restoreInterruptedDeliveryWithoutResubmission(){
  start(reset=false);assertNotEquals("Test did not replace application process",Pocket.prefs.getInt("diagnostic:seed-pid",0),android.os.Process.myPid())
  val id=Pocket.prefs.getString("diagnostic:interrupted-id",null);assertNotNull(id)
  main{Pocket.open("same-thread",keyboard=true)}
  await{PocketReplies.entries("same-thread").isEmpty()&&!PocketTranscript.updating&&device.hasObject(By.clazz("android.widget.EditText"))}
  assertFalse("Accepted interrupted turn left old draft",Pocket.prefs.contains("draft:same-thread"))
  assertEquals("",device.findObject(By.clazz("android.widget.EditText")).text)
  assertEquals("Process restart resubmitted turn",1,posts().length())
  assertEquals(id,posts().getJSONObject(0).s("id"))
  assertTrue("Original receipt was not read",qa("state").getJSONArray("receiptReads").let{a->(0 until a.length()).any{a.getString(it)==id}})
 }
}
