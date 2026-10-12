package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class TranscriptRefreshNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val context get()=i.targetContext
 private val device get()=UiDevice.getInstance(i)
 private fun main(b:()->Unit)=i.runOnMainSync(b)
 private fun await(b:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(b())return;Thread.sleep(50)};assertTrue("Freshness condition timed out",b())}
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/"+path,JSONObject())}
 private fun has(n:Int)=PocketTranscript.rows.any{it.s("text")=="Fresh response "+n}
 private fun twoChats(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  // These transcript tests do not test Android's first-install permission UI.
  // Seed permissions only on the asserted disposable emulator, before launch.
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","First saved draft").putString("draft:second-thread","Second saved draft").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  qa("show-second");main{Pocket.refresh()};await{Pocket.tasks.any{it.id=="second-thread"}}
 }
 @Test fun historyEvidenceRepairsDeferredPositionOnce(){
  twoChats();qa("await-position-history");main{Pocket.open("same-thread")}
  await{PocketTranscript.rows.any{it.s("id")=="order/answer"}&&!PocketTranscript.loading}
  val initialReads=qa("state").getJSONArray("reads").length()
  val event=JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","order").put("version",1000).put("row",JSONObject().put("id","order/live").put("itemId","live").put("turnId","order").put("clientId","same-send").put("kind","user").put("text","Deferred input").put("version",1000))
  main{Pocket.event(event)}
  await{qa("state").getJSONArray("positions").length()==2&&!PocketTranscript.updating}
  assertEquals("Ordering repair added reads beyond the normal new-input refresh",initialReads+1,qa("state").getJSONArray("reads").length())
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue(ids.indexOf("order/client-user/same-send")<ids.indexOf("order/answer"))
  assertEquals(1,PocketTranscript.rows.count{it.s("kind")=="user"&&it.s("clientId")=="same-send"})
  val inputs=qa("state").getJSONArray("positions");assertEquals("same-send",inputs.getJSONObject(1).getString("clientId"))
  main{Pocket.refreshDetail()};await{!PocketTranscript.loading};Thread.sleep(600)
  assertEquals("Successful evidence was needlessly re-requested",2,qa("state").getJSONArray("positions").length())
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
 @Test fun canonicalInputPositionSurvivesProviderChangeDuringLookup(){
  twoChats();qa("anchored");main{Pocket.open("same-thread")}
  await{PocketTranscript.rows.any{it.s("id")=="order/answer"}&&!PocketTranscript.loading}
  fun event(item:String,version:Int)=JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","order").put("version",version).put("row",JSONObject().put("id","order/"+item).put("itemId",item).put("turnId","order").put("clientId","same-send").put("kind","user").put("text","Late same-send input").put("version",version))
  main{Pocket.event(event("live-uuid",1000))}
  await{qa("state").getJSONArray("positions").length()==1}
  main{Pocket.event(event("rebuilt-item",1001))}
  qa("release-position")
  await{qa("state").getJSONArray("positions").length()==2&&!PocketTranscript.updating}
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Canonical input position lookup could not address the displayed row",ids.indexOf("order/client-user/same-send")<ids.indexOf("order/answer"))
  assertEquals(1,PocketTranscript.rows.count{it.s("kind")=="user"&&it.s("clientId")=="same-send"})
  assertEquals("order/client-user/same-send",transcriptDisplayAnchor(PocketTranscript.rows,"order/live-uuid"))
  assertEquals("order/client-user/same-send",transcriptDisplayAnchor(PocketTranscript.rows,"order/rebuilt-item"))
  val before=qa("state");val reads=before.getJSONArray("reads").length()
  repeat(3){main{Pocket.event(event("rebuilt-item",1001))}}
  Thread.sleep(800)
  val after=qa("state")
  assertEquals("Repeated input event restarted canonical reads",reads,after.getJSONArray("reads").length())
  assertEquals("Repeated input event restarted position lookup",2,after.getJSONArray("positions").length())
  assertEquals("Read repair submitted work",0,after.getJSONArray("replies").length())
 }
 @Test fun providerReconstructionReconcilesLiveHistoryAndLegacyCache(){
  twoChats();qa("message-identity");qa("hold-history")
  val scope=PocketTranscript.scopeKey()
  val old=org.json.JSONArray()
  (listOf("uuid")+(1..99).map{"item-"+it}).forEach{item->old.put(JSONObject().put("id","identity/"+item).put("itemId",item).put("turnId","identity").put("clientId","send-one").put("kind","user").put("text","Same deliberate text"))}
  val snapshot=JSONObject().put("schema",1).put("scope",scope).put("id","same-thread").put("rows",old).put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",false).put("position",JSONObject().put("row","identity/item-32").put("offset",17).put("follow",false))
  assertTrue(TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).write(scope,"same-thread",snapshot.toString()))
  val restoredBefore=PocketTranscript.cacheRestoreRevision
  main{Pocket.open("same-thread")};await{PocketTranscript.cacheRestoreRevision>restoredBefore&&PocketTranscript.loading}
  assertEquals("Legacy reading anchor was lost during duplicate collapse","identity/client-user/send-one",PocketTranscript.readPosition?.rowId)
  assertEquals(17,PocketTranscript.readPosition?.offset)
  assertEquals(1,PocketTranscript.rows.count{it.s("kind")=="user"})
  qa("release-history");await{has(1)&&!PocketTranscript.updating}
  fun users()=PocketTranscript.rows.filter{it.s("kind")=="user"}
  assertEquals("Two distinct sends with identical text must survive",2,users().size)
  assertEquals(setOf("send-one","send-two"),users().map{it.s("clientId")}.toSet())
  await{device.findObjects(By.text("Same deliberate text")).size==2}
  val firstId=users().first().s("id")
  main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","identity").put("row",JSONObject().put("id","identity/new-live-id").put("itemId","new-live-id").put("turnId","identity").put("clientId","send-one").put("kind","user").put("text","Same deliberate text").put("version",1000)))}
  assertEquals(2,users().size);assertEquals(firstId,users().first().s("id"))
  await{device.findObjects(By.text("Same deliberate text")).size==2}
  main{Pocket.open("second-thread")};await{PocketTranscript.rows.any{it.s("text")=="Other response 1"}}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  assertEquals(2,users().size)
  assertEquals("First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals("Identity reconciliation must never submit work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun legacyCachedGapIsRepairedWithoutLoadingEarlierManually(){
  twoChats();qa("same-turn-gap");qa("head-only-update")
  val scope=PocketTranscript.scopeKey()
  val oldRows=org.json.JSONArray("""[{"id":"synthetic-turn/header","kind":"turn","turnId":"synthetic-turn"},{"id":"gap-user","kind":"user","turnId":"synthetic-turn","text":"Shared turn request"},{"id":"gap-old-answer","kind":"message","turnId":"synthetic-turn","text":"Fresh response 1"},{"id":"gap-new-tool","kind":"activity","turnId":"synthetic-turn","text":"New tool action"}]""")
  val snapshot=JSONObject().put("schema",1).put("scope",scope).put("id","same-thread").put("rows",oldRows).put("detail",JSONObject().put("thread",JSONObject().put("id","same-thread"))).put("earlier",false)
  assertTrue(TranscriptDiskCache(java.io.File(context.noBackupFilesDir,"transcript-cache-v1")).write(scope,"same-thread",snapshot.toString()))
  main{Pocket.open("same-thread")};await{has(2)&&!PocketTranscript.updating}
  assertEquals(listOf("gap-user","gap-old-answer","gap-new-answer","gap-new-tool"),PocketTranscript.rows.filter{it.s("kind")!="turn"}.map{it.s("id")})
  assertEquals("First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
 @Test fun sameTurnHeaderDoesNotHideAnIntermediateAnswer(){
  twoChats();qa("same-turn-gap");main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("head-only-update");main{Pocket.refresh()}
  await{has(2)&&!PocketTranscript.updating}
  assertEquals(listOf("gap-user","gap-old-answer","gap-new-answer","gap-new-tool"),PocketTranscript.rows.filter{it.s("kind")!="turn"}.map{it.s("id")})
  assertEquals("First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
 @Test fun inlinePlaybackVolumePreservesMuteAndTypingDuringCapture(){
  twoChats();main{Pocket.open("same-thread",keyboard=true)}
  await{has(1)&&device.hasObject(By.clazz("android.widget.EditText"))&&device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val audio=context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
  val previous=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
  try{
   audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,0,0);Thread.sleep(600)
   device.findObject(By.clazz("android.widget.EditText")).text="Playback volume keeps my draft"
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP);await{PocketVoice.state=="Listening"}
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
   assertEquals("Capture keys changed intentional playback mute",0,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
   val slider=device.findObject(By.desc("Speech volume")).visibleBounds
   device.click(slider.left+slider.width()*3/4,slider.centerY())
   await{audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)>0}
   assertTrue("Volume adjustment dismissed typing",device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
   assertEquals("Volume adjustment erased draft","Playback volume keeps my draft",prefs.getString("draft:same-thread",null))
   assertEquals("Volume/capture controls submitted work",0,qa("state").getJSONArray("replies").length())
  }finally{main{PocketVoice.stop()};audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,previous,0)}
 }
 @Test fun coordinatorControlsKeepTheirTargetAndDraftInLargeTextImmersion(){
  twoChats();val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
  try{
   main{Pocket.closeTask();PocketImmersion.setEnabled(true);PocketCoordinator.open()}
   await{PocketCoordinator.visible&&!PocketCoordinator.historyLoading}
   device.executeShellCommand("settings put system font_scale 2.0");await{context.resources.configuration.fontScale>=1.9f}
   for(label in listOf("Registra","Tastiera","Impostazioni"))assertTrue("Translated control missing: "+label,device.wait(Until.hasObject(By.text(label)),3000))
   Thread.sleep(300);assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"bottom-coordinator-italian-large.png")))
   device.findObject(By.desc(PocketImmersion.label("Start recording"))).click();await{PocketVoice.state=="Listening"&&PocketVoice.targetThread==null}
   assertTrue("Capture left coordinator",PocketCoordinator.visible)
   device.wait(Until.hasObject(By.desc(PocketImmersion.label("Stop and send recording"))),3000)
   device.findObject(By.desc(PocketImmersion.label("Stop and send recording"))).click();await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
   main{PocketVoice.stop();PocketCoordinator.keyboard(true)}
   await{device.hasObject(By.clazz("android.widget.EditText"))&&device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
   device.findObject(By.clazz("android.widget.EditText")).text="Coordinator typing stays separate"
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP);await{PocketVoice.state=="Listening"&&PocketVoice.targetThread==null}
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
   assertEquals("Coordinator capture erased its draft","Coordinator typing stays separate",prefs.getString("coordinatorDraft",null))
   assertTrue("Coordinator voice hid keyboard",device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
   assertTrue("Volume missing in coordinator",device.hasObject(By.desc("Speech volume")))
   assertEquals("Coordinator UI test started work",0,qa("state").getJSONArray("replies").length())
  }finally{main{PocketVoice.stop();PocketCoordinator.close();PocketImmersion.setEnabled(false)};device.executeShellCommand("settings put system font_scale "+font)}
 }
 @Test fun bottomControlsRemainReachableAtLargeTextAndPreserveTyping(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
  fun reachable(selector:androidx.test.uiautomator.BySelector):androidx.test.uiautomator.UiObject2{
   assertTrue("Bottom control missing: "+selector,device.wait(Until.hasObject(selector),3000))
   var node=device.findObject(selector);repeat(6){if(!node.isClickable&&node.parent!=null)node=node.parent}
   assertTrue("Control not tappable",node.isClickable)
   assertTrue("Control clipped below48dp: "+node.visibleBounds,node.visibleBounds.height()>=48*context.resources.displayMetrics.density-2)
   return node
  }
  try{
   for(scale in listOf(1.0f,1.3f,2.0f)){
    device.executeShellCommand("settings put system font_scale "+scale);await{kotlin.math.abs(context.resources.configuration.fontScale-scale)<.05f&&!PocketTranscript.updating}
    reachable(By.text("Record"));reachable(By.text("Keyboard"));reachable(By.desc("Settings, app updates, usage and activity"))
    assertTrue("Volume disappeared",device.hasObject(By.desc("Speech volume")))
    await{device.hasObject(By.text("Settings"))&&device.hasObject(By.text("↻ —"))};Thread.sleep(300)
    assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"bottom-controls-"+scale+".png")))
   }
   reachable(By.text("Record")).click();await{PocketVoice.state=="Listening"}
   assertTrue("Capture feedback absent",device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Recording .*"))),2000))
   assertTrue("Recording status incorrectly exposed as disabled",device.findObject(By.text(java.util.regex.Pattern.compile("Recording .*"))).isEnabled)
   reachable(By.desc("Stop and send recording")).click();await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
   assertFalse("Silent emulator recording submitted work",prefs.contains("voicePendingId"))
   main{PocketVoice.stop()}
   reachable(By.desc("Settings, app updates, usage and activity")).click()
   assertTrue("Updates inaccessible from dock",device.wait(Until.hasObject(By.text("App updates")),3000))
   device.pressBack();await{AppWindowVisibility.focused&&Pocket.selected=="same-thread"}
   reachable(By.text("Keyboard")).click();await{device.hasObject(By.clazz("android.widget.EditText"))&&device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
   device.findObject(By.clazz("android.widget.EditText")).text="Large text draft stays here"
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP);await{PocketVoice.state=="Listening"}
   device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN);await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
   assertEquals("Hardware capture lost large-text draft","Large text draft stays here",prefs.getString("draft:same-thread",null))
   assertTrue("Voice hid the IME",device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
   assertTrue("Volume inaccessible with IME",device.hasObject(By.desc("Speech volume")))
   val collapsed=device.findObject(By.text(PocketVoice.problem)).visibleBounds.height()
   device.findObject(By.text(PocketVoice.problem)).click()
   await{device.findObject(By.text(PocketVoice.problem)).visibleBounds.height()>collapsed}
   assertTrue("Inline error help hid IME",device.executeShellCommand("dumpsys input_method").contains("mInputShown=true"))
   assertTrue(device.takeScreenshot(java.io.File(context.cacheDir,"bottom-controls-ime-large.png")))
   assertEquals("Layout/capture test submitted work",0,qa("state").getJSONArray("replies").length())
  }finally{main{PocketVoice.stop()};device.executeShellCommand("settings put system font_scale "+font)}
 }
 @Test fun liveActivityRefreshesUnchangedStatusWithoutPollingOrLosingDraft(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("activity-only");await{has(2)&&!PocketTranscript.updating}
  assertEquals("Refresh lost the saved draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  val reads=qa("state").getJSONArray("reads").length()
  repeat(4){qa("repeat-activity")};Thread.sleep(1200)
  assertEquals("Repeated work events polled history",reads,qa("state").getJSONArray("reads").length())
  assertEquals("Refresh submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun prolongedHistoryRecoveryCannotFreezeOrFollowNavigation(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  val baseline=qa("state").getJSONArray("reads").length()
  qa("silent");qa("fail-read-burst");main{Pocket.refreshDetail()}
  await{qa("state").getJSONArray("reads").length()==baseline+4&&PocketTranscript.updating}
  assertTrue("Outage lost cached history",has(1));assertTrue("Outage hid actionable error",Pocket.error.isNotBlank())
  await{qa("state").getJSONArray("reads").length()>=baseline+5&&has(2)&&!PocketTranscript.updating}
  qa("fail-read");main{Pocket.refreshDetail()};await{Pocket.error.contains("Synthetic history refresh unavailable")&&PocketTranscript.updating}
  main{Pocket.open("second-thread")};await{PocketTranscript.rows.any{it.s("text")=="Other response 1"}&&!PocketTranscript.updating}
  val afterNavigation=qa("state").getJSONArray("reads").objects().count{it.s("threadId")!="second-thread"}
  Thread.sleep(1500)
  assertEquals("Old conversation retried after navigation",afterNavigation,qa("state").getJSONArray("reads").objects().count{it.s("threadId")!="second-thread"})
  assertEquals("Retry mutated work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun transientHistoryFailureRecoversWithoutAnotherEvent(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("silent");qa("fail-read");main{Pocket.refreshDetail()}
  await{Pocket.error.contains("Synthetic history refresh unavailable")&&PocketTranscript.updating}
  assertTrue("Cached history discarded on failure",has(1))
  assertTrue("Loading indicator missing",device.wait(Until.hasObject(By.text("Updating messages")),3000))
  await{has(2)&&!PocketTranscript.updating&&Pocket.error.isBlank()}
  qa("silent");qa("malformed-read");main{Pocket.refreshDetail()}
  await{Pocket.error.contains("Messages were not returned")&&PocketTranscript.updating}
  assertTrue("Malformed response erased cached history",has(2))
  await{has(3)&&!PocketTranscript.updating&&Pocket.error.isBlank()}
  qa("silent");qa("fail-read-permanent");main{Pocket.refreshDetail()}
  await{Pocket.error.contains("Synthetic history permission rejected")&&!PocketTranscript.updating}
  val reads=qa("state").getJSONArray("reads").length();Thread.sleep(2500)
  assertEquals("Rejected permission was polled",reads,qa("state").getJSONArray("reads").length())
  assertTrue("Permission failure hid local history",has(3))
  assertEquals("Recovery submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun controlsAndDialogDoNotConsumeUpdatesButImeStillPresentsLatest(){
  twoChats();qa("visibility");main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating&&AppWindowVisibility.focused}
  fun acknowledged(n:Int)=qa("state").getJSONArray("acknowledgements").objects().any{it.optInt("throughId")==n}
  await{acknowledged(1)}
  device.findObject(By.descContains("; conversation controls")).click();await{!AppWindowVisibility.focused}
  qa("silent");main{Pocket.refreshDetail()};await{has(2)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Controls popup consumed covered update",acknowledged(2))
  device.pressBack();await{AppWindowVisibility.focused&&acknowledged(2)}
  device.findObject(By.descContains("; conversation controls")).click()
  repeat(10){if(!device.hasObject(By.text("Rename")))device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN,.8f)}
  assertTrue("Rename action unavailable",device.hasObject(By.text("Rename")))
  device.findObject(By.text("Rename")).click();await{device.hasObject(By.text("Rename conversation"))&&!AppWindowVisibility.focused}
  qa("silent");main{Pocket.refreshDetail()};await{has(3)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Rename dialog consumed covered update",acknowledged(3))
  device.findObject(By.text("Cancel")).click();await{AppWindowVisibility.focused&&acknowledged(3)}
  device.pressBack();await{Pocket.selected==null}
  assertTrue("Keyboard card region unavailable",device.wait(Until.hasObject(By.desc("Open Keyboard for Synthetic freshness work")),3000))
  device.findObject(By.desc("Open Keyboard for Synthetic freshness work")).click()
  await{has(3)&&AppWindowVisibility.focused&&device.hasObject(By.clazz("android.widget.EditText"))}
  await{device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")}
  device.findObject(By.clazz("android.widget.EditText")).text="Keep this IME draft"
  qa("silent");main{Pocket.refreshDetail()};await{has(4)&&!PocketTranscript.updating&&acknowledged(4)}
  assertTrue("IME incorrectly blocked main-window presentation",AppWindowVisibility.focused)
  assertEquals("Focus transitions lost draft","Keep this IME draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString(Pocket.key("draft:same-thread"),null))
  assertEquals("Read check mutated work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun delayedReplyFailureStaysWithItsSourceAndResolvesByReceipt(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("fail-reply");var completed=false
  main{Pocket.reply("Synthetic held reply",onDone={completed=true})}
  await{qa("state").getJSONArray("replies").length()==1}
  main{Pocket.open("second-thread")};await{PocketTranscript.rows.any{it.s("text")=="Other response 1"}&&!PocketTranscript.updating}
  qa("release-reply");await{!Pocket.sending&&Pocket.replyFailure("same-thread")!=null}
  assertFalse("Failed transport cleared the source draft",completed)
  assertEquals("Delayed failure contaminated selected chat","",Pocket.error)
  assertNull("Delayed failure attached to wrong thread",Pocket.replyFailure("second-thread"))
  assertFalse("Old chat failure displayed in new chat",device.hasObject(By.textContains("Synthetic delayed reply failure")))
  val sourceReceipt=qa("state").getJSONArray("replies").getJSONObject(0).s("id")
  main{Pocket.event(JSONObject().put("type","reply").put("threadId","second-thread").put("id","second-failure").put("state","unknown").put("error","Synthetic second chat failure"))}
  await{device.hasObject(By.text("Synthetic second chat failure"))}
  main{Pocket.event(JSONObject().put("type","reply").put("threadId","second-thread").put("id","older-accepted").put("state","accepted"))}
  Thread.sleep(600);assertEquals("Older accepted receipt erased a newer failure","second-failure",Pocket.replyFailure("second-thread")?.id)
  main{Pocket.event(JSONObject().put("type","reply").put("threadId","second-thread").put("id","second-failure").put("state","accepted"))}
  await{Pocket.replyFailure("second-thread")==null&&!device.hasObject(By.text("Synthetic second chat failure"))}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating&&device.hasObject(By.textContains("Synthetic delayed reply failure"))}
  assertEquals("History reload lost the source delivery ID",sourceReceipt,Pocket.replyFailure("same-thread")?.id)
  assertEquals("Navigation lost unsent draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  val font=device.executeShellCommand("settings get system font_scale").trim().toFloatOrNull()?:1f
  try{
   device.executeShellCommand("settings put system font_scale 2.0")
   await{context.resources.configuration.fontScale>=1.9f}
   assertTrue("Remove missing at large text",device.wait(Until.hasObject(By.text("Remove saved copy")),5000))
   Thread.sleep(600)
   val dismiss=device.findObject(By.text("Remove saved copy"))
   var control:androidx.test.uiautomator.UiObject2?=dismiss
   while(control!=null&&!control.isClickable)control=control.parent
   assertNotNull("Dismiss has no clickable target",control)
   val bounds=control!!.visibleBounds
   val minimum=(48*context.resources.displayMetrics.density).toInt()
   assertTrue("Large-text dismiss target clipped: $bounds",bounds.width()>=minimum&&bounds.height()>=minimum)
   dismiss.click();await{Pocket.replyFailure("same-thread")==null}
  }finally{device.executeShellCommand("settings put system font_scale $font")}

  assertEquals("Dismiss replayed the uncertain send",1,qa("state").getJSONArray("replies").length())
  main{Pocket.closeTask()}
  fun popupReply(){
   device.findObject(By.desc("Settings, app updates, usage and activity")).click()
   repeat(10){if(!device.hasObject(By.desc("Reply to Other synthetic work")))device.findObjects(By.scrollable(true)).lastOrNull()?.scroll(Direction.DOWN,.8f)}
   assertTrue("Popup target reply action unavailable",device.hasObject(By.desc("Reply to Other synthetic work")))
   device.findObject(By.desc("Reply to Other synthetic work")).click()
   assertTrue("Popup text input unavailable",device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000))
  }
  popupReply();device.findObject(By.clazz("android.widget.EditText")).text="Preserve this popup draft"
  qa("fail-reply");device.findObject(By.text("Send")).click();await{qa("state").getJSONArray("replies").length()==2}
  qa("release-reply");await{!Pocket.sending&&device.hasObject(By.textContains("Synthetic delayed reply failure"))}
  assertEquals("Popup reply routed incorrectly","second-thread",qa("state").getJSONArray("replies").getJSONObject(1).s("threadId"))
  assertEquals("Popup failure contaminated global state","",Pocket.error)
  assertEquals("Popup failure discarded editable draft","Preserve this popup draft",device.findObject(By.clazz("android.widget.EditText")).text)
  device.findObject(By.text("Close")).click();popupReply()
  assertEquals("Closing popup lost failed draft","Preserve this popup draft",device.findObject(By.clazz("android.widget.EditText")).text)
  assertEquals("Reopening popup replayed send",2,qa("state").getJSONArray("replies").length())
  device.findObject(By.clazz("android.widget.EditText")).text=""
  assertEquals("Draft cannot be erased","",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString(Pocket.key("popupDraft:second-thread"),null))

 }
 @Test fun refreshedHistoryIsNotReadUntilLatestIsActuallyPresented(){
  twoChats();qa("visibility");main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  fun acknowledged(n:Int)=qa("state").getJSONArray("acknowledgements").objects().any{it.optInt("throughId")==n}
  await{acknowledged(1)}
  assertEquals("Initial catch-up not acknowledged once",1,qa("state").getJSONArray("acknowledgements").objects().count{it.s("token")=="capture-1"})
  repeat(6){device.swipe(device.displayWidth/2,device.displayHeight/4,device.displayWidth/2,device.displayHeight*3/4,25)}
  assertFalse("Did not leave latest viewport",device.hasObject(By.text("Fresh response 1")))
  qa("silent");main{Pocket.refreshDetail()};await{has(2)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Fetch consumed unseen latest update while reading history",acknowledged(2))
  val latest=device.findObject(By.text("Latest"));assertNotNull("Return to latest action unavailable",latest);latest.click()
  await{acknowledged(2)}
  val important=device.findObject(By.text("Important"));assertNotNull("Important filter unavailable",important);important.click()
  qa("silent");main{Pocket.refreshDetail()};await{has(3)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Filtered presentation consumed full-conversation catch-up",acknowledged(3))
  device.findObject(By.text("Important")).click();await{acknowledged(3)}
  main{BlackoutVisibility.active=true}
  qa("silent");main{Pocket.refreshDetail()};await{has(4)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Blackout consumed unseen work",acknowledged(4))
  main{BlackoutVisibility.active=false};await{acknowledged(4)}
  device.pressHome();qa("silent");main{Pocket.refreshDetail()};await{has(5)&&!PocketTranscript.updating};Thread.sleep(1000)
  assertFalse("Background conversation consumed unseen work",acknowledged(5))
  main{context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))};await{acknowledged(5)}
  assertEquals("Live refresh repeatedly acknowledged catch-up",1,qa("state").getJSONArray("acknowledgements").objects().count{it.has("token")})
 }
 @Test fun switchingWhileOldRefreshIsBlockedDoesNotLoseNewChatUpdates(){
  twoChats()
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance");await{qa("state").getJSONArray("reads").objects().any{it.s("threadId")!="second-thread"&&it.optInt("revision")==2}}
  main{Pocket.open("second-thread")}
  await{PocketTranscript.rows.any{it.s("text")=="Other response 1"}&&!PocketTranscript.loading}
  qa("advance-second")
  await{Pocket.tasks.any{it.id=="second-thread"&&it.preview=="Other response 2"}&&PocketTranscript.updating}
  assertTrue("New chat cache disappeared",PocketTranscript.rows.any{it.s("text")=="Other response 1"})
  await{PocketTranscript.rows.any{it.s("text")=="Other response 2"}&&!PocketTranscript.updating}
  qa("release");Thread.sleep(500)
  assertEquals("Old refresh changed selected chat","second-thread",Pocket.selected)
  assertEquals("Old response contaminated current chat",listOf("other/message"),PocketTranscript.rows.map{it.s("id")})
  main{Pocket.reply("Reply after switching")};await{qa("state").getJSONArray("replies").length()==1&&!Pocket.sending}
  assertEquals("Reply routed to old chat","second-thread",qa("state").getJSONArray("replies").getJSONObject(0).s("threadId"))
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  assertEquals("First draft lost","First saved draft",prefs.getString("draft:same-thread",null))
  assertEquals("Second draft lost","Second saved draft",prefs.getString("draft:second-thread",null))
  main{Pocket.closeTask();Pocket.open("same-thread")};await{has(2)&&!PocketTranscript.updating}
  assertTrue("New chat rows leaked on reopen",PocketTranscript.rows.none{it.s("id")=="other/message"})
  main{Pocket.closeTask()}
  assertTrue("Second chat touch region not identifiable",device.wait(Until.hasObject(By.desc("Open Chat for Other synthetic work")),3000))
  device.findObject(By.desc("Open Chat for Other synthetic work")).click()
  await{Pocket.selected=="second-thread"&&PocketTranscript.rows.any{it.s("text")=="Other response 2"}&&!PocketTranscript.updating}
  device.pressBack();await{Pocket.selected==null}
  assertTrue("First chat touch region not identifiable",device.wait(Until.hasObject(By.desc("Open Chat for Synthetic freshness work")),3000))
  device.findObject(By.desc("Open Chat for Synthetic freshness work")).click()
  await{Pocket.selected=="same-thread"&&has(2)&&!PocketTranscript.updating}
  assertEquals("Card navigation lost saved draft","First saved draft",prefs.getString("draft:same-thread",null))
 }
 @Test fun backgroundSessionEventsDoNotReloadCurrentTranscript(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  val before=qa("state").getJSONArray("reads").length()
  main{repeat(10){n->
   Pocket.event(JSONObject().put("type","timeline").put("threadId","second-thread").put("turnId","background-turn").put("turn",JSONObject().put("status","completed")))
   Pocket.event(JSONObject().put("type","codex").put("event",JSONObject().put("method","turn/completed").put("params",JSONObject().put("threadId","second-thread"))))
   Pocket.event(JSONObject().put("type","reply").put("threadId","second-thread").put("id","background-$n").put("state","accepted"))
  }}
  Thread.sleep(2500)
  assertEquals("Background work reloaded the current transcript",before,qa("state").getJSONArray("reads").length())
  assertFalse("Background activity left Updating messages",PocketTranscript.updating)
  qa("silent")
  main{Pocket.event(JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","selected-turn").put("turn",JSONObject().put("status","completed")))}
  await{has(2)&&!PocketTranscript.updating}
  assertEquals("Selected turn event did not trigger one read",before+1,qa("state").getJSONArray("reads").length())
 }
 @Test fun backendSwitchPausesHiddenReadsAndRestoresCodexSelection(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("advance");await{qa("state").getJSONArray("reads").objects().any{it.optInt("revision")==2}}
  main{Pocket.scheduleRefresh();BackendNavigation.select("losangelex")}
  await{!PocketTranscript.loading&&!PocketTranscript.updating}
  assertEquals("Backend switch lost Codex selection","same-thread",Pocket.selected)
  assertTrue("Backend switch hid retained cache",has(1))
  qa("release");Thread.sleep(1000)
  val count=qa("state").getJSONArray("reads").length()
  assertEquals("Cancelled read restarted behind other backend",2,count)
  main{Pocket.refreshDetail();Pocket.scheduleRefresh()};Thread.sleep(600)
  assertEquals("Hidden Codex view fetched history",count,qa("state").getJSONArray("reads").length())
  main{BackendNavigation.select("codex")};await{has(2)&&!PocketTranscript.updating}
  assertEquals("Returning to Codex changed selection","same-thread",Pocket.selected)
  assertEquals("Backend switch lost draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
 }
 @Test fun previewDuringScheduledRecentReadIsNotLost(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep my draft").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance")
  await{qa("state").getJSONArray("reads").objects().any{it.optInt("revision")==2}}
  qa("advance");await{Pocket.tasks.first().preview=="Fresh response 3"}
  assertTrue("Cached messages hidden during in-flight refresh",has(1))
  qa("release")
  await{has(3)&&!PocketTranscript.updating}
  assertEquals("Draft lost during coalesced refresh","Keep my draft",prefs.getString("draft:same-thread",null))
  val reads=qa("state").getJSONArray("reads").length()
  qa("repeat");Thread.sleep(1000)
  assertEquals("Unchanged preview caused repeated reads",reads,qa("state").getJSONArray("reads").length())
 }
 @Test fun pendingQuestionJumpUsesCommittedUnfilteredRows(){
  twoChats();qa("pending-navigation");main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  assertTrue("Pending question initially missing",device.wait(Until.hasObject(By.text("Which synthetic review should continue?")),3000))
  device.findObject(By.text("Important")).click();Thread.sleep(400)
  device.findObject(By.text("Needs you")).click()
  assertTrue("Pending navigation action missing",device.wait(Until.hasObject(By.text("Answer pending question")),2000))
  device.findObject(By.text("Answer pending question")).click()
  assertTrue("Pending navigation scrolled to a filtered-list index",device.wait(Until.hasObject(By.text("Which synthetic review should continue?")),3000))
  assertTrue("Question skip not immediately reachable",device.hasObject(By.text("Skip")))
  assertEquals("Pending navigation erased draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals("Pending navigation submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun quietAuthenticatedReconnectRestoresMessagesWithoutFalseDisconnect(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  val connections=qa("state").getInt("connections")
  qa("quiet-reconnect")
  await{qa("state").getInt("connections")>connections&&has(2)&&!PocketTranscript.updating}
  // Wait beyond the old disconnect timer, with no application status/event frame.
  Thread.sleep(9000)
  assertTrue("Authenticated live socket falsely reported disconnected",Pocket.connected)
  assertFalse("Healthy quiet reconnect kept outage warning",PocketLive.outageVisible)
  qa("live-outage");await{!Pocket.connected&&PocketLive.outageVisible}
  assertTrue("Genuine outage omitted connection explanation",Pocket.connectionError.isNotBlank())
  qa("live-recover");await{Pocket.connected&&!PocketLive.outageVisible&&has(3)&&!PocketTranscript.updating}
  assertEquals("Recovered transport kept stale connection error","",Pocket.connectionError)
  assertEquals("Reconnect erased draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals("Reconnect submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun incompleteOptionalHeadProtocolCannotCreateAHistoryReadLoop(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("omit-snapshot-head");qa("notification-overflow");main{Pocket.refresh()};await{has(2)&&!PocketTranscript.updating}
  val reads=qa("state").getJSONArray("reads").length()
  repeat(3){main{Pocket.refresh()};Thread.sleep(600)};Thread.sleep(1600)
  assertEquals("Incomplete optional protocol polled history",reads,qa("state").getJSONArray("reads").length())
  qa("restore-snapshot-head");main{Pocket.refreshDetail()};await{has(2)&&!PocketTranscript.updating}
  qa("head-only-update");main{Pocket.refresh()};await{has(3)&&!PocketTranscript.updating}
  assertTrue("Restored protocol response not visible",device.wait(Until.hasObject(By.text("Fresh response 3")),4000))
  assertEquals("Compatibility recovery submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun selectedHistoryRecoversWhenUpdateIsOutsideBothBoundedFeeds(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("hold-catalog");main{Pocket.refresh()};await{qa("state").optBoolean("catalogBlocked")}
  try{
   qa("notification-overflow");main{Pocket.refresh()};assertTrue("Refresh must show visible loading feedback",device.wait(Until.hasObject(By.text("Updating messages")),4000));await{has(2)&&!PocketTranscript.updating}
   assertTrue("Recovered response missing from screen",device.wait(Until.hasObject(By.text("Fresh response 2")),4000))
   assertTrue("Recovery must occur before slow catalog finishes",qa("state").optBoolean("catalogBlocked"))
   assertEquals("Catalog must remain unchanged",1000L,Pocket.tasks.first{it.id=="same-thread"}.activityAt)
   assertTrue("Global notification feed must omit selected update",Pocket.notifications.none{it.s("thread_id")=="same-thread"})
   val reads=qa("state").getJSONArray("reads").length()
   repeat(3){main{Pocket.refresh()};Thread.sleep(600)};Thread.sleep(1500)
   assertEquals("Unchanged head repeatedly fetched history",reads,qa("state").getJSONArray("reads").length())
   qa("reset-history-head");main{Pocket.refresh()};await{has(3)&&!PocketTranscript.updating}
   assertTrue("Restarted response missing from screen",device.wait(Until.hasObject(By.text("Fresh response 3")),4000))
   val resetReads=qa("state").getJSONArray("reads").length();main{Pocket.refresh()};Thread.sleep(1600)
   assertEquals("Same restarted head repeatedly fetched history",resetReads,qa("state").getJSONArray("reads").length())
   qa("head-live-update");await{has(4)}
   assertTrue("Live response missing from screen",device.wait(Until.hasObject(By.text("Fresh response 4")),4000))
   main{Pocket.refresh()};Thread.sleep(1600)
   assertEquals("Already delivered live head polled history",resetReads,qa("state").getJSONArray("reads").length())
   qa("head-only-update");qa("fail-read-permanent");main{Pocket.refresh()}
   await{Pocket.error.contains("Synthetic history permission rejected")&&!PocketTranscript.updating}
   val deniedReads=qa("state").getJSONArray("reads").length()
   repeat(3){main{Pocket.refresh()};Thread.sleep(600)};Thread.sleep(1600)
   assertEquals("Permanent rejection was repeatedly retried by head probes",deniedReads,qa("state").getJSONArray("reads").length())
   assertTrue("Permission error erased last readable response",device.hasObject(By.text("Fresh response 4")))
   assertEquals("Freshness recovery submitted work",0,qa("state").getJSONArray("replies").length())
   assertEquals("Freshness recovery erased draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  }finally{qa("release-catalog")}
 }
 @Test fun fetchedNotificationRepairsMissedEventWithUnchangedCatalog(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("notification-only");main{Pocket.refresh()};await{has(2)&&!PocketTranscript.updating}
  assertEquals("Catalog should be unchanged",1000L,Pocket.tasks.first{it.id=="same-thread"}.activityAt)
  val reads=qa("state").getJSONArray("reads").length()
  main{Pocket.refresh()};Thread.sleep(1500)
  assertEquals("Same notification repeatedly fetched history",reads,qa("state").getJSONArray("reads").length())
  assertEquals("Recovery submitted work",0,qa("state").getJSONArray("replies").length())
  assertEquals("Recovery erased draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
 }
 @Test fun catalogActivityRepairsMissedEventsDespiteUnchangedPreview(){
  twoChats();main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.updating}
  qa("clipped-preview");qa("silent")
  assertTrue("Missed-event setup did not retain old history",has(1))
  main{Pocket.refresh()};await{has(2)&&!PocketTranscript.updating}
  assertEquals("Preview should remain unchanged","Fresh response 1",Pocket.tasks.first{it.id=="same-thread"}.preview)
  val reads=qa("state").getJSONArray("reads").length()
  main{Pocket.refresh()};Thread.sleep(1500)
  assertEquals("Unchanged catalog polled history again",reads,qa("state").getJSONArray("reads").length())
  assertEquals("Catch-up submitted work",0,qa("state").getJSONArray("replies").length())
  assertEquals("Catch-up erased draft","First saved draft",context.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
 }
 @Test fun cachedPreviewRefreshIsVisibleAndLatestWinsOverOlderHistory(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val prefs=context.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Unsaved draft preserved").commit()
  main{Pocket.init(context);context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{Pocket.open("same-thread")};await{has(1)&&!PocketTranscript.loading}
  qa("advance");await{Pocket.tasks.first().preview=="Fresh response 2"&&PocketTranscript.updating}
  assertTrue("Cached content disappeared during refresh",has(1))
  assertTrue("Refresh indicator not visible",device.wait(Until.hasObject(By.text("Updating messages")),2000))
  qa("release")
  await{has(2)&&!PocketTranscript.updating}
  val reads=qa("state").getJSONArray("reads").length()
  qa("repeat");Thread.sleep(800)
  assertEquals("Repeated previews triggered polling",reads,qa("state").getJSONArray("reads").length())
  val readerSettled=kotlinx.coroutines.CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={readerSettled.await()})}};await{PocketTranscript.loading}
  qa("advance");await{PocketTranscript.updating}
  Thread.sleep(2400)
  assertFalse("Older page inserted before reader settled",PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
  readerSettled.complete(Unit)
  await{has(3)&&!PocketTranscript.updating}
  assertTrue("Older loaded rows lost",PocketTranscript.rows.any{it.s("text")=="Older synthetic history"})
  qa("silent");main{Pocket.refresh()};await{has(4)&&!PocketTranscript.updating}
  main{Pocket.closeTask();Pocket.open("same-thread")}
  await{PocketTranscript.updating};assertTrue("Warm history hidden",has(4))
  await{!PocketTranscript.loading&&!PocketTranscript.updating}
  assertEquals("Unsaved draft preserved",prefs.getString("draft:same-thread",null))
  qa("order")
  fun live(id:String,kind:String,text:String,version:Int)=JSONObject().put("threadId","same-thread").put("turnId","order").put("version",version).put("row",JSONObject().put("id","order/"+id).put("turnId","order").put("kind",kind).put("text",text).put("version",version))
  main{
   PocketTranscript.reset("same-thread")
   PocketTranscript.apply(live("answer","message","Cached ordering answer",100))
   PocketTranscript.apply(live("user","user","Ordering request",101))
   Pocket.scope.launchEnvironment{PocketTranscript.load()}
  }
  await{PocketTranscript.loading}
  main{PocketTranscript.apply(live("answer","message","Newer answer during fetch",900))}
  await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="order/end"}}
  assertEquals(listOf("older-message","same-message","order/header","order/user","order/answer","order/end"),PocketTranscript.rows.map{it.s("id")})
  assertEquals("Newer answer during fetch",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  assertTrue("Newer answer absent from native view",device.wait(Until.hasObject(By.text("Newer answer during fetch")),2000))
  assertEquals("Draft changed during ordering repair","Unsaved draft preserved",prefs.getString("draft:same-thread",null))
  main{
   Pocket.detail=JSONObject(requireNotNull(Pocket.detail).toString()).put("outgoing",org.json.JSONArray().put(JSONObject().put("id","failed-ui").put("state","failed").put("mode","auto").put("text","Synthetic failed draft").put("result","Synthetic transport failure")))
  }
  assertTrue("Retry control missing",device.wait(Until.hasObject(By.text("Retry")),3000))
  fun actionEnabled(label:String):Boolean{
   var node=device.findObject(By.text(label))?:return false
   while(true){if(!node.isEnabled)return false;node=node.parent?:return true}
  }
  main{Pocket.replyActionBusy=setOf("other-environment:same-thread:failed-ui")}
  assertTrue("Other environment disabled this action",actionEnabled("Retry"))
  main{Pocket.replyActionBusy=setOf("${Pocket.environmentId}:same-thread:failed-ui")}
  await{!actionEnabled("Retry")}
  assertFalse("Edit available during pending request",actionEnabled("Edit"))
  assertFalse("Remove available during pending request",actionEnabled("Remove"))
  main{Pocket.replyActionBusy=emptySet()}
  await{actionEnabled("Retry")}
  main{Pocket.reply("Synthetic rapid submission");Pocket.reply("Synthetic rapid submission")}
  await{!Pocket.sending}
  assertEquals("Rapid input created duplicate submissions",1,qa("state").getJSONArray("replies").length())
  assertTrue("Keyboard entry missing",device.wait(Until.hasObject(By.pkg("co.fallsoft.pocket").text("Keyboard")),3000))
  device.findObject(By.pkg("co.fallsoft.pocket").text("Keyboard")).click()
  assertTrue("Editor did not open",device.wait(Until.hasObject(By.clazz("android.widget.EditText")),3000))
  device.findObject(By.clazz("android.widget.EditText")).text="One handed draft"
  await{prefs.getString("draft:same-thread",null)=="One handed draft"}
  assertFalse("Keyboard control duplicated while typing",device.hasObject(By.pkg("co.fallsoft.pocket").text("Keyboard")))
  device.pressBack()
  await{Pocket.selected==null}
  assertEquals("Back lost the draft","One handed draft",prefs.getString("draft:same-thread",null))
  main{Pocket.open("same-thread",keyboard=true)}
  assertTrue("Draft not restored in editor",device.wait(Until.hasObject(By.clazz("android.widget.EditText").text("One handed draft")),3000))
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER)
  await{qa("state").getJSONArray("replies").length()==2&&!Pocket.sending}
  assertEquals("Enter changed the sent draft","One handed draft",qa("state").getJSONArray("replies").getJSONObject(1).s("text"))
  await{!prefs.contains("draft:same-thread")}
  device.findObject(By.clazz("android.widget.EditText")).text="Voice must keep this draft"
  await{prefs.getString("draft:same-thread",null)=="Voice must keep this draft"}
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_UP)
  await{PocketVoice.state=="Listening"}
  assertEquals("Capture routed away from current chat","same-thread",PocketVoice.targetThread)
  assertTrue("Recording timer not initialized",PocketVoice.recordingStartedAt>0)
  assertTrue("Recording feedback not visible",device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("Recording .*"))),2000))
  device.pressKeyCode(android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
  await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
  assertEquals("Voice keys lost typed draft","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  assertFalse("Silent emulator capture submitted a voice turn",prefs.contains("voicePendingId"))
  assertTrue("Capture error hidden",device.wait(Until.hasObject(By.text(PocketVoice.problem)),2000))
  assertTrue("Touch microphone retry missing",device.wait(Until.hasObject(By.desc("Start recording")),2000))
  device.findObject(By.desc("Start recording")).click()
  await{PocketVoice.state=="Listening"}
  assertTrue("One-tap stop control missing",device.wait(Until.hasObject(By.desc("Stop and send recording")),2000))
  device.findObject(By.desc("Stop and send recording")).click()
  await{PocketVoice.state=="Ready"&&PocketVoice.problem.isNotBlank()}
  assertEquals("Repeated touch recording lost draft","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  main{PocketVoice.stop()}
  main{for(n in 0..29){PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","read-error").put("version",1000+n).put("row",JSONObject().put("id","error-reading-$n").put("turnId","read-error").put("kind","message").put("text","Older reading position $n: retained synthetic material filling the conversation so errors at its end would be offscreen.")))}}
  assertTrue(device.wait(Until.hasObject(By.textContains("Older reading position 29:")),3000))
  repeat(3){requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.7f);Thread.sleep(150)}
  assertFalse("Reading setup remained at transcript end",device.hasObject(By.textContains("Older reading position 29:")))
  val retained=PocketTranscript.rows.map{it.s("id")}
  qa("fail-read");main{Pocket.refreshDetail()}
  await{!PocketTranscript.loading&&Pocket.error.contains("Synthetic history refresh unavailable")}
  assertEquals("Failed refresh discarded local history",retained,PocketTranscript.rows.map{it.s("id")})
  assertTrue("History refresh error hidden",device.wait(Until.hasObject(By.textContains("Synthetic history refresh unavailable")),2000))
  assertTrue("Read recovery inaccessible",device.wait(Until.hasObject(By.text("Reload conversation")),2000))
  device.findObject(By.text("Reload conversation")).click()
  await{!PocketTranscript.loading&&Pocket.error.isBlank()}
  assertEquals("Read retry erased typing","Voice must keep this draft",prefs.getString("draft:same-thread",null))
  val readsBeforeRejected=qa("state").getJSONArray("reads").length()
  qa("fail-read-permanent")
  main{Pocket.refreshDetail();Pocket.refreshDetail()}
  await{!PocketTranscript.loading&&Pocket.error.contains("Synthetic history permission rejected")}
  assertFalse("Permission failure still permits automatic reads",PocketTranscript.automaticRetryAllowed)
  assertFalse("Rejected coalesced read left perpetual Updating messages",PocketTranscript.updating)
  Thread.sleep(900)
  assertEquals("Rejected read retried unchanged credentials",readsBeforeRejected+1,qa("state").getJSONArray("reads").length())
  device.findObject(By.text("Reload conversation")).click()
  await{!PocketTranscript.loading&&Pocket.error.isBlank()}
  assertTrue("Explicit successful reload did not restore automatic pagination",PocketTranscript.automaticRetryAllowed)

 }
}
