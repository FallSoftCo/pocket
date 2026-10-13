package co.fallsoft.pocket

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import org.junit.Before
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedInlineLayoutNativeTest {
 @get:Rule val compose=createEmptyComposeRule()
 @Before fun ownedEmulatorPermissionSetup(){
  val i=InstrumentationRegistry.getInstrumentation()
  val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  device.executeShellCommand("pm grant "+i.targetContext.packageName+" android.permission.POST_NOTIFICATIONS")
 }


 @Test fun targetGrammarScaffoldKeepsVocabularyAtTheSameNativePositions(){
  val permissions=requireNotNull(InterfaceGrammarScaffolds.find(
   "Full permissions for new tasks","Permessi completi per i nuovi incarichi"))
  val negative=authoredImmersionScaffold("I am not checking the model",listOf(
   "Non" to "not"," " to " ","sto" to "am"," " to " ","controllando" to "checking",
   " " to " ","il" to "the"," " to " ","modello" to "model")).let{span->
   span.copy(targetSegments=span.targetSegments.mapIndexed{index,segment->when(index){
    0->segment.copy(role="adverb",relations=listOf(ImmersionRelation("negates",2)))
    2->segment.copy(role="auxiliary")
    6->segment.copy(role="determiner")
    else->segment
   }})
  }
  val contraction=authoredImmersionScaffold("Open the parent task",listOf(
   "Apri" to "Open"," " to " ","l’incarico" to "l’task"," " to " ","principale" to "parent"))
  assertEquals("Permissions full for the new tasks",immersionGrammarSupport(permissions))
  assertEquals("not am checking the model",immersionGrammarSupport(negative))
  assertEquals("Open l’task parent",immersionGrammarSupport(contraction))
  for(span in listOf(permissions,negative,contraction)){
   val from=requireNotNull(immersionGrammarForm(span,false,"phrase"))
   val to=requireNotNull(immersionGrammarForm(span,true,"phrase"))
   val progress=mutableFloatStateOf(0f)
   var layout:TextLayoutResult?=null
   ActivityScenario.launch(MainActivity::class.java).use{scenario->
    scenario.onActivity{it.setContent{MaterialTheme{
     androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()){
      SharedInlineImmersionText(from,to,{progress.floatValue},Modifier.width(220.dp),
       fontSize=24.sp,lineHeight=34.sp,onTextLayout={value->layout=value})
     }
    }}}
    fun observed(text:String):TextLayoutResult {
     var result:TextLayoutResult?=null
     compose.waitUntil(5000){
      compose.runOnIdle{result=layout?.takeIf{it.layoutInput.text.text==text}}
      result!=null
     }
     return requireNotNull(result)
    }
    val a=observed(from.text)
    val before=a.placeholderRects.toList()
    compose.runOnIdle{progress.floatValue=1f}
    val b=observed(to.text)
    assertEquals("Lexical slot count changed",before.size,b.placeholderRects.size)
    assertTrue("No lexical placeholders were laid out",before.any{it!=null})
    before.zip(b.placeholderRects).forEachIndexed{index,(left,right)->
     assertEquals("Vocabulary slot $index moved for "+span.target,left,right)
    }
    assertEquals("Canonical source changed",span.source,when(span){
     permissions->"Full permissions for new tasks"
     negative->"I am not checking the model"
     else->"Open the parent task"
    })
   }
  }
 }

 @Test fun interfaceTemplatesCycleWithoutChangingLiteralValuesOrActions(){
  val instrument=InstrumentationRegistry.getInstrumentation()
  val context=instrument.targetContext
  // Each journey owns its synthetic pairing; earlier navigation cases deliberately
  // leave different profiles selected. Never inherit their state or contact a live host.
  context.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear()
   .putString("server","http://127.0.0.1:18880").putString("token","synthetic-system76")
   .putString("deviceId","synthetic-device").putBoolean("activeLocal",false).commit()
  instrument.runOnMainSync{Pocket.init(context)}
  val literal="/tmp/Review 100%/résultat.json"
  val original=immersionGrammarSupport(InterfaceTemplate.GENERATED_ARTIFACT.scaffold(literal))
  val target=InterfaceTemplate.GENERATED_ARTIFACT.target(literal)
  val countsOriginal=immersionGrammarSupport(InterfaceTemplate.COMMAND_RESULTS.scaffold(7,5))
  val countsTarget=InterfaceTemplate.COMMAND_RESULTS.target(7,5)
  val creditPeriod="Oct 12 10:00 – Oct 12 11:00"
  val creditsOriginal=immersionGrammarSupport(InterfaceTemplate.CREDIT_OBSERVATION.scaffold("62.57",creditPeriod))
  val creditsTarget=InterfaceTemplate.CREDIT_OBSERVATION.target("62.57",creditPeriod)
  val teamLiteral="Review 100% | équipe ~ native"
  val teamOriginal=immersionGrammarSupport(InterfaceTemplate.TEAM_COORDINATOR.scaffold(teamLiteral))
  val teamTarget=InterfaceTemplate.TEAM_COORDINATOR.target(teamLiteral)
  val attentionOriginal=immersionGrammarSupport(InterfaceTemplate.TEAM_ATTENTION.scaffold(teamLiteral))
  val attentionTarget=InterfaceTemplate.TEAM_ATTENTION.target(teamLiteral)
  listOf(InterfaceTemplate.TEAM_COORDINATOR,InterfaceTemplate.TEAM_ATTENTION).forEach{
   assertEquals("Team name must bypass authored vocabulary syntax",it.target(teamLiteral),it.scaffold(teamLiteral).target)
  }
  val roomForms=listOf(
   InterfaceTemplate.TEAM_CAPTION_TEAM to emptyArray<Any>(),
   InterfaceTemplate.TEAM_CAPTION_TASK to emptyArray<Any>(),
   InterfaceTemplate.TEAM_CAPTION_TO to emptyArray<Any>(),
   InterfaceTemplate.TEAM_CAPTION_AGENT to emptyArray<Any>(),
   InterfaceTemplate.TEAM_EVENT_MESSAGE to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_EVENT_ATTENTION to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_EVENT_APPROVAL to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_CHOICE_TEAM to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_CHOICE_TASK to arrayOf<Any>(literal),
   InterfaceTemplate.TEAM_CHOICE_TO to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_CHOICE_AGENT to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_CHOICE_COORDINATOR to emptyArray<Any>(),
   InterfaceTemplate.TEAM_REPLY_CLEAR to arrayOf<Any>(731),
   InterfaceTemplate.TEAM_MESSAGE_RECIPIENT to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_ROOM_TASK to arrayOf<Any>(teamLiteral),
   InterfaceTemplate.TEAM_DIRECT_TASK to arrayOf<Any>(teamLiteral,literal),
   InterfaceTemplate.TEAM_ROOM_LOADING to emptyArray<Any>(),
   InterfaceTemplate.TEAM_DIRECT_LOADING to arrayOf<Any>(teamLiteral))
  val roomExpected=roomForms.flatMap{(template,args)->
   val scaffold=template.scaffold(*args)
   assertEquals("Literal team identity altered by scaffold syntax",template.target(*args),scaffold.target)
   listOf(immersionGrammarSupport(scaffold),template.target(*args))
  }
  var taps=0
  val seen=mutableSetOf<String>()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{activity->
    // The owned emulator is paired only to its loopback synthetic fixture.
    check(Pocket.base=="http://127.0.0.1:18880"){"Template fixture profile was not initialized"}
    PocketImmersion.setEnabled(true)
    activity.setContent{
     androidx.compose.runtime.CompositionLocalProvider(LocalImmersionMotionState provides true,LocalImmersionTransitionAnimated provides false){
      MaterialTheme{androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()){
       androidx.compose.material3.TextButton(onClick={taps++}){InterfaceTemplateText(InterfaceTemplate.GENERATED_ARTIFACT,literal)}
       InterfaceTemplateText(InterfaceTemplate.COMMAND_RESULTS,7,5)
       InterfaceTemplateText(InterfaceTemplate.CREDIT_OBSERVATION,"62.57",creditPeriod)
       InterfaceTemplateText(InterfaceTemplate.TEAM_COORDINATOR,teamLiteral)
       InterfaceTemplateText(InterfaceTemplate.TEAM_ATTENTION,teamLiteral)
       roomForms.forEach{(template,args)->InterfaceTemplateText(template,*args)}
      }}
     }
    }
   }
   try{compose.waitUntil(35000){
    (listOf(original,target,countsOriginal,countsTarget,creditsOriginal,creditsTarget,teamOriginal,teamTarget,attentionOriginal,attentionTarget)+roomExpected).forEach{form->
     if(compose.onAllNodesWithText(form).fetchSemanticsNodes().isNotEmpty())seen+=form
    }
    seen.containsAll((listOf(original,target,countsOriginal,countsTarget,creditsOriginal,creditsTarget,teamOriginal,teamTarget,attentionOriginal,attentionTarget)+roomExpected))
   }}catch(error:Throwable){
    android.util.Log.i("InterfaceTemplateQA","seen="+seen+" enabled="+PocketImmersion.enabled+" motion="+PocketImmersion.motionEnabled+" tree="+compose.onRoot().printToString())
    throw AssertionError("seen="+seen+" enabled="+PocketImmersion.enabled+" motion="+PocketImmersion.motionEnabled+" tree="+compose.onRoot().printToString(),error)
   }
   val visible=if(compose.onAllNodesWithText(target).fetchSemanticsNodes().isNotEmpty())target else original
   compose.onNodeWithText(visible).performClick()
   compose.runOnIdle{assertEquals(1,taps)}
  }
 }
 @Test fun longPhraseStartsOnTheSameLineAsItsVisiblePrefix(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(phrase:String)=buildAnnotatedString{append("Please ");val start=length;append(phrase);addStringAnnotation("immersion-reserve","long-phrase",start,length);append(", then inspect two new sessions.")}
  val from=form("review the latest coordinator conversation history")
  val to=form("rivedi la cronologia più recente del coordinatore")
  var layout:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{SharedInlineImmersionText(from,to,{0f},Modifier.width(360.dp),onTextLayout={layout=it})}}}
   compose.waitUntil(5000){layout!=null}
   val actual=requireNotNull(layout)
   assertEquals("Long replacement isolated the preceding word",actual.getLineForOffset(0),actual.getLineForOffset(7))
  }
 }
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
  val clickedForms=mutableListOf<String>()
  fun form(phrase:String)=buildAnnotatedString{
   append("Please ");val at=length;append(phrase)
   addStringAnnotation("immersion-reserve","7:13",at,length)
   addLink(LinkAnnotation.Clickable("reveal",linkInteractionListener={clicks++;clickedForms+=phrase}),at,length)
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
     androidx.test.uiautomator.By.pkg(c.packageName).text("Please $word existing sessions.")),5000)!!
    var box:androidx.compose.ui.geometry.Rect?=null
    i.runOnMainSync{box=parentLayout!!.placeholderRects.single()}
    val bounds=node.visibleBounds
    device.click(bounds.left+box!!.center.x.toInt(),bounds.top+box!!.center.y.toInt())
    compose.waitForIdle()
   }
   tapVisibleLink("search")
   i.runOnMainSync{assertEquals(1,clicks)}
   // A language handoff during a short native press must retain its down action.
   val device=UiDevice.getInstance(i)
   val node=device.wait(androidx.test.uiautomator.Until.findObject(
    androidx.test.uiautomator.By.pkg(c.packageName).text("Please search existing sessions.")),5000)!!
   val box=parentLayout!!.placeholderRects.single()!!
   val x=node.visibleBounds.left+box.center.x
   val y=node.visibleBounds.top+box.center.y
   val down=android.os.SystemClock.uptimeMillis()
   fun inject(action:Int){
    val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
    try{assertTrue(i.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
   }
   inject(android.view.MotionEvent.ACTION_DOWN)
   i.runOnMainSync{progress.floatValue=1f}
   compose.waitForIdle()
   inject(android.view.MotionEvent.ACTION_UP)
   compose.waitForIdle()
   i.runOnMainSync{assertEquals(2,clicks);assertEquals("search",clickedForms.last())}
   tapVisibleLink("cerca")
   i.runOnMainSync{assertEquals(3,clicks);assertEquals("cerca",clickedForms.last())}
   val actions=compose.onNodeWithText("Please cerca existing sessions.",useUnmergedTree=true)
    .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
   assertEquals("cerca",actions.single().label)
   i.runOnMainSync{assertTrue(actions.single().action());assertEquals(4,clicks)}
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
    device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(c.packageName).textContains(word)),5000)
    device.dumpWindowHierarchy(java.io.File(c.cacheDir,"shared-accessibility.xml"))
    val texts=device.findObjects(androidx.test.uiautomator.By.pkg(c.packageName).textContains(word)).mapNotNull{it.text}
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
    androidx.test.uiautomator.By.pkg(c.packageName).text("Please search existing sessions.")),5000)!!
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
     androidx.test.uiautomator.By.pkg(c.packageName).text("Please cerca fra tutti i risultati existing sessions.")),5000))
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

 @Test fun productionReservedTextRetainsVisibilityAndFreshInlineCallbacks(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  val source="Please search existing sessions."
  val target="Please cerca fra tutti i risultati existing sessions."
  val span=ImmersionSpan(7,13,"search","cerca fra tutti i risultati")
  val plan=ImmersionPresentation(target,source,true,listOf(span))
  val progress=mutableFloatStateOf(0f)
  val handoff=androidx.compose.runtime.mutableStateOf(false)
  var originalClicks=0;var freshClicks=0
  var visibleIds:Set<String> = emptySet();var parent:TextLayoutResult?=null
  fun content(translated:Boolean)=buildAnnotatedString{
   append(if(translated)target else source)
   val end=7+(if(translated)span.target.length else span.source.length)
   addStringAnnotation("immersion-reserve","7:13",7,end)
   addLink(LinkAnnotation.Clickable("reveal",linkInteractionListener={
    if(translated)freshClicks++ else originalClicks++
   }),7,end)
  }
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    androidx.compose.foundation.layout.Box(Modifier.padding(top=64.dp).width(240.dp)){
     ReservedImmersionText(content(handoff.value),plan,{1f},17.sp,26.sp,Paper,null,
      Int.MAX_VALUE,androidx.compose.ui.text.style.TextOverflow.Clip,
      ImmersionCycleRender(plan,incomingPlan=plan,cueActive=true,cueId=1,
       cueProgress={progress.floatValue},reportVisible={_,ids->visibleIds=ids}),
      onTextLayout={parent=it})
    }
   }}}
   compose.waitUntil(5000){visibleIds.contains("7:13")}
   compose.waitForIdle()
   val original=parent!!.getBoundingBox(parent!!.layoutInput.text.text.indexOf("existing"))
   i.runOnMainSync{progress.floatValue=1f;handoff.value=true}
   compose.waitForIdle()
   val node=device.wait(androidx.test.uiautomator.Until.findObject(
    androidx.test.uiautomator.By.pkg(c.packageName).text(target)),5000)!!
   val translated=parent!!.getBoundingBox(parent!!.layoutInput.text.text.indexOf("existing"))
   assertTrue(kotlin.math.abs(original.left-translated.left)<=2f&&kotlin.math.abs(original.top-translated.top)<=2f)
   assertTrue(visibleIds.contains("7:13"))
   val box=parent!!.placeholderRects.first()!!
   device.click(node.visibleBounds.left+box.center.x.toInt(),node.visibleBounds.top+box.center.y.toInt())
   compose.waitForIdle()
   i.runOnMainSync{assertEquals(0,originalClicks);assertEquals(1,freshClicks)}
  }
 }

 @Test fun singleLineTruncationRetainsVisibleTextAndExcludesHiddenCue(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(word:String)=buildAnnotatedString{
   var at=length;append(word)
   addStringAnnotation("immersion-reserve","first",at,length)
   append(" with extra context and ");at=length;append("open")
   addStringAnnotation("immersion-reserve","hidden",at,length)
   append(" another conversation.")
  }
  val from=form("supercalifragilisticexpialidocious")
  val to=form("pneumonoultramicroscopicsilicovolcanoconiosis")
  var parent:TextLayoutResult?=null;var ids:Set<String> = emptySet()
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(from,to,{0f},Modifier.padding(top=64.dp).width(240.dp),
     maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
     onTextLayout={parent=it},onVisibleRanges={visible,_->ids=visible})
   }}}
   compose.waitForIdle()
   val deadline=System.currentTimeMillis()+5000
   while(parent==null&&System.currentTimeMillis()<deadline){compose.waitForIdle();Thread.sleep(25)}
   assertEquals(1,parent!!.lineCount)
   assertTrue("Single line must retain actual phrase ink",parent!!.placeholderRects.first()!=null)
   assertTrue("Visible phrase must remain available to cycling",ids.contains("first"))
   assertFalse("Clipped phrase must not acquire a visible cue",ids.contains("hidden"))
   assertTrue("Single-line height must stay bounded",parent!!.size.height<=kotlin.math.ceil(with(parent!!.layoutInput.density){26.sp.toPx()})+1)
  }
 }

 @Test fun visibleOutgoingWrappedWordStillRespondsDuringWipe(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  val word="supercalifragilisticexpialidocious"
  var clicks=0;var outgoingClicks=0
  fun form(value:String)=buildAnnotatedString{
   append("Please ");val at=length;append(value)
   addStringAnnotation("immersion-reserve","7:40",at,length)
   addLink(LinkAnnotation.Clickable("reveal",linkInteractionListener={clicks++;if(value==word)outgoingClicks++}),at,length)
   append(" existing sessions.")
  }
  val from=form(word);val to=form("cerca")
  val progress=mutableFloatStateOf(0f)
  var parent:TextLayoutResult?=null;var sourceLayout:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    SharedInlineImmersionText(from,to,{progress.floatValue},Modifier.padding(top=64.dp).width(120.dp),
     onTextLayout={parent=it},onFragmentLayout={if(it.layoutInput.text.text==word)sourceLayout=it})
   }}}
   compose.waitForIdle()
   device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.pkg(c.packageName).text(from.text)),5000)
   val glyph=sourceLayout!!.getBoundingBox(word.length-1)
   assertTrue("Fixture requires an outgoing word on its second line",glyph.top>0f)
   val slot=parent!!.placeholderRects.first()!!
   i.runOnMainSync{progress.floatValue=.4f}
   compose.waitForIdle()
   val node=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.pkg(c.packageName).text(to.text)),5000)!!
   device.click(node.visibleBounds.left+(slot.left+glyph.center.x).toInt(),
    node.visibleBounds.top+(slot.top+glyph.center.y).toInt())
   compose.waitForIdle()
   i.runOnMainSync{assertEquals("Still-visible outgoing glyph must remain actionable",1,clicks);assertEquals("Stable help tag must use current context",0,outgoingClicks)}
  }
 }

 @Test fun reservedBlankSpaceDoesNotCaptureAnInlineAction(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  var phraseClicks=0;var surroundingClicks=0;var parent:TextLayoutResult?=null
  fun form(word:String)=buildAnnotatedString{
   append("Please ");val at=length;append(word)
   addStringAnnotation("immersion-reserve","phrase",at,length)
   addLink(LinkAnnotation.Clickable("reveal",linkInteractionListener={phraseClicks++}),at,length)
   append(" existing sessions.")
  }
  val from=form("cerca");val to=form("cerca fra tutti i risultati")
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    androidx.compose.foundation.layout.Box(
     Modifier.padding(top=64.dp).width(240.dp).clickable{surroundingClicks++}){
     SharedInlineImmersionText(from,to,{0f},onTextLayout={parent=it})
    }
   }}}
   compose.waitForIdle()
   val node=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.pkg(c.packageName).text(from.text)),5000)!!
   val slot=parent!!.placeholderRects.first()!!
   device.click(node.visibleBounds.left+slot.right.toInt()-8,node.visibleBounds.top+slot.center.y.toInt())
   compose.waitForIdle()
   i.runOnMainSync{assertEquals(0,phraseClicks);assertEquals(1,surroundingClicks)}
  }
 }

 @Test fun selectionCopiesOneVisibleParagraphWithoutDuplicatedPhrase()=
  checkSelectionCopy("search","cerca",1f,true,"Please cerca existing sessions.")
 @Test fun selectionCopiesTheTouchedWord()=
  checkSelectionCopy("search","cerca",1f,false,"cerca")
 @Test fun originalSelectionDoesNotCopyReservedSlotMarkers()=
  checkSelectionCopy("search","cerca fra tutti i risultati in tutte le conversazioni disponibili",0f,true,"Please search existing sessions.")
 @Test fun originalZeroWidthCharacterSurvivesProjectionCleanup(){
  checkSelectionCopy("search\u200b","cerca fra tutti i risultati in tutte le conversazioni disponibili",0f,true,"Please search\u200b existing sessions.")
 }
 private fun checkSelectionCopy(sourceWord:String,targetWord:String,phase:Float,whole:Boolean,expected:String){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext;val device=UiDevice.getInstance(i)
  check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(word:String)=buildAnnotatedString{
   append("Please ");val at=length;append(word)
   addStringAnnotation("immersion-reserve","phrase",at,length)
   append(" existing sessions.")
  }
  val from=form(sourceWord);val to=form(targetWord)
  val activeWord=if(phase==0f)sourceWord else targetWord
  var parent:TextLayoutResult?=null;var glyphLayout:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    ImmersionSelection(from.text){
     SharedInlineImmersionText(from,to,{phase},Modifier.padding(top=64.dp).width(240.dp),onTextLayout={parent=it},
      onFragmentLayout={if(it.layoutInput.text.text==activeWord)glyphLayout=it})
    }
   }}}
   compose.waitForIdle()
   val node=device.wait(androidx.test.uiautomator.Until.findObject(
    androidx.test.uiautomator.By.pkg(c.packageName).textContains("Please "+activeWord.take(4))),5000)!!
   val slot=parent!!.placeholderRects.first()!!
   val glyph=glyphLayout!!.getBoundingBox(activeWord.length/2)
   val x=node.visibleBounds.left+(slot.left+glyph.center.x).toInt()
   val y=node.visibleBounds.top+(slot.top+glyph.center.y).toInt()
   val downAt=android.os.SystemClock.uptimeMillis()
   val down=android.view.MotionEvent.obtain(downAt,downAt,android.view.MotionEvent.ACTION_DOWN,x.toFloat(),y.toFloat(),0)
   down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
   assertTrue(i.uiAutomation.injectInputEvent(down,true));down.recycle()
   compose.mainClock.advanceTimeBy(1000);compose.waitForIdle()
   val up=android.view.MotionEvent.obtain(downAt,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x.toFloat(),y.toFloat(),0)
   up.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
   assertTrue(i.uiAutomation.injectInputEvent(up,true));up.recycle()
   compose.waitForIdle()
   device.dumpWindowHierarchy(java.io.File(c.cacheDir,"selection-toolbar.xml"))
   if(whole){
    val select=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Select all")),5000)
    assertNotNull("Native long press must offer Select all",select)
    select!!.click()
   }
   val copy=device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text("Copy")),5000)
   assertNotNull("Native selection must offer Copy",copy)
   copy!!.click();compose.waitForIdle()
   var actual:String?=null
   i.runOnMainSync{
    val clipboard=c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    actual=clipboard.primaryClip?.getItemAt(0)?.text?.toString()
   }
   assertEquals("Copy must contain one ordered visible flow",expected,actual)
  }
 }


 @Test fun unboundedControlKeepsItsWholeReservedWordWithoutEllipsisChunks() {
  val i=InstrumentationRegistry.getInstrumentation()
  i.targetContext.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  fun form(word:String)=buildAnnotatedString{
   append(word);addStringAnnotation("immersion-reserve","recent-control",0,length)
  }
  var geometry:ImmersionSharedWipeGeometry?=null
  var parent:TextLayoutResult?=null
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   scenario.onActivity{it.setContent{MaterialTheme{
    androidx.compose.foundation.layout.Row(Modifier.horizontalScroll(rememberScrollState())){
     SharedInlineImmersionText(form("Recent"),form("Recenti"),{1f},
      fontSize=14.sp,lineHeight=18.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
      reserve=listOf(form("Recent"),form("Recenti")),
      onTextLayout={parent=it},onWipeGeometry={geometry=it})
    }
   }}}
   compose.waitForIdle()
   i.runOnMainSync{
    assertNotNull(geometry);assertNotNull(parent)
    assertEquals("Unbounded control invented wrapped/truncated chunks",1,geometry!!.regions.size)
    android.util.Log.i("ImmersionSearchGeometry","size=${parent!!.size} widthOverflow=${parent!!.didOverflowWidth} heightOverflow=${parent!!.didOverflowHeight} lines=${parent!!.lineCount} bottom=${parent!!.getLineBottom(0)} placeholders=${parent!!.placeholderRects}")
    assertFalse("Natural-width word was clipped",parent!!.hasVisualOverflow)
   }
  }
 }
}
