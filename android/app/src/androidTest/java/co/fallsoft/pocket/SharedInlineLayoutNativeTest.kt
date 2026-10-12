package co.fallsoft.pocket

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedInlineLayoutNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun inlineReplacementKeepsUnchangedWordAtOneNativePosition()=checkGeometry(17.sp,26.sp)
 @Test fun largeTextReplacementKeepsUnchangedWordAtOneNativePosition()=checkGeometry(34.sp,52.sp)
 private fun checkGeometry(fontSize:TextUnit,lineHeight:TextUnit){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(phrase:String)=buildAnnotatedString{
   append("Please ");val at=length;append(phrase)
   addStringAnnotation("immersion-reserve","7:13",at,length)
   append(" existing sessions before opening another conversation.")
  }
  val from=form("search");val to=form("cerca fra tutti i risultati")
  val progress=mutableFloatStateOf(0f)
  var current:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(from,to,{progress.floatValue},Modifier.width(240.dp),fontSize=fontSize,lineHeight=lineHeight,onTextLayout={value->current=value})
   }}}
   fun waitLayout(text:String):TextLayoutResult{
    val deadline=System.currentTimeMillis()+5000
    while(System.currentTimeMillis()<deadline){
     compose.waitForIdle()
     var value:TextLayoutResult?=null
     i.runOnMainSync{value=current?.takeIf{it.layoutInput.text.text.replace("\u200b","")==text}}
     if(value!=null)return value!!
     Thread.sleep(25)
    }
    error("Visible shared paragraph layout unavailable")
   }
   val original=waitLayout(from.text);val a=original.getBoundingBox(original.layoutInput.text.text.indexOf("existing"))
   i.runOnMainSync{progress.floatValue=1f}
   val translated=waitLayout(to.text);val b=translated.getBoundingBox(translated.layoutInput.text.text.indexOf("existing"))
   assertTrue("Shared unchanged word moved: original=$a target=$b",
    kotlin.math.abs(a.left-b.left)<=2f&&kotlin.math.abs(a.top-b.top)<=2f)
  }
 }
 @Test fun replacementRetainsNativeInlineLinkAction(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  var clicks=0
  fun form(phrase:String)=buildAnnotatedString{
   append("Please ");val at=length;append(phrase)
   addStringAnnotation("immersion-reserve","7:13",at,length)
   addLink(LinkAnnotation.Clickable("reveal",linkInteractionListener={clicks++}),at,length)
   append(" existing sessions.")
  }
  val from=form("search");val to=form("cerca")
  var parentLayout:TextLayoutResult?=null
  val progress=mutableFloatStateOf(0f)
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(from,to,{progress.floatValue},Modifier.padding(top=64.dp).width(240.dp),onTextLayout={parentLayout=it})
   }}}
   compose.waitForIdle()
   fun tapVisibleLink(word:String){
    val device=UiDevice.getInstance(i)
    val node=device.wait(androidx.test.uiautomator.Until.findObject(
     androidx.test.uiautomator.By.text("Please $word existing sessions.")),5000)!!
    var box:androidx.compose.ui.geometry.Rect?=null
    i.runOnMainSync{box=parentLayout!!.placeholderRects.single()}
    val bounds=node.visibleBounds
    device.click(bounds.left+box!!.center.x.toInt(),bounds.top+box!!.center.y.toInt())
    compose.waitForIdle()
   }
   tapVisibleLink("search")
   i.runOnMainSync{assertEquals(1,clicks);progress.floatValue=1f}
   compose.waitForIdle()
   tapVisibleLink("cerca")
   i.runOnMainSync{assertEquals(2,clicks)}
   val actions=compose.onNodeWithText("Please cerca existing sessions.",useUnmergedTree=true)
    .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
   assertEquals("cerca",actions.single().label)
   i.runOnMainSync{assertTrue(actions.single().action());assertEquals(3,clicks)}
  }
 }

 @Test fun oversizedReplacementUsesFullNativeWrappedText(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  val source="supercalifragilisticexpialidocious"
  val target="pneumonoultramicroscopicsilicovolcanoconiosis"
  fun form(word:String)=buildAnnotatedString{
   append("Please ");val at=length;append(word)
   addStringAnnotation("immersion-reserve","7:40",at,length)
   append(" then continue.")
  }
  val progress=mutableFloatStateOf(0f)
  var fragmentLayout:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(form(source),form(target),{progress.floatValue},Modifier.width(120.dp),onFragmentLayout={fragmentLayout=it})
   }}}
   fun assertComplete(word:String){
    val layout=fragmentLayout!!
    assertEquals(word,layout.layoutInput.text.text)
    assertTrue("Long token should wrap",layout.lineCount>1)
    assertFalse("Wrapped token must not overflow height",layout.didOverflowHeight)
    assertEquals(word.length,layout.getLineEnd(layout.lineCount-1))
   }
   compose.waitForIdle();assertComplete(source)
   i.runOnMainSync{progress.floatValue=1f}
   compose.waitForIdle();assertComplete(target)
  }
 }

 @Test fun accessibilityExposesOneCoherentVisibleSentence(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(word:String)=buildAnnotatedString{
   append("Please ");val at=length;append(word)
   addStringAnnotation("immersion-reserve","7:13",at,length)
   append(" existing sessions.")
  }
  val progress=mutableFloatStateOf(0f)
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(form("search"),form("cerca"),{progress.floatValue},Modifier.padding(top=64.dp).width(240.dp))
   }}}
   fun checkSentence(word:String){
    compose.waitForIdle()
    device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.textContains(word)),5000)
    device.dumpWindowHierarchy(java.io.File(c.cacheDir,"shared-accessibility.xml"))
    val texts=device.findObjects(androidx.test.uiautomator.By.textContains(word)).mapNotNull{it.text}
    assertEquals("Visible phrase duplicated in accessibility: $texts",1,
     texts.sumOf{Regex(Regex.escape(word)).findAll(it).count()})
    assertTrue("Accessibility must preserve one ordered sentence: $texts",texts.contains("Please $word existing sessions."))
   }
   checkSentence("search")
   i.runOnMainSync{progress.floatValue=1f}
   checkSentence("cerca")
  }
 }

 @Test fun intermediateWipePreservesCommonInkAndActuallyChangesPhraseInk(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(word:String)=buildAnnotatedString{
   append("Please ");val at=length;append(word)
   addStringAnnotation("immersion-reserve","7:13",at,length)
   append(" existing sessions.")
  }
  val progress=mutableFloatStateOf(0f)
  var parent:TextLayoutResult?=null
  var geometry:ImmersionSharedWipeGeometry?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(form("search"),form("cerca fra tutti i risultati"),{progress.floatValue},
     Modifier.padding(top=64.dp).width(240.dp),onTextLayout={parent=it},onWipeGeometry={geometry=it})
   }}}
   compose.waitForIdle()
   val node=device.wait(androidx.test.uiautomator.Until.findObject(
    androidx.test.uiautomator.By.text("Please search existing sessions.")),5000)!!
   val bounds=node.visibleBounds
   var common:androidx.compose.ui.geometry.Rect?=null;var phrase:androidx.compose.ui.geometry.Rect?=null
   i.runOnMainSync{
    val layout=parent!!
    common=layout.getBoundingBox(layout.layoutInput.text.text.indexOf("existing"))
    phrase=layout.placeholderRects.first()
   }
   val baseline=i.uiAutomation.takeScreenshot()!!
   i.runOnMainSync{progress.floatValue=.65f}
   compose.mainClock.advanceTimeBy(32)
   compose.waitForIdle()
   assertTrue("Native text must hand off before sampling its frame",
    device.wait(androidx.test.uiautomator.Until.hasObject(
     androidx.test.uiautomator.By.text("Please cerca fra tutti i risultati existing sessions.")),5000))
   device.waitForIdle()
   var middle=i.uiAutomation.takeScreenshot()!!
   fun differences(rect:androidx.compose.ui.geometry.Rect):Int{
    var changed=0
    for(y in (bounds.top+rect.top.toInt()).coerceAtLeast(0) until (bounds.top+rect.bottom.toInt()).coerceAtMost(baseline.height)){
     for(x in (bounds.left+rect.left.toInt()).coerceAtLeast(0) until (bounds.left+rect.right.toInt()).coerceAtMost(baseline.width)){
      if(baseline.getPixel(x,y)!=middle.getPixel(x,y))changed++
     }
    }
    return changed
   }
   fun colors(rect:androidx.compose.ui.geometry.Rect)=buildSet<Int>{
    for(y in bounds.top+rect.top.toInt() until bounds.top+rect.bottom.toInt()){
     for(x in bounds.left+rect.left.toInt() until bounds.left+rect.right.toInt())add(baseline.getPixel(x,y))
    }
   }.size
   val paintStarted=android.os.SystemClock.elapsedRealtime()
   while(differences(phrase!!)<=10&&android.os.SystemClock.elapsedRealtime()-paintStarted<2000){
    middle.recycle();Thread.sleep(50);middle=i.uiAutomation.takeScreenshot()!!
   }
   val a=IntArray(baseline.width*baseline.height);val b=IntArray(a.size)
   baseline.getPixels(a,0,baseline.width,0,0,baseline.width,baseline.height)
   middle.getPixels(b,0,middle.width,0,0,middle.width,middle.height)
   java.io.File(c.cacheDir,"shared-wipe-pixels.json").writeText(
    org.json.JSONObject().put("bounds",bounds.toString()).put("common",common.toString())
     .put("phrase",phrase.toString()).put("geometry",geometry.toString())
     .put("commonColors",colors(common!!)).put("phraseColors",colors(phrase!!))
     .put("paintObservationWaitMs",android.os.SystemClock.elapsedRealtime()-paintStarted)
     .put("allChangedPixels",a.indices.count{a[it]!=b[it]})
     .put("phraseChangedPixels",differences(phrase!!)).toString())
   try{
    assertTrue("Screenshot must contain actual common glyph ink",colors(common!!)>2)
    assertEquals("Unchanged ink should not fade or shift",0,differences(common!!))
    assertTrue("A mid-wipe frame must actually change replacement ink",differences(phrase!!)>10)
   }finally{baseline.recycle();middle.recycle()}
  }
 }

 @Test fun laterPhraseCycleCannotReflowAlreadyReservedSurroundingText(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(first:String,second:String)=buildAnnotatedString{
   append("Please ");var at=length;append(first)
   addStringAnnotation("immersion-reserve","first",at,length)
   append(" existing sessions and ");at=length;append(second)
   addStringAnnotation("immersion-reserve","second",at,length)
   append(" before continuing.")
  }
  val original=form("search","open")
  val first=form("cerca fra tutti i risultati","open")
  val both=form("cerca fra tutti i risultati","apri una nuova conversazione")
  val cycle=androidx.compose.runtime.mutableIntStateOf(0)
  val reserveAll=androidx.compose.runtime.mutableStateOf(true)
  val progress=mutableFloatStateOf(0f)
  var current:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(if(cycle.intValue==0)original else first,
     if(cycle.intValue==0)first else both,{progress.floatValue},Modifier.width(240.dp),
     reserve=if(reserveAll.value)listOf(original,both)else emptyList(),onTextLayout={current=it})
   }}}
   fun passage(text:String):androidx.compose.ui.geometry.Rect{
    val deadline=System.currentTimeMillis()+5000
    while(System.currentTimeMillis()<deadline){
     compose.waitForIdle()
     var layout:TextLayoutResult?=null
     i.runOnMainSync{layout=current?.takeIf{it.layoutInput.text.text.replace("\u200b","")==text}}
     if(layout!=null)return layout!!.getBoundingBox(layout!!.layoutInput.text.text.indexOf("before"))
     Thread.sleep(25)
    }
    error("Current cycle layout unavailable")
   }
   val a=passage(original.text)
   i.runOnMainSync{progress.floatValue=1f}
   val b=passage(first.text)
   i.runOnMainSync{cycle.intValue=1;progress.floatValue=0f}
   val c1=passage(first.text)
   i.runOnMainSync{progress.floatValue=1f}
   val d=passage(both.text)
   for(rect in listOf(b,c1,d))assertTrue("Later cue reflowed common text: $a -> $rect",
    kotlin.math.abs(a.left-rect.left)<=2f&&kotlin.math.abs(a.top-rect.top)<=2f)
   i.runOnMainSync{reserveAll.value=false;cycle.intValue=0;progress.floatValue=0f}
   val missingReservation=passage(original.text)
   assertTrue("Fixture must detect the missing cross-cycle reservation: $a -> $missingReservation",
    kotlin.math.abs(a.left-missingReservation.left)>2f||kotlin.math.abs(a.top-missingReservation.top)>2f)
  }
 }

}
