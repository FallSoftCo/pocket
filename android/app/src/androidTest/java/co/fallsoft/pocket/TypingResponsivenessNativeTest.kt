package co.fallsoft.pocket
import android.content.Intent
import android.os.*
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/** Diagnostic: no external worker, submission or real account. Existing profiles retained. */
@RunWith(AndroidJUnit4::class)
class TypingResponsivenessNativeTest {
 @Test fun stockConversationMeasuresMotionAndMainThreadStallsWithoutSending(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  fun main(f:()->Unit)=i.runOnMainSync(f)
  fun await(label:String,f:()->Boolean){val end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){if(f())return;Thread.sleep(10)};assertTrue(label,f())}
  main{Pocket.init(c)}
  val original=Pocket.environmentId;val p=c.getSharedPreferences("pocket",0);val profile=EnvironmentIdentity("typing_perf_20261015","Isolated typing diagnosis")
  val all=EnvironmentProfiles(p).all().filter{it.id!=profile.id}+profile
  p.edit().putString("environments:v1",JSONArray(all.map{JSONObject().put("id",it.id).put("name",it.name).put("local",it.local)}).toString())
   .putString(profile.key("server"),"http://127.0.0.1:18891").putString(profile.key("token"),"synthetic-system76").putString(profile.key("deviceId"),"synthetic-device-a").commit()
  val run=AtomicBoolean(true);val stacks=ConcurrentHashMap<String,Int>();val h=Handler(Looper.getMainLooper())
  val watchdog=Thread{
   while(run.get()){
    val ack=java.util.concurrent.CountDownLatch(1);val start=SystemClock.elapsedRealtimeNanos();h.post{ack.countDown()}
    if(!ack.await(32,java.util.concurrent.TimeUnit.MILLISECONDS)){
     val trace=Looper.getMainLooper().thread.stackTrace.take(12).joinToString("\n"){it.toString()}
     stacks.merge(trace,1){a,b->a+b};ack.await(2,java.util.concurrent.TimeUnit.SECONDS)
    }
    Thread.sleep(16)
   }
  }
  val oldIdle=Configurator.getInstance().waitForIdleTimeout;Configurator.getInstance().waitForIdleTimeout=0
  val reports=JSONArray()
  val drawn=java.util.concurrent.atomic.AtomicInteger(-1);val drawAt=java.util.concurrent.atomic.AtomicLong(0)
  var drawnView:android.view.View?=null;var drawListener:android.view.ViewTreeObserver.OnDrawListener?=null
  val largePrefs=InstrumentationRegistry.getArguments().getString("largePrefs")=="true"
  try{
   main{Pocket.activateEnvironment(profile.id,force=true);c.startActivity(Intent(c,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Pocket.refresh()}
   await("Synthetic catalog not ready"){Pocket.tasks.isNotEmpty()&&Pocket.environmentId==profile.id}
   main{Pocket.open("same-thread",keyboard=true);PocketImmersion.setEnabled(true);PocketImmersion.setSupportEnabled(true)}
   await("Synthetic history not ready"){PocketTranscript.rows.isNotEmpty()&&!PocketTranscript.loading}
   // Exercise a real delayed history read without allowing read freshness to erase work evidence.
   kotlinx.coroutines.runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/hold-history",JSONObject())}
   try{
    main{
     PocketImmersion.setEnabled(false)
     PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","toolbar-live").put("version",900).put("turn",JSONObject().put("status","inProgress")))
     Pocket.refreshDetail()
    }
    await("Delayed refresh did not start"){PocketTranscript.loading}
    assertTrue("Refresh discarded verified work state",PocketTranscript.currentWorkVerified)
    await("Working toolbar disappeared during history refresh"){d.hasObject(By.text("Working"))}
    for(kind in listOf("thinking","command","message","thinking")){
     main{Pocket.tasks=Pocket.tasks.map{if(it.id=="same-thread")it.copy(status="active",previewKind=kind)else it}}
     Thread.sleep(120)
     assertTrue("Activity phase changed toolbar lifecycle",d.hasObject(By.text("Working")))
    }
   }finally{kotlinx.coroutines.runBlocking{Pocket.apiEnvironment(requireNotNull(Pocket.captureEnvironment()),"/_qa/release-history",JSONObject())}}
   await("History refresh did not settle"){!PocketTranscript.loading}
   main{PocketImmersion.setEnabled(true)}
   val stress=InstrumentationRegistry.getArguments().getString("stress")=="true"
   val phrase="Full permissions for new tasks. ";val text=phrase.repeat(if(stress)100 else 20)
   main{
    if(stress){
     val target="Permessi completi per nuove attività. ";val spans=JSONArray()
     repeat(100){n->spans.put(JSONObject().put("start",n*phrase.length).put("end",(n+1)*phrase.length).put("source",phrase).put("target",target))}
     PocketImmersion.accept(JSONObject().put("translations",JSONArray().put(JSONObject().put("id","typing-plan").put("planVersion","inline-replacement-v3").put("density",PocketImmersion.density).put("version",immersionSourceHash(text)).put("original",text).put("text",target.repeat(100)).put("spans",spans))))
    }
    for(n in 0..(if(stress)199 else 79))PocketTranscript.apply(JSONObject().put("threadId","same-thread").put("turnId","typing-load").put("version",1000+n).put("row",JSONObject().put("id","typing-load-$n").put("turnId","typing-load").put("kind","message").put("text",text)))
   }
   val field=By.clazz("android.widget.EditText");await("Editor missing"){d.findObject(field)!=null};d.findObject(field).click()
   val key=Pocket.key("draft:same-thread");val initial=p.getString(key,"").orEmpty().length;var count=initial
   if(largePrefs){val e=p.edit();repeat(10000){e.putString("typing_perf_cache:$it","x".repeat(512))};check(e.commit())}
   main{
    drawnView=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).first().window.decorView
    drawListener=android.view.ViewTreeObserver.OnDrawListener{drawn.set(p.getString(key,"").orEmpty().length);drawAt.set(SystemClock.elapsedRealtimeNanos())}
    drawnView!!.viewTreeObserver.addOnDrawListener(drawListener)
   }
   watchdog.start()
   for(motion in listOf(false,true,false,true)){
    main{PocketImmersion.setMotionEnabled(motion)};Thread.sleep(700)
    val values=JSONArray();val draws=JSONArray()
    repeat(24){
     val start=SystemClock.elapsedRealtimeNanos();val t=SystemClock.uptimeMillis()
     assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(t,t,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_A,0),true))
     assertTrue(i.uiAutomation.injectInputEvent(KeyEvent(t,t,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_A,0),true));count++
     val end=SystemClock.elapsedRealtime()+4000
     while(p.getString(key,"").orEmpty().length<count&&SystemClock.elapsedRealtime()<end)Thread.sleep(2)
     assertEquals("Dropped keystroke",count,p.getString(key,"").orEmpty().length)
     values.put((SystemClock.elapsedRealtimeNanos()-start)/1e6)
    val drawDeadline=SystemClock.elapsedRealtime()+4000
    while(drawn.get()<count&&SystemClock.elapsedRealtime()<drawDeadline)Thread.sleep(2)
    assertTrue("Saved keystroke did not reach a draw frame",drawn.get()>=count)
    draws.put((drawAt.get()-start)/1e6);Thread.sleep(35)
    }
    reports.put(JSONObject().put("motion",motion).put("dispatchToDraftMs",values).put("dispatchToDrawCallbackMs",draws))
   }
   c.filesDir.resolve("typing-responsiveness.json").writeText(JSONObject().put("scope","Software emulator; hardware-key dispatch through production stock conversation to saved draft; not physical screen latency").put("runs",reports).put("mainStallStacks",JSONObject(stacks as Map<*,*>)).put("messagesSent",0).toString())
   assertTrue("Back swipe was not injected",d.swipe(1,d.displayHeight/3,d.displayWidth/2,d.displayHeight/3,30))
   await("One Back swipe with IME open did not return to the session catalog"){d.hasObject(By.res("session-catalog-microphone"))}
   assertTrue("Shared session catalog paging controls missing",d.hasObject(By.res("reader-page-up"))&&d.hasObject(By.res("reader-page-down")))
   assertTrue("Shared session catalog volume control missing",d.hasObject(By.desc("Speech volume")))
   d.takeScreenshot(c.filesDir.resolve("session-catalog-shared-dock.png"))
  }catch(e:Throwable){
   d.takeScreenshot(c.filesDir.resolve("shared-stock-catalog-failure.png"));d.dumpWindowHierarchy(c.filesDir.resolve("shared-stock-catalog-failure.xml"));throw e
  }finally{if(largePrefs){val e=p.edit();repeat(10000){e.remove("typing_perf_cache:$it")};e.commit()};run.set(false);if(watchdog.isAlive)watchdog.join(3000);Configurator.getInstance().waitForIdleTimeout=oldIdle;main{drawListener?.let{drawnView?.viewTreeObserver?.removeOnDrawListener(it)};Pocket.activateEnvironment(original)}}
 }
}
