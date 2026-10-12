package co.fallsoft.pocket

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ImmersionSharedWipeTest {
 private val geometry=ImmersionSharedWipeGeometry(
  mapOf("left" to Rect(0f,0f,80f,26f),"right" to Rect(150f,0f,220f,26f),
   "later" to Rect(0f,52f,100f,78f)),240f,26f)
 @Test fun sameLineFragmentsShareOneGlobalFrontier(){
  val left=geometry.mask("left",0f,.25f,false)!!
  val right=geometry.mask("right",0f,.25f,false)!!
  assertEquals(left.center,right.center+150f,.001f)
  assertEquals(left.feather,right.feather,.001f)
 }
 @Test fun lowerLineWaitsForEarlierReadingBand(){
  val first=geometry.mask("left",0f,.3f,false)!!
  val later=geometry.mask("later",0f,.3f,false)!!
  assertTrue(first.center>0f)
  assertTrue(later.center<0f)
 }
 @Test fun rightToLeftMirrorsTheParagraphNotEachIndividualSlot(){
  val ltr=geometry.mask("right",0f,.2f,false)!!
  val rtl=geometry.mask("right",0f,.2f,true)!!
  assertEquals(240f,ltr.center+rtl.center+300f,.001f)
 }
 @Test fun endpointsCoverEveryFragmentAndWrappedLine(){
  for((key,rect) in geometry.regions){
   assertTrue(geometry.mask(key,0f,0f,false)!!.center<0f)
   assertTrue(geometry.mask(key,0f,1f,false)!!.center>rect.width)
  }
  assertNull(geometry.mask("unknown",0f,.5f,false))
 }
}
