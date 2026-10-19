package co.fallsoft.pocket

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NaturalImmersionNativeTest {
 @Test(timeout=90000) fun naturalParagraphsKeepNativeSpacingAndGlobalWaveRunsWhileTyping(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  val clock=AtomicReference<ImmersionReadingWaveClock?>()
  val nativeLayout=AtomicReference<TextLayoutResult?>()
  val expected=AtomicReference<TextLayoutResult?>()
  val bottom=AtomicReference<Rect?>()
  val paragraphOrigin=AtomicReference(Offset.Zero)
  val actions=java.util.concurrent.atomic.AtomicInteger()
  val marker=AtomicReference<Rect?>()
  var visualArmed=false
  var visualSamples=0
  var lastVisualAt=0L
  fun markerIntact():Boolean{
   val box=marker.get()?:return false
   val bitmap=i.uiAutomation.takeScreenshot()?:return false
   try{
    visualSamples++
    val y=((box.top+box.bottom)/2).toInt().coerceIn(0,bitmap.height-1)
    val xs=listOf(box.left+box.width*.25f,box.left+box.width*.75f)
    val intact=xs.all{x->bitmap.getPixel(x.toInt().coerceIn(0,bitmap.width-1),y)==0xff00cc88.toInt()}
    if(!intact&&visualArmed)c.filesDir.resolve("natural-immersion-outside-ink-failure.png").outputStream().use{
     bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
    }
    return intact
   }finally{bitmap.recycle()}
  }

  val typedLength=java.util.concurrent.atomic.AtomicInteger()
  val expectedLength=java.util.concurrent.atomic.AtomicInteger(Int.MAX_VALUE)
  val submittedKey=java.util.concurrent.atomic.AtomicLong()
  val drawnKey=java.util.concurrent.atomic.AtomicLong()
  val keyToDraw=mutableListOf<Double>()
  val drawListener=android.view.ViewTreeObserver.OnDrawListener{
   if(submittedKey.get()>0&&typedLength.get()>=expectedLength.get())drawnKey.compareAndSet(0,System.nanoTime())
  }
  val frames=java.util.Collections.synchronizedList(mutableListOf<Long>())
  val metrics=android.view.Window.OnFrameMetricsAvailableListener{_,value,_->frames.add(value.getMetric(android.view.FrameMetrics.TOTAL_DURATION))}
  val action=LinkAnnotation.Clickable("owned-grammar-action",linkInteractionListener=LinkInteractionListener{actions.incrementAndGet()})
  fun actionable(form:AnnotatedString)=buildAnnotatedString{append(form);addLink(action,0,length)}
  val source=authoredImmersionScaffold("I am not checking the latest changes.",listOf(
   "Non sto" to "Not-am"," " to " ","controllando" to "checking"," " to " ",
   "le ultime" to "the-latest"," " to " ","modifiche." to "changes."))
  val support=actionable(requireNotNull(immersionGrammarForm(source,true,"fixture")))
  val italian=actionable(requireNotNull(immersionGrammarForm(source,false,"fixture")))
  assertEquals("Not-am checking the-latest changes.",support.text)
  assertTrue(supportedImmersionPlanVersion("transgrammatic-english-italian-v2"))
  assertTrue(supportedImmersionPlanVersion("transgrammatic-english-italian-v1"))
  assertFalse(supportedImmersionPlanVersion("unknown"))
  val monitor=i.addMonitor(MainActivity::class.java.name,null,false)
  c.startActivity(android.content.Intent(c,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
  val activity=i.waitForMonitorWithTimeout(monitor,10000) as? MainActivity
  assertNotNull("Native activity monitor unavailable",activity)
  try{
   i.runOnMainSync{activity!!.window.addOnFrameMetricsAvailableListener(metrics,android.os.Handler(android.os.Looper.getMainLooper()));activity.window.decorView.viewTreeObserver.addOnDrawListener(drawListener);activity.setContent{MaterialTheme{
    CompositionLocalProvider(LocalImmersionMotionState provides true,LocalImmersionTransitionAnimated provides true){
     ImmersionReadingWaveHost{
      val wave=LocalImmersionReadingWave.current
      val measurer=rememberTextMeasurer()
      var typed by remember{mutableStateOf("")}
      SideEffect{typedLength.set(typed.length)}
      Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().imePadding().padding(20.dp),
       verticalArrangement=Arrangement.spacedBy(20.dp)){
       Box(Modifier.fillMaxWidth().height(4.dp).background(androidx.compose.ui.graphics.Color(0xff00cc88))
        .onGloballyPositioned{layout->val origin=layout.localToScreen(Offset.Zero);marker.set(Rect(origin,androidx.compose.ui.geometry.Size(layout.size.width.toFloat(),layout.size.height.toFloat())))})
       BasicTextField(typed,{typed=it},Modifier.fillMaxWidth().heightIn(min=48.dp),
        textStyle=androidx.compose.ui.text.TextStyle(color=Paper,fontSize=20.sp))
       SharedInlineImmersionText(support,italian,{1f},Modifier.width(280.dp).onGloballyPositioned{paragraphOrigin.set(it.localToScreen(Offset.Zero))},
        fontSize=20.sp,lineHeight=28.sp,readingWave=wave,trackWholeTextVisibility=true,
        onTextLayout={layout->
         nativeLayout.set(layout)
         expected.set(measurer.measure(layout.layoutInput.text,style=layout.layoutInput.style,
          constraints=layout.layoutInput.constraints,maxLines=layout.layoutInput.maxLines,
          overflow=layout.layoutInput.overflow,softWrap=layout.layoutInput.softWrap))
        })
       var point by remember{mutableStateOf(Offset.Zero)}
       SharedInlineImmersionText(AnnotatedString("Permissions full for the new tasks"),
        AnnotatedString("Permessi completi per i nuovi incarichi"),{1f},
        Modifier.width(280.dp).onGloballyPositioned{point=it.localToScreen(Offset.Zero)},
        fontSize=20.sp,lineHeight=28.sp,readingWave=wave,trackWholeTextVisibility=true,
        onWipeGeometry={geometry->
         geometry.regions.values.maxByOrNull{it.bottom}?.let{r->
          bottom.set(r.translate(point-(wave?.viewportOrigin?:Offset.Zero)))
         }
        })
      }
      SideEffect{clock.set(wave)}
     }
    }
   }}}
   fun await(message:String,timeout:Long=20000,test:()->Boolean){
    val end=SystemClock.elapsedRealtime()+timeout
    while(SystemClock.elapsedRealtime()<end){
     if(visualArmed&&SystemClock.elapsedRealtime()-lastVisualAt>=250){
      lastVisualAt=SystemClock.elapsedRealtime()
      assertTrue("Animated text clipped independent native content",markerIntact())
     }
     if(test())return;Thread.sleep(50)
    }
    assertTrue(message+" [layout="+(nativeLayout.get()!=null)+", expected="+(expected.get()!=null)+", lower="+(bottom.get()!=null)+", visible="+clock.get()?.hasVisible+"]",test())
   }
   await("Natural native text did not layout"){nativeLayout.get()!=null&&expected.get()!=null&&bottom.get()!=null&&clock.get()?.hasVisible==true}
   fun checkNatural(){
    val actual=requireNotNull(nativeLayout.get());val normal=requireNotNull(expected.get())
    assertTrue("Per-word placeholder reservations remain",actual.placeholderRects.isEmpty())
    assertEquals(normal.lineCount,actual.lineCount)
    for(n in 0 until actual.lineCount)assertEquals(normal.getLineEnd(n),actual.getLineEnd(n))
    for(n in actual.layoutInput.text.text.indices)
     assertEquals("Glyph spacing differs from normal paragraph at "+n,normal.getBoundingBox(n).left,actual.getBoundingBox(n).left,.1f)
   }
   i.runOnMainSync{checkNatural()}
   await("Independent marker did not draw"){markerIntact()}
   visualArmed=true
   device.takeScreenshot(c.filesDir.resolve("natural-immersion-english.png"))
   device.findObject(By.clazz("android.widget.EditText")).click()
   device.findObject(By.clazz("android.widget.EditText")).text="Owned typing stays responsive"
   val before=clock.get()!!.elapsedMs
   Thread.sleep(400)
   assertTrue("Open keyboard/tap paused the global reading wave",clock.get()!!.elapsedMs>before+150)
   assertEquals("Owned typing stays responsive",device.findObject(By.clazz("android.widget.EditText")).text)
   repeat(12){
    expectedLength.set(typedLength.get()+1);drawnKey.set(0);submittedKey.set(System.nanoTime())
    assertTrue(device.pressKeyCode(android.view.KeyEvent.KEYCODE_A))
    await("A physical key event did not draw",3000){drawnKey.get()>0}
    keyToDraw+=(drawnKey.get()-submittedKey.get())/1e6;submittedKey.set(0)
   }
   assertTrue("Typed keys disappeared",device.findObject(By.clazz("android.widget.EditText")).text.endsWith("aaaaaaaaaaaa"))
   device.takeScreenshot(c.filesDir.resolve("natural-immersion-typing.png"))
   device.pressBack()
   await("Global waterfall did not reach the lower container",30000){
    val rect=bottom.get()?:return@await false
    val mask=clock.get()?.lineMask(rect.top,false)?:return@await false
    mask.incomingAtReadingStart&&mask.center-mask.feather>=rect.right
   }
   i.runOnMainSync{checkNatural()}
   device.takeScreenshot(c.filesDir.resolve("natural-immersion-italian.png"))
   val glyph=nativeLayout.get()!!.getBoundingBox(0).center+paragraphOrigin.get()
   device.click(glyph.x.toInt(),glyph.y.toInt())
   await("Visible link action did not run exactly once"){actions.get()==1}
   Thread.sleep(200);assertEquals("Link action ran twice",1,actions.get())
   await("Global wave did not return to the English grammatical form",30000){
    val rect=bottom.get()?:return@await false
    val mask=clock.get()?.lineMask(rect.top,false)?:return@await false
    !mask.incomingAtReadingStart&&mask.center-mask.feather>=rect.right
   }
   i.runOnMainSync{checkNatural()}
   assertEquals(support.text,nativeLayout.get()!!.layoutInput.text.text)
   device.takeScreenshot(c.filesDir.resolve("natural-immersion-english-return.png"))
   val samples=synchronized(frames){frames.toList().sorted()}
   val measured=org.json.JSONObject().put("samples",samples.size)
   if(samples.isNotEmpty())measured.put("p50Ms",samples[samples.size/2]/1e6).put("p95Ms",samples[((samples.size-1)*.95).toInt()]/1e6).put("maxMs",samples.last()/1e6)
   c.filesDir.resolve("natural-immersion-receipt.json").writeText(org.json.JSONObject()
    .put("naturalGlyphSpacing",true).put("lexicalPlaceholders",0)
    .put("globalWaveReachedLowerContainer",true).put("keyboardDoesNotPauseWave",true)
    .put("outsideInkSamples",visualSamples).put("independentContentIntact",true)
    .put("returnedToEnglish",true).put("visibleLinkActionCount",actions.get()).put("windowFrames",measured)
    .put("sourceForm",support.text).put("targetForm",italian.text)
    .put("keyToDrawMs",org.json.JSONObject().put("samples",keyToDraw.size)
     .put("p50",keyToDraw.sorted()[keyToDraw.size/2]).put("p95",keyToDraw.sorted()[((keyToDraw.size-1)*.95).toInt()])
     .put("max",keyToDraw.max()))
    .put("actualTeacherModel",false).put("physicalPhone",false).toString())
  }finally{i.runOnMainSync{activity?.window?.removeOnFrameMetricsAvailableListener(metrics);activity?.window?.decorView?.viewTreeObserver?.removeOnDrawListener(drawListener);activity?.finish()};i.removeMonitor(monitor)}
 }
}
