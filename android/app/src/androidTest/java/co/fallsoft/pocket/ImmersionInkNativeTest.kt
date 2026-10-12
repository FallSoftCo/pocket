package co.fallsoft.pocket
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
@RunWith(AndroidJUnit4::class)
class ImmersionInkNativeTest{
 @get:Rule val compose=createEmptyComposeRule()
 @Test fun unchangedInkDoesNotDimDuringComplementaryWipe(){
  val i=InstrumentationRegistry.getInstrumentation();val c=i.targetContext
  check(UiDevice.getInstance(i).executeShellCommand("getprop ro.boot.qemu.avd_name").trim()=="nextcomp_env_qa")
  c.getSharedPreferences("pocket",Context.MODE_PRIVATE).edit().clear().commit()
  val from=AnnotatedString("Read the file.");val to=AnnotatedString("Read the code.")
  val progress=mutableFloatStateOf(0f)
  ActivityScenario.launch(MainActivity::class.java).use{scenario->
   for((ink,background) in listOf(Color.White to Color.Black,Color(.8f,.6f,.4f) to Color(.2f,.2f,.2f))){
   i.runOnMainSync{progress.floatValue=0f}
   scenario.onActivity{it.setContent{MaterialTheme{
    BlendImmersionText(from,to,{progress.floatValue},to,color=ink,
     modifier=Modifier.width(240.dp).background(background).testTag("ink"),fillWidth=true)
   }}}
   compose.waitForIdle();val baseline=compose.onNodeWithTag("ink").captureToImage().toPixelMap()
   // Locate a full-opacity glyph in the unchanged prefix, which the moving
   // frontier crosses. The actual native mask has a feathered edge.
   val x=(0 until baseline.width/3).firstOrNull{col->(0 until baseline.height).any{y->baseline[col,y].red>ink.red-.035f}}
   assertNotNull("Native baseline contains no common glyph",x)
   val glyphX=x!!;val glyphY=(0 until baseline.height).first{baseline[glyphX,it].red>ink.red-.035f}
   val phase=(glyphX+48f)/(baseline.width+96f)
   i.runOnMainSync{progress.floatValue=phase};compose.waitForIdle()
   val mid=compose.onNodeWithTag("ink").captureToImage().toPixelMap()
   val before=baseline[glyphX,glyphY].red;val during=mid[glyphX,glyphY].red
   assertTrue("Unchanged ink dimmed: before=$before during=$during phase=$phase",abs(before-during)<.035f)
   for(channel in listOf("green","blue")){
    val a=baseline[glyphX,glyphY];val b=mid[glyphX,glyphY]
    assertTrue("Common ink tint changed: $channel",abs(if(channel=="green")a.green-b.green else a.blue-b.blue)<.035f)
   }
   }
  }
 }
}
