package co.fallsoft.pocket

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import kotlinx.coroutines.test.StandardTestDispatcher
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Real text layout, both language forms, constrained catalog viewport and adjacent action. */
@RunWith(AndroidJUnit4::class)
class SessionPreviewBoundsNativeTest {
 @get:Rule val compose=createEmptyComposeRule(effectContext=StandardTestDispatcher())
 @Test(timeout=90000) fun longDiagnosticsRespectPreviewBoundsInBothLanguagesAndLargeText(){
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  requireOwnedNativeTarget(UiDevice.getInstance(instrumentation))
  val scale=mutableFloatStateOf(1f);val phase=mutableIntStateOf(1)
  val layout=AtomicReference<TextLayoutResult>();val height=mutableIntStateOf(0)
  var taps=0
  fun marked(text:String)=buildAnnotatedString{append(text);addStringAnnotation("immersion-reserve","diagnostic",0,length)}
  val forms=listOf(marked("Request failed\n"+"upstream_error_".repeat(100)),marked("Richiesta non riuscita\n"+"il servizio non risponde ".repeat(100)))
  ActivityScenario.launch(NativeComponentFixtureActivity::class.java).use{scenario->
   scenario.onActivity{activity->activity.setContent{
    val density=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density,scale.floatValue)){
     val previewHeight=with(LocalDensity.current){40.sp.toDp()}.coerceAtLeast(48.dp)
     SideEffect{height.intValue=with(density){previewHeight.roundToPx()}}
     MaterialTheme{Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)){
      Box(Modifier.width(240.dp).height(previewHeight).background(Color.DarkGray)){
       StableImmersionPhrases(forms,phase.intValue,modifier=Modifier.fillMaxSize(),fontSize=14.sp,lineHeight=20.sp,
        color=Color.Red,fontWeight=null,maxLines=2,overflow=TextOverflow.Ellipsis,phraseTaps=false,
        onTextLayout={layout.set(it)})
      }
      Button(onClick={taps++}){Text("Open adjacent session")}
     }}
    }
   }}
   for(fontScale in listOf(1f,2f))for(language in 0..1){
    compose.runOnIdle{layout.set(null);scale.floatValue=fontScale;phase.intValue=language}
    compose.waitUntil(5000){layout.get()!=null}
    val actual=layout.get()
    assertTrue("Preview exceeded its height at $fontScale/$language",actual.size.height<=height.intValue)
    assertTrue("Diagnostic placeholder bypassed the preview line limit",actual.placeholderRects.filterNotNull().all{it.height<=height.intValue+1})
    compose.onNodeWithText("Open adjacent session").performClick()
   }
   assertEquals("Overflow must not consume the neighboring session's interaction",4,taps)
   val device=UiDevice.getInstance(instrumentation)
   assertTrue(device.takeScreenshot(java.io.File(instrumentation.targetContext.cacheDir,"session-preview-bounds.png")))
  }
 }
}
