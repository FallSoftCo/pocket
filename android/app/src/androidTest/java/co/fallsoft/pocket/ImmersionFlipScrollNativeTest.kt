package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Actual native text, keyed lazy-list recycling, input and link activation. */
@RunWith(AndroidJUnit4::class)
class ImmersionFlipScrollNativeTest {
 @Test(timeout=120000) fun keyedRecyclingKeepsComplementaryFormsAndNativeTextWhileTyping(){
  fun step(label:String){android.util.Log.i("FlipScrollQA",label)}
  step("start")
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val d=UiDevice.getInstance(i)
  check(d.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  Configurator.getInstance().setWaitForIdleTimeout(200)
  val list=AtomicReference<LazyListState>();val coroutine=AtomicReference<CoroutineScope>()
  val clock=AtomicReference<ImmersionFlipClock>();val layouts=java.util.concurrent.ConcurrentHashMap<Int,TextLayoutResult>()
  var linkClicks=0
  val allowMotion=mutableStateOf(true)
  fun form(words:List<String>,index:Int)=buildAnnotatedString{
   words.forEachIndexed{n,word->if(n>0)append(' ');val start=length;append(word);addStringAnnotation("immersion-reserve","lexical:"+n,start,length)}
   if(index==0)addLink(LinkAnnotation.Clickable("inline-reference",linkInteractionListener={linkClicks++}),0,words.first().length)
   append(" [$index]")
  }
  val target=(0..39).associateWith{form(listOf("Una","domanda","importante"),it)}
  val meaning=(0..39).associateWith{form(listOf("A","question","important"),it)}
  val expected=(0..39).associateWith{index->immersionFlipForms(target.getValue(index),meaning.getValue(index))!!.map{it.text}}
  fun await(label:String,predicate:()->Boolean){val end=android.os.SystemClock.elapsedRealtime()+15000;while(android.os.SystemClock.elapsedRealtime()<end){if(predicate())return;Thread.sleep(50)};assertTrue(label,predicate())}
  fun rendered(index:Int)=d.findObject(By.text(java.util.regex.Pattern.compile(".*\\[$index\\]")))?.text
  fun scroll(index:Int){i.runOnMainSync{coroutine.get().launch{list.get().scrollToItem(index)}};await("List did not reach $index"){rendered(index)!=null}}
  step("launch")
  ActivityScenario.launch(ImmersionFixtureActivity::class.java).use{scenario->
   try {
   step("launched")
   scenario.onActivity{activity->step("set-content");PocketImmersion.setEnabled(true);activity.setContent{
    CompositionLocalProvider(LocalImmersionMotionState provides allowMotion.value,LocalImmersionTransitionAnimated provides false){
     MaterialTheme{ImmersionReadingWaveHost{
      val state=rememberLazyListState();val scope=rememberCoroutineScope();val flip=LocalImmersionFlip.current!!
      val measurer=rememberTextMeasurer();var typed by remember{mutableStateOf("")}
      SideEffect{list.set(state);coroutine.set(scope);clock.set(flip)}
      Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)){
       BasicTextField(typed,{typed=it},Modifier.fillMaxWidth().heightIn(min=48.dp),textStyle=TextStyle(fontSize=20.sp))
       val link=buildAnnotatedString{append("Open reference");addLink(LinkAnnotation.Clickable("reference",linkInteractionListener={linkClicks++}),0,length)}
       Text(link,Modifier.padding(vertical=12.dp))
       LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=state,verticalArrangement=Arrangement.spacedBy(28.dp)){
        items(40,key={it}){index->
         SharedInlineImmersionText(target.getValue(index),meaning.getValue(index),{1f},fontSize=20.sp,lineHeight=28.sp,onTextLayout={layout->
          val native=measurer.measure(layout.layoutInput.text,style=layout.layoutInput.style,constraints=layout.layoutInput.constraints)
          check(layout.placeholderRects.isEmpty())
          check(native.lineCount==layout.lineCount)
          for(n in layout.layoutInput.text.indices)check(kotlin.math.abs(native.getBoundingBox(n).left-layout.getBoundingBox(n).left)<.1f)
          layouts[index]=layout
         })
        }
       }
      }
     }}
    }
   }}
   step("content-set")
   await("First complementary paragraph did not render"){rendered(0) in expected.getValue(0)}
   step("first-rendered")
   val initial=rendered(0)!!;val initialPhase=clock.get().phase
   // Recycle all initial items, then return within the same reading interval.
   scroll(28);step("scrolled-away");scroll(0);step("scrolled-back")
   val returned=rendered(0)!!
   val expectedIndex=expected.getValue(0).indexOf(initial) xor (initialPhase xor clock.get().phase)
   assertEquals("Recycling reassigned the paragraph language",expected.getValue(0)[expectedIndex],returned)
   assertTrue("Native normal text spacing was not measured",layouts.isNotEmpty())
   step("spacing-checked")
   val passage=d.findObject(By.text(rendered(0)!!)).visibleBounds
   d.click(passage.left+6,passage.centerY());await("Inline link must activate once"){linkClicks==1}
   step("link-activated")
   val edit=d.findObject(By.clazz("android.widget.EditText"));edit.click();edit.text="Typing survives the language flip"
   step("typed")
   val oldPhase=clock.get().phase
   await("IME/input stopped complementary flipping"){clock.get().phase!=oldPhase}
   assertEquals("Typing draft changed", "Typing survives the language flip",d.findObject(By.clazz("android.widget.EditText")).text)
   step("flipped-with-ime")
   d.pressBack()
   await("Paragraph lost its complementary language"){rendered(0) in expected.getValue(0)}
   step("complete")
   d.takeScreenshot(java.io.File(c.cacheDir,"flip-scroll-native.png"))
   } finally {
    // Stop only the synthetic fixture clock before ActivityScenario waits for idle.
    // Live language cycling is intentionally perpetual; teardown must not wait for it.
    i.runOnMainSync { allowMotion.value=false }
   }
  }
 }
}
