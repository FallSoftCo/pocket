package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Actual display clock, real scrolling/IME, rendered ink and stable line identity. */
@RunWith(AndroidJUnit4::class)
class ImmersionWaterfallScrollNativeTest {
 @Test fun waterfallRemainsSpatialAndScrollDoesNotReassignLanguage(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",0).edit().clear().commit()
  i.runOnMainSync{Pocket.init(c);PocketImmersion.setEnabled(true);PocketImmersion.setMotionEnabled(true);PocketImmersion.setSupportEnabled(true)}
  val active=AtomicReference<MainActivity>();val clock=AtomicReference<ImmersionReadingWaveClock?>();val list=AtomicReference<LazyListState?>();val bounds=AtomicReference<Rect?>()
  val frames=java.util.Collections.synchronizedList(mutableListOf<Long>())
  val metrics=android.view.Window.OnFrameMetricsAvailableListener{_,frame,_->frames.add(frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION))}
  fun await(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+25000
   while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(20)};assertTrue(label,predicate())}
  fun observations():Pair<Long,List<ImmersionReadingWaveClock.Observation>>{
   var result=0L to emptyList<ImmersionReadingWaveClock.Observation>()
   i.runOnMainSync{result=clock.get()!!.elapsedMs to clock.get()!!.observeLines()};return result
  }
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->
    active.set(activity)
    activity.window.addOnFrameMetricsAvailableListener(metrics,android.os.Handler(android.os.Looper.getMainLooper()))
    activity.setContent{MaterialTheme{
     ImmersionReadingWaveHost{
      val wave=LocalImmersionReadingWave.current;val state=rememberLazyListState();var text by remember{mutableStateOf("")}
      SideEffect{clock.set(wave);list.set(state)}
      Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().imePadding()){
       BasicTextField(text,{text=it},Modifier.fillMaxWidth().heightIn(min=44.dp).padding(8.dp),textStyle=TextStyle(color=Paper,fontSize=20.sp))
       LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{layout->bounds.set(Rect(layout.localToScreen(Offset.Zero),androidx.compose.ui.geometry.Size(layout.size.width.toFloat(),layout.size.height.toFloat())))},
        state=state,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        items(40,key={it}){index->
         val italian=buildAnnotatedString{withStyle(SpanStyle(color=androidx.compose.ui.graphics.Color(0xff22dd88))){append("Una domanda importante "+index)}}
         val english=buildAnnotatedString{withStyle(SpanStyle(color=androidx.compose.ui.graphics.Color(0xffee5588))){append("A question important "+index)}}
         ImmersionSelection(italian.text){SharedInlineImmersionText(italian,english,{1f},fontSize=20.sp,lineHeight=28.sp,readingWave=wave,trackWholeTextVisibility=true)}
        }
       }
       Button({},Modifier.fillMaxWidth().height(48.dp)){Text("QA action")}
      }
     }
    }}
   }
   await("No actual visible lines enrolled"){clock.get()?.hasVisible==true}
   await("Natural display clock failed to reach a spatial handoff"){clock.get()!!.elapsedMs%ImmersionReadingWaveClock.HALF_MS in 1800L..2150L}
   val bitmap=i.uiAutomation.takeScreenshot()!!
   try{
    val area=bounds.get()!!;var italian=0;var english=0
    for(y in area.top.toInt().coerceAtLeast(0) until area.bottom.toInt().coerceAtMost(bitmap.height) step 3)
     for(x in area.left.toInt().coerceAtLeast(0) until area.right.toInt().coerceAtMost(bitmap.width) step 3){
      val pixel=bitmap.getPixel(x,y);val r=android.graphics.Color.red(pixel);val g=android.graphics.Color.green(pixel);val b=android.graphics.Color.blue(pixel)
      if(g>150&&r<100&&b>70)italian++
      if(r>170&&g<140&&b>80)english++
     }
    c.cacheDir.resolve("waterfall-two-languages.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    assertTrue("Whole-page handoff: no Italian ink during waterfall",italian>100)
    assertTrue("Whole-page handoff: no English ink during waterfall",english>100)
   }finally{bitmap.recycle()}
   val before=observations()
   val box=bounds.get()!!
   d.swipe(box.center.x.toInt(),(box.top+box.height*.65f).toInt(),box.center.x.toInt(),(box.top+box.height*.35f).toInt(),12)
   Thread.sleep(100)
   val after=observations()
   val old=before.second.associateBy{it.identity};val common=after.second.filter{it.identity in old}
   assertTrue("Scroll fixture retained no line identities",common.isNotEmpty())
   assertEquals("Scroll crossed a half-wave boundary; cannot compare its onset",before.first/ImmersionReadingWaveClock.HALF_MS,after.first/ImmersionReadingWaveClock.HALF_MS)
   val velocity=(clock.get()!!.viewportWidthPx+2*(clock.get()!!.viewportWidthPx*.055f).coerceAtLeast(12f))/ImmersionReadingWaveClock.WIPE_MS
   val allowance=(after.first-before.first)*velocity+2f
   common.forEach{now->
    val then=old.getValue(now.identity)
    assertTrue("Scroll reassigned a line phase instead of advancing elapsed time",kotlin.math.abs(now.mask.center-then.mask.center)<=allowance)
    assertEquals("Scroll reversed a line's language",then.mask.incomingAtReadingStart,now.mask.incomingAtReadingStart)
   }
   d.takeScreenshot(c.cacheDir.resolve("waterfall-after-scroll.png"))
   val started=clock.get()!!.elapsedMs
   d.findObject(By.clazz("android.widget.EditText")).click()
   repeat(6){d.pressKeyCode(android.view.KeyEvent.KEYCODE_A)}
   Thread.sleep(350)
   assertTrue("Typing or IME stopped the global wave",clock.get()!!.elapsedMs>started+100)
   assertEquals("Typed input lost", "aaaaaa",d.findObject(By.clazz("android.widget.EditText")).text)
   d.pressBack()
   val end=android.os.SystemClock.elapsedRealtime()+12000;val trace=JSONArray()
   while(android.os.SystemClock.elapsedRealtime()<end){
    val sample=observations()
    trace.put(JSONObject().put("elapsedMs",sample.first).put("lines",JSONArray(sample.second.map{
     JSONObject().put("id",it.identity).put("top",it.rect.top).put("center",it.mask.center).put("incoming",it.mask.incomingAtReadingStart)
    })))
    Thread.sleep(150)
   }
   c.cacheDir.resolve("waterfall-trace.json").writeText(trace.toString())
   val durations=synchronized(frames){frames.sorted()}
   c.cacheDir.resolve("waterfall-result.json").writeText(JSONObject().put("passed",true).put("clock","actual-native-display")
    .put("scrollCommonLines",common.size).put("scrollElapsedMs",after.first-before.first).put("mixedItalianEnglishInk",true)
    .put("frames",durations.size).put("p50FrameMs",durations[durations.size/2]/1e6).put("p95FrameMs",durations[((durations.size-1)*.95).toInt()]/1e6).toString())
   i.runOnMainSync{active.get().window.removeOnFrameMetricsAvailableListener(metrics);active.get().setContent{}}
  }
 }
}
