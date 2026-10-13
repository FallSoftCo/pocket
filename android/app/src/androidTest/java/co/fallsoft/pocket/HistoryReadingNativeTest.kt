package co.fallsoft.pocket
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class HistoryReadingNativeTest {
 private val i=InstrumentationRegistry.getInstrumentation()
 private val device=UiDevice.getInstance(i)
 private fun main(block:()->Unit)=i.runOnMainSync(block)
 private fun await(message:String="Reading condition timed out",condition:()->Boolean){val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};assertTrue(message,condition())}
 private fun visiblePassages(prefix:String="Reading passage "):List<Pair<String,android.graphics.Rect>>{
  if(android.os.Build.VERSION.SDK_INT>=34)i.uiAutomation.clearCache()
  val out=java.io.ByteArrayOutputStream();device.dumpWindowHierarchy(out)
  val doc=javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.ByteArrayInputStream(out.toByteArray()))
  val nodes=doc.getElementsByTagName("node")
  return (0 until nodes.length).mapNotNull{index->val n=nodes.item(index) as org.w3c.dom.Element;val text=n.getAttribute("text");if(!text.startsWith(prefix))null else {val b=Regex("[0-9]+").findAll(n.getAttribute("bounds")).map{it.value.toInt()}.toList();if(b.size!=4)null else text to android.graphics.Rect(b[0],b[1],b[2],b[3])}}
 }
 private fun qa(path:String)=runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/$path",JSONObject())}
 private var eventVersion=900
 private fun event(id:String,kind:String,text:String):JSONObject{val version=++eventVersion;return JSONObject().put("type","timeline").put("threadId","same-thread").put("turnId","order").put("version",version).put("row",JSONObject().put("id","order/$id").put("turnId","order").put("kind",kind).put("text",text).put("version",version))}
 @Test fun typingWithReadingWavePreservesDraftAndReportsDispatchLatency(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()}
  main{
   Pocket.open("same-thread",keyboard=true)
   PocketImmersion.setEnabled(true);PocketImmersion.setSupportEnabled(true)
  }
  await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","typing-wave").put("version",500+n).put("row",JSONObject().put("id","typing-wave-$n").put("turnId","typing-wave").put("kind","message").put("text","Full permissions for new tasks")))}
  val field=By.clazz("android.widget.EditText")
  assertTrue(device.wait(Until.hasObject(field),5000))
  requireNotNull(device.findObject(field)).click()
  val reports=org.json.JSONArray()
  val draftKey=Pocket.key("draft:same-thread")
  var count=0
  for(cycling in listOf(false,true,false,true)){
   main{PocketImmersion.setMotionEnabled(cycling)}
   Thread.sleep(500)
   val timings=org.json.JSONArray()
   repeat(16){
    val before=android.os.SystemClock.elapsedRealtimeNanos()
    val eventTime=android.os.SystemClock.uptimeMillis()
    assertTrue(i.uiAutomation.injectInputEvent(android.view.KeyEvent(eventTime,eventTime,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_A,0),true))
    assertTrue(i.uiAutomation.injectInputEvent(android.view.KeyEvent(eventTime,eventTime,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_A,0),true))
    count++
    val deadline=android.os.SystemClock.elapsedRealtime()+2000
    while(Pocket.prefs.getString(draftKey,"")!!.length<count&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(2)
    assertEquals(count,Pocket.prefs.getString(draftKey,"")!!.length)
    timings.put((android.os.SystemClock.elapsedRealtimeNanos()-before)/1e6)
    Thread.sleep(70)
   }
   reports.put(org.json.JSONObject().put("cycling",cycling).put("dispatchToSavedDraftMs",timings))
  }
  val expected="a".repeat(count)
  assertEquals(expected,Pocket.prefs.getString(draftKey,""))
  main{Pocket.closeTask();Pocket.open("same-thread",keyboard=true)}
  await{device.findObject(field)?.text==expected}
  java.io.File(c.cacheDir,"conversation-typing-performance.json").writeText(org.json.JSONObject().put("scope","Production conversation with 40 synthetic offline-translated messages and real hardware-key dispatch; timing includes direct Android input dispatch and saved-draft observation, not optical display latency; software emulator only").put("runs",reports).put("draftRetained",true).toString())
 }
 @Test fun growingResponseKeepsItsNewestPassageVisible(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  val body=(1..30).joinToString("\n\n"){"Streaming passage $it. "+"Readable synthetic response. ".repeat(5)}+"\n\nStreaming tail marker"
  main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","streaming").put("version",1000).put("row",JSONObject().put("id","streaming/answer").put("turnId","streaming").put("kind","message").put("text",body)))}
  assertTrue("Following a growing reply left its latest passage offscreen",device.wait(Until.hasObject(By.text("Streaming tail marker")),5000))
  val tail=requireNotNull(device.findObject(By.text("Streaming tail marker"))).visibleBounds
  assertFalse("Latest passage has empty visible bounds",tail.isEmpty)
  assertTrue("Latest passage is outside the screen",tail.top>=0&&tail.bottom<=device.displayHeight)
  fun update(text:String,version:Int)=main{PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","streaming").put("version",version).put("row",JSONObject().put("id","streaming/answer").put("turnId","streaming").put("kind","message").put("text",text)))}
  val growing=body+"\n\nStreaming revised tail"
  update(growing,1001)
  assertTrue("A subsequent delta did not follow the new tail",device.wait(Until.hasObject(By.text("Streaming revised tail")),5000))
  repeat(2){requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.65f);Thread.sleep(200)}
  await{!PocketTranscript.loading};device.waitForIdle(2000)
  var snapshot=visiblePassages("Streaming passage ")
  var stable=0
  await("Reading viewport never settled"){
   val next=visiblePassages("Streaming passage ");stable=if(next==snapshot)stable+1 else 0;snapshot=next;stable>=2
  }
  val anchor=requireNotNull(snapshot.firstOrNull{(_,b)->!b.isEmpty&&b.top>50&&b.bottom<device.displayHeight*3/4})
  val anchorText=anchor.first;val anchorTop=anchor.second.top
  update(growing+"\n\nStreaming unread tail",1002);Thread.sleep(600);device.waitForIdle(2000)
  val after=requireNotNull(visiblePassages("Streaming passage ").firstOrNull{it.first==anchorText}).second
  assertTrue("A growing reply moved the earlier reading passage",kotlin.math.abs(after.top-anchorTop)<=24)
  val unread=visiblePassages("Streaming unread tail")
  assertTrue("A new delta forced reading back to latest",unread.none{!it.second.isEmpty})
  assertEquals("Auto-follow submitted work",0,qa("state").getJSONArray("replies").length())
 }
 @Test fun pullingAShortRecentPageLoadsEarlierHistoryWithoutATap(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.POST_NOTIFICATIONS")
  device.executeShellCommand("pm grant co.fallsoft.pocket android.permission.RECORD_AUDIO")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Short history draft").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")}
  await{PocketTranscript.rows.any{it.s("text")=="Fresh response 1"}&&!PocketTranscript.updating}
  Thread.sleep(400)
  assertTrue(device.swipe(device.displayWidth/2,device.displayHeight/2,device.displayWidth/2,device.displayHeight/4,35))
  Thread.sleep(3500)
  assertEquals("A swipe toward latest unexpectedly fetched older history",0,qa("state").getJSONArray("reads").objects().count{it.optBoolean("older")})
  assertTrue(device.swipe(device.displayWidth/2,device.displayHeight/4,device.displayWidth/2,device.displayHeight/2,35))
  await("A thumb pull on a short page did not load older history"){PocketTranscript.rows.any{it.s("id")=="older-message"}&&!PocketTranscript.loading}
  assertTrue("Loaded older text stayed outside the viewport",device.wait(Until.hasObject(By.text("Older synthetic history")),3000))
  assertTrue("Short-page reveal hid the retained recent reply",device.hasObject(By.text("Fresh response 1")))
  assertTrue(PocketTranscript.rows.any{it.s("text")=="Fresh response 1"})
  assertEquals(1,qa("state").getJSONArray("reads").objects().count{it.optBoolean("older")})
  assertEquals("Short history draft",c.getSharedPreferences("pocket",Context.MODE_PRIVATE).getString("draft:same-thread",null))
  assertEquals(0,qa("state").getJSONArray("replies").length())
 }
 @Test fun readingAnchorSurvivesDeferredOlderPage(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putBoolean("diagnostic:scroll",true).commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  main{for(n in 0..39)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","reading").put("version",100+n).put("row",JSONObject().put("id","reading-$n").put("turnId","reading").put("kind","message").put("text","Reading passage $n: retained synthetic conversation text with a clear place to return while earlier history arrives.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Reading passage 39:")),5000));Thread.sleep(150)
  if(InstrumentationRegistry.getArguments().getString("profileConversation")=="true"){
   var window:android.view.Window?=null
   main{window=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().single().window}
   val frames=java.util.Collections.synchronizedList(mutableListOf<LongArray>())
   val worker=android.os.HandlerThread("ConversationFrameObservation").apply{start()}
   val listener=android.view.Window.OnFrameMetricsAvailableListener{_,f,_->frames.add(longArrayOf(f.getMetric(android.view.FrameMetrics.TOTAL_DURATION),f.getMetric(android.view.FrameMetrics.DRAW_DURATION),f.getMetric(android.view.FrameMetrics.DEADLINE)))}
   val before=android.os.Debug.getRuntimeStats();val cpu=android.os.Process.getElapsedCpuTime();val started=android.os.SystemClock.elapsedRealtime()
   main{window!!.addOnFrameMetricsAvailableListener(listener,android.os.Handler(worker.looper))}
   try{
    repeat(6){n->device.swipe(device.displayWidth/2,if(n%2==0)device.displayHeight/3 else device.displayHeight*2/3,device.displayWidth/2,if(n%2==0)device.displayHeight*2/3 else device.displayHeight/3,30);Thread.sleep(600)}
   }finally{main{window!!.removeOnFrameMetricsAvailableListener(listener)};worker.quitSafely();worker.join(2000)}
   val after=android.os.Debug.getRuntimeStats();val deltas=JSONObject();listOf("art.gc.bytes-allocated","art.gc.gc-count","art.gc.gc-time").forEach{k->val a=before[k]?.toLongOrNull();val b=after[k]?.toLongOrNull();if(a!=null&&b!=null)deltas.put(k,b-a)}
   val samples=org.json.JSONArray();synchronized(frames){frames.forEach{samples.put(org.json.JSONArray().put(it[0]).put(it[1]).put(it[2]))}}
   java.io.File(c.cacheDir,"conversation-scroll-performance.json").writeText(JSONObject().put("scope","Actual production conversation with 40 synthetic messages, alternating finger scrolls, immersion disabled, software emulator; not physical frame pacing").put("wallMs",android.os.SystemClock.elapsedRealtime()-started).put("processCpuMs",android.os.Process.getElapsedCpuTime()-cpu).put("runtimeDeltas",deltas).put("frames",samples).toString())
  }

  if(InstrumentationRegistry.getArguments().getString("profileOnly")=="true")return
  repeat(3){requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.7f);Thread.sleep(200)}
  device.waitForIdle(2000)
  device.dumpWindowHierarchy(java.io.File(c.cacheDir,"history-reading-hierarchy.xml"))
  val visible=visiblePassages()
  val last=visible.firstOrNull{it.first.startsWith("Reading passage 39:")}
  assertTrue("Reading gesture returned to latest: ${last?.second}",last==null||last.second.isEmpty)
  val anchor=requireNotNull(visible.firstOrNull{!it.second.isEmpty&&it.second.top>50&&it.second.bottom<device.displayHeight*3/4})
  val text=anchor.first;val top=anchor.second.top
  // Exercise the real footer pager, not a direct LazyListState mutation.
  val beforePage=visiblePassages().filter{it.second.top>50&&it.second.bottom<device.displayHeight*3/4}.map{it.first}.toSet()
  requireNotNull(device.findObject(By.res("reader-page-up"))).click()
  Thread.sleep(600);device.waitForIdle(2000)
  val afterPage=visiblePassages().filter{it.second.top>50&&it.second.bottom<device.displayHeight*3/4}.map{it.first}.toSet()
  assertTrue("Page up did not change the reading passage",afterPage.isNotEmpty()&&afterPage!=beforePage)
  assertTrue("Full passages overlap across a full viewport page",beforePage.intersect(afterPage).isEmpty())
  requireNotNull(device.findObject(By.res("reader-page-down"))).click()
  Thread.sleep(600);device.waitForIdle(2000)
  assertTrue("Paging back lost the reading anchor",kotlin.math.abs(requireNotNull(visiblePassages().firstOrNull{it.first==text}).second.top-top)<=24)

  val settled=CompletableDeferred<Unit>();val arrived=CompletableDeferred<Unit>()
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true,beforeApply={arrived.complete(Unit);settled.await()})}}
  await{PocketTranscript.loading};await{arrived.isCompleted}
  assertFalse("Page applied during unsettled read",PocketTranscript.rows.any{it.s("id")=="older-message"})
  settled.complete(Unit);await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("id")=="older-message"}}
  assertTrue("Prepend shifted reading passage",kotlin.math.abs(requireNotNull(visiblePassages().firstOrNull{it.first==text}).second.top-top)<=24)

 }
 @Test fun lateInputEventReconcilesCanonicalOrderOnce(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putBoolean("diagnostic:scroll",true).commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("order")
  main{Pocket.event(event("answer","message","Initial streamed answer"));Pocket.event(event("user","user","Ordering request"))}
  await{PocketTranscript.loading}
  main{Pocket.event(event("answer","message","New answer during reconciliation"))}
  await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="order/end"}}
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Late input remains after its response",ids.indexOf("order/user")<ids.indexOf("order/answer"))
  assertEquals("New streamed answer overwritten","New answer during reconciliation",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  val reads=qa("state").getJSONArray("reads").length();main{Pocket.event(event("user","user","Ordering request"))};Thread.sleep(700)
  assertEquals("Repeated input triggered polling",reads,qa("state").getJSONArray("reads").length())
 }

 @Test fun shortHistoryPagesRequireAnotherReadingGesture(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("pacing")
  main{for(n in 0..7)PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","reading").put("version",100+n).put("row",JSONObject().put("id","pacing-read-$n").put("turnId","reading").put("kind","message").put("text","Reading passage $n: retained synthetic conversation text with a clear place to return while earlier history arrives.")))}
  assertTrue(device.wait(Until.hasObject(By.textContains("Reading passage 7:")),5000));Thread.sleep(150)
  requireNotNull(device.findObject(By.scrollable(true))).scroll(androidx.test.uiautomator.Direction.UP,0.9f)
  await{PocketTranscript.rows.any{it.s("id")=="pacing-1"}&&!PocketTranscript.loading}
  Thread.sleep(3000)
  fun olderReads()=qa("state").getJSONArray("reads").objects().count{it.optBoolean("older")}
  assertEquals("Idle edge drained multiple history pages",1,olderReads())
  fun accessibleScroll(node:android.view.accessibility.AccessibilityNodeInfo):Boolean{
   if(node.isScrollable)return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
   for(index in 0 until node.childCount){val child=node.getChild(index)?:continue;if(accessibleScroll(child))return true}
   return false
  }
  assertTrue("Accessible history scroll unavailable",accessibleScroll(i.uiAutomation.rootInActiveWindow))
  await{PocketTranscript.rows.any{it.s("id")=="pacing-2"}&&!PocketTranscript.loading}
  assertEquals("Second gesture did not load exactly one page",2,olderReads())
 }

 @Test fun anchoredInputOutsideRecentPageRepairsWithoutBackfillOrLosingLiveText(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;val prefs=c.getSharedPreferences("pocket",Context.MODE_PRIVATE)
  prefs.edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").putString("draft:same-thread","Keep anchored draft").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("anchored")
  main{
   PocketTranscript.apply(event("commentary","message","Earlier commentary"))
   PocketTranscript.apply(event("prior-user","user","Earlier request"))
   PocketTranscript.apply(event("prior-answer","message","Earlier reply"))
   PocketTranscript.apply(event("answer","message","Original anchored reply"))
   Pocket.event(event("user","user","Delayed request outside latest page").put("type","timeline"))
  }
  await{!PocketTranscript.loading&&PocketTranscript.rows.any{it.s("text")=="Stale anchored answer"}}
  if(qa("state").getJSONArray("positions").length()>0)assertTrue("Position read indicator missing",PocketTranscript.updating)
  main{PocketTranscript.apply(event("answer","message","Newest live anchored reply").put("version",900))}
  qa("release-position")
  await("Delayed request remained below its reply outside the recent page"){
   val current=PocketTranscript.rows.map{it.s("id")}
   !PocketTranscript.updating&&current.indexOf("order/user") in 0 until current.indexOf("order/answer")
  }
  val ids=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Delayed request remained below its reply",ids.indexOf("order/user")<ids.indexOf("order/answer"))
  assertTrue("Earlier reply moved behind later steer",ids.indexOf("order/prior-answer")<ids.indexOf("order/user"))
  assertTrue("Earlier commentary lost",ids.contains("order/commentary"))
  assertTrue("Local history hidden",ids.contains("same-message"))
  assertEquals("Newest live anchored reply",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
  assertEquals("Keep anchored draft",prefs.getString("draft:same-thread",null))
  assertFalse("Anchored repair fetched older history",qa("state").getJSONArray("reads").objects().any{it.optBoolean("older")})
  main{Pocket.event(event("user","user","Delayed request outside latest page").put("type","timeline"))}
  Thread.sleep(600)
  assertEquals("Repeated input restarted position read",1,qa("state").getJSONArray("positions").length())
  qa("anchored")
  main{Pocket.event(event("second-user","user","Second delayed input").put("type","timeline"))}
  await{qa("state").getJSONArray("positions").length()==2}
  main{Pocket.open("second-thread")}
  await{PocketTranscript.rows.any{it.s("id")=="other/message"}&&!PocketTranscript.loading}
  qa("release-position");Thread.sleep(500)
  assertEquals("Stale position response changed selected conversation","second-thread",Pocket.selected)
  assertEquals("Old position rows leaked into other conversation",listOf("other/message"),PocketTranscript.rows.map{it.s("id")})
  main{Pocket.open("same-thread")}
  await{!PocketTranscript.updating&&PocketTranscript.rows.any{it.s("id")=="order/second-user"}}
  val reopened=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Warm reopen did not repair cancelled outside-page input",reopened.indexOf("order/second-user")<reopened.indexOf("order/answer"))
  assertEquals("Warm reopen lost draft","Keep anchored draft",prefs.getString("draft:same-thread",null))
  qa("fail-position")
  main{Pocket.event(event("third-user","user","Retain input when position read fails").put("type","timeline"))}
  await{!PocketTranscript.updating&&PocketTranscript.readError.contains("Synthetic item position unavailable")}
  assertTrue("Read failure hid the delayed input",PocketTranscript.rows.any{it.s("id")=="order/third-user"})
  val failedCount=qa("state").getJSONArray("positions").length()
  Thread.sleep(600);assertEquals("Failed position read polled unchanged dependency",failedCount,qa("state").getJSONArray("positions").length())
  main{Pocket.retryConnection()}
  await{!PocketTranscript.updating&&PocketTranscript.readError.isBlank()}
  val retried=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Explicit retry did not repair failed position",retried.indexOf("order/third-user")<retried.indexOf("order/answer"))
  assertEquals("Position retry lost draft","Keep anchored draft",prefs.getString("draft:same-thread",null))
 }

 @Test fun partialOlderPageRepairsLateInputAgainstRetainedReply(){
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val c=i.targetContext;c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76").putString("deviceId","synthetic-device-a").commit()
  main{Pocket.init(c);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
  await{Pocket.connected&&Pocket.tasks.isNotEmpty()};main{Pocket.open("same-thread")};await{PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
  qa("boundary")
  main{PocketTranscript.apply(event("answer","message","Original live answer"));PocketTranscript.apply(event("user","user","Ordering request"))}
  val before=PocketTranscript.rows.map{it.s("id")};assertTrue(before.indexOf("order/user")>before.indexOf("order/answer"))
  main{Pocket.scope.launchEnvironment{PocketTranscript.load(true)}};await{PocketTranscript.loading}
  main{PocketTranscript.apply(event("answer","message","Live progress during older read"))}
  await{!PocketTranscript.loading}
  val after=PocketTranscript.rows.map{it.s("id")}
  assertTrue("Boundary did not put input before its reply",after.indexOf("order/user")<after.indexOf("order/answer"))
  assertEquals(1,after.count{it=="order/header"});assertTrue(after.contains("same-message"))
  assertEquals("Live progress during older read",PocketTranscript.rows.single{it.s("id")=="order/answer"}.s("text"))
 }

}
