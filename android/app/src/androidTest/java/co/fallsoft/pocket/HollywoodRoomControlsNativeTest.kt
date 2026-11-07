package co.fallsoft.pocket

import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** One real nonexecuting QA request. Its journal fences every subsequent invocation. */
@RunWith(AndroidJUnit4::class)
class HollywoodRoomControlsNativeTest {
 @Test fun stopLostAcknowledgementAndLocalSlowdownReportKeepOriginalIdentity()=runBlocking {
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val config=JSONObject(c.filesDir.resolve("live-room-qa-config.json").readText())
  val binding=config.getJSONObject("identity");val room=config.s("roomId");val base=config.s("base");val token=config.s("token")
  check(binding.s("authorityId")=="6b8d1e3c-12c0-4c6d-8943-a7fdb32deece"&&room=="6d966d5c-6d16-4fa9-91ee-5313e86312f7"&&base=="http://127.0.0.1:18890")
  check(config.s("recipientId")=="19e396f9-0530-44bd-91dd-ef29bb1395e4")
  val client=HollywoodClient(backendOwner(base,token),binding,base,token)
  val scope=HollywoodScope(room,config.s("recipientId"))
  val runId=InstrumentationRegistry.getArguments().getString("qaRunId")?:config.s("roomControlsRunId","r01");require(runId.matches(Regex("[a-zA-Z0-9_-]{1,60}")))
  val text="QA native room controls and local responsiveness report 20261016 "+runId
  val journal=c.filesDir.resolve("native-room-controls-journey-$runId.json")
  c.filesDir.listFiles().orEmpty().filter{it.name.startsWith("native-room-controls-journey-")&&it.extension=="json"&&it!=journal}.forEach{prior->
   val previous=JSONObject(prior.readText());require(previous.s("deliveryId").isNotBlank()){"Inspect earlier prepared QA run before another intent"}
   val original=client.call("deliveries/"+previous.s("deliveryId")).getJSONObject("delivery");require(original.s("state") in listOf("completed","failed","cancelled")){"Earlier original QA work is not settled"}
  }
  val apkPath=d.executeShellCommand("pm path co.fallsoft.pocket").trim().removePrefix("package:")
  val apkSha=d.executeShellCommand("sha256sum $apkPath").substringBefore(" ")
  val digest=java.security.MessageDigest.getInstance("SHA-256")
  java.util.zip.ZipFile(apkPath).use{zip->zip.entries().asSequence().filter{!it.name.startsWith("META-INF/")}.sortedBy{it.name}.forEach{entry->
   digest.update(entry.name.toByteArray(Charsets.UTF_8));digest.update(0.toByte())
   zip.getInputStream(entry).use{input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
  }}
  val payloadSha=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
  fun await(label:String,test:()->Boolean){val end=SystemClock.elapsedRealtime()+35000;while(SystemClock.elapsedRealtime()<end){i.uiAutomation.clearCache();if(test())return;Thread.sleep(100)};assertTrue(label,test())}
  fun tap(tag:String){
   await("Native control unavailable: "+tag){runCatching{d.findObject(By.res(tag))?.let{it.click();true}?:false}.getOrDefault(false)}
  }
  fun show(tag:String,up:Boolean=false):UiObject2 {
   repeat(12){
    i.uiAutomation.clearCache()
    val item=d.findObject(By.res(tag))
    if(item!=null&&item.visibleBounds.height()>12)return item
    val scroll=d.findObjects(By.pkg(c.packageName).scrollable(true)).firstOrNull{it.resourceName!="native-room-history"}
    check(scroll!=null){"No native menu scroller for $tag"};scroll.scroll(if(up)Direction.UP else Direction.DOWN,.7f)
   }
   d.dumpWindowHierarchy(c.filesDir.resolve("room-controls-target-failure.xml"));d.takeScreenshot(c.filesDir.resolve("room-controls-target-failure.png"))
   c.filesDir.resolve("room-controls-target-failure-detail.json").writeText(runBlocking{client.call("rooms/$room")}.toString())
   error("Native menu target unavailable: $tag")
  }
  fun receipt()=Pocket.prefs.all.filterKeys{it.startsWith("receipt:hollywood:"+client.owner+":")}.values
   .mapNotNull{runCatching{JSONObject(it as String)}.getOrNull()}.firstOrNull{it.optJSONObject("receipt")?.optJSONObject("event")?.s("body")==text}
  fun control(drop:Boolean){
   val q=Request.Builder().url(base+"/__qa/control").header("Authorization","Bearer "+config.s("controlToken"))
    .post(JSONObject().put("dropCancelAck",drop).toString().toRequestBody("application/json".toMediaType())).build()
   Pocket.http.newCall(q).execute().use{assertEquals(200,it.code)}
  }
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  var originalDraft=client.draft(scope);var target:JSONObject?=null
  val profileId=prepareRealRoomQaEnvironment(c,config)
  try{
   control(false)
   i.runOnMainSync{Pocket.init(c);Pocket.activateEnvironment(profileId,force=true);check(Pocket.environmentId==profileId&&Pocket.base==base){"Paired QA profile failed to activate"};c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
   await("Native room unavailable"){d.hasObject(By.res("native-room-controls"))}
   if(!d.hasObject(By.clazz("android.widget.EditText")))tap("native-room-keyboard")
   await("Room keyboard did not open"){d.hasObject(By.clazz("android.widget.EditText"))}
   val priorPrepared=if(journal.exists())JSONObject(journal.readText())else null
   if(priorPrepared!=null&&priorPrepared.s("stage")=="complete"){
    check(priorPrepared.s("codeResourcesManifestSha256")==payloadSha){"Inspect the original completed qualification; do not replay it"}
    val request=client.call("commands/"+priorPrepared.s("operationId"));assertEquals(priorPrepared.s("eventId"),request.getJSONObject("event").s("id"))
    val existing=client.call("deliveries/"+priorPrepared.s("deliveryId"));assertEquals("cancelled",existing.getJSONObject("delivery").s("state"))
    tap("native-room-controls")
    assertFalse(show("native-workflow").visibleBounds.isEmpty)
    return@runBlocking
   }
   if(priorPrepared!=null){
    check(priorPrepared.s("stage")=="prepared"&&!priorPrepared.has("operationId")&&!priorPrepared.has("deliveryId")){"Reconcile original delivery; do not submit again"}
    var cursor=0L;var exhausted=false
    repeat(30){
     if(!exhausted){
      val page=client.call("rooms/$room/history?view=conversation&after=$cursor&limit=100")
      val rows=page.getJSONArray("events").objects()
      check(rows.none{it.s("body")==text}){"Original text already exists canonically; reconcile without another send"}
      val next=page.getLong("nextCursor");check(next>=cursor)
      exhausted=rows.size<100;cursor=next
     }
    }
    check(exhausted){"Cannot establish original submission absence within bounded history"}
   }
   check(client.pending(scope)==null){"An existing original delivery must be inspected, not overwritten"}
   journal.writeText(JSONObject().put("stage","prepared").put("qualificationApkSha256",apkSha).put("codeResourcesManifestSha256",payloadSha).put("originalDraft",originalDraft).apply{priorPrepared?.let{put("priorPrepared",it)}}.toString())
   d.findObject(By.clazz("android.widget.EditText")).apply{click();this.text=text}
   await("IME did not open"){d.hasObject(By.pkg("com.google.android.inputmethod.latin"))}
   tap("native-room-controls")
   show("native-measure-responsiveness").click()
   await("Bounded report did not start"){ResponsivenessReport.measuring}
   assertTrue("Opening the footer controls dismissed typing",d.hasObject(By.pkg("com.google.android.inputmethod.latin")))
   d.findObject(By.desc("Send message")).click()
   await("Original accepted receipt missing"){receipt()!=null}
   val envelope=requireNotNull(receipt());val accepted=envelope.getJSONObject("receipt")
   target=accepted.getJSONArray("deliveries").getJSONObject(0)
   val id=target!!.s("id")
   val initial=client.call("deliveries/$id").getJSONObject("delivery")
   assertEquals("pending",initial.s("state"));assertTrue(initial.isNull("host")&&initial.isNull("native_thread"))
   journal.writeText(JSONObject().put("stage","accepted").put("qualificationApkSha256",apkSha).put("codeResourcesManifestSha256",payloadSha).put("originalDraft",originalDraft)
    .put("operationId",envelope.s("operationId")).put("eventId",accepted.getJSONObject("event").s("id")).put("deliveryId",id).toString())
   control(true)
   await("Exact delivery quick-stop did not arrive"){d.hasObject(By.res("native-quick-stop:"+id))}
   d.dumpWindowHierarchy(c.filesDir.resolve("room-controls-before-stop.xml"));d.takeScreenshot(c.filesDir.resolve("room-controls-before-stop.png"))
   c.filesDir.resolve("room-controls-before-stop-detail.json").writeText(client.call("rooms/$room").toString())
   tap("native-quick-stop:"+id)
   await("Lost stop acknowledgement did not reconcile"){client.stopRecord(id)?.s("state")=="cancelled"}
   val cancelled=client.call("deliveries/$id").getJSONObject("delivery")
   assertEquals("cancelled",cancelled.s("state"));assertTrue(cancelled.optBoolean("cancellation_requested"))
   assertTrue("QA unexpectedly started native execution",cancelled.isNull("host")&&cancelled.isNull("native_thread"))
   await("Room did not retain its single footer"){d.hasObject(By.res("native-room-controls"))}
   assertFalse("Obsolete permanent idle strip remains",d.hasObject(By.res("native-room-idle")))
   assertTrue("Canonical controls are not folded",d.hasObject(By.text("Operations")))
   if(!d.hasObject(By.clazz("android.widget.EditText")))tap("native-room-keyboard")
   await("Keyboard control did not restore input"){d.hasObject(By.clazz("android.widget.EditText"))}
   d.findObject(By.clazz("android.widget.EditText")).text=originalDraft
   await("Bounded report did not finish/export"){!ResponsivenessReport.measuring&&ResponsivenessReport.savedName.isNotBlank()}
   val body=c.filesDir.resolve("diagnostics/responsiveness-latest.json").readText();val report=JSONObject(body)
   assertTrue(report.getJSONObject("metadata").optBoolean("nativeRooms"));assertTrue(report.optLong("totalFrames")>0)
   assertTrue(report.getJSONArray("frames").length()<=2048)
   assertFalse("Report leaked input",body.contains(text));assertFalse("Report leaked credential",body.contains(token))
   if(Build.VERSION.SDK_INT>=29){
    val collection=MediaStore.Downloads.EXTERNAL_CONTENT_URI
    c.contentResolver.query(collection,arrayOf(MediaStore.Downloads._ID),MediaStore.Downloads.DISPLAY_NAME+"=?",arrayOf(ResponsivenessReport.savedName),null).use{cursor->
     assertTrue("Downloads export missing",cursor?.moveToFirst()==true)
     val uri=ContentUris.withAppendedId(collection,cursor!!.getLong(0))
     assertEquals(body,requireNotNull(c.contentResolver.openInputStream(uri)).bufferedReader().use{it.readText()})
    }
   }
   val original=JSONObject(journal.readText()).put("stage","complete").put("stopRecoveredByRead",true).put("reportFile",ResponsivenessReport.savedName)
    .put("draftRestored",true).put("nativeExecutionStarted",false)
   journal.writeText(original.toString())
   d.takeScreenshot(c.filesDir.resolve("native-room-controls-closed.png"))
  }finally{
   control(false)
   target?.let{delivery->
    val current=client.call("deliveries/"+delivery.s("id")).getJSONObject("delivery")
    if(current.s("state") !in listOf("completed","failed","cancelled"))client.stopDelivery(room,current,inspect=client.stopRecord(current.s("id"))!=null)
   }
   if(client.pending(scope)==null)client.saveDraft(scope,originalDraft)
   Configurator.getInstance().waitForIdleTimeout=oldIdle
  }
 }
}
