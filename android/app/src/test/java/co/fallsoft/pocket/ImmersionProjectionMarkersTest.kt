package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class ImmersionProjectionMarkersTest{
 @Test fun avoidsOriginalAndAlternateContentCollisions(){
  val original="\u2063nextcomp-slot:fixed:0\u2063"
  val form="\u2063nextcomp-slot:fixed:1\u2063"
  val registry=ImmersionProjectionMarkers(original){"fixed"}
  assertEquals("\u2063nextcomp-slot:fixed:2\u2063",registry.create(listOf(form)))
 }
 @Test fun preservesOriginalZeroWidthAndUnownedTokens(){
  val registry=ImmersionProjectionMarkers("user\u200btext"){"owned"}
  val marker=registry.create(emptyList())
  val unknown="\u2063nextcomp-slot:unknown:0\u2063"
  val text="user\u200btext"+marker+unknown
  val clean=StringBuilder(text)
  immersionOwnedProjectionRanges(text,registry.snapshot()).forEach{clean.delete(it.first,it.last+1)}
  assertEquals("user\u200btext"+unknown,clean.toString())
 }
 @Test fun removesRepeatedOwnedTokensWithoutRemovingAdjacentText(){
  val registry=ImmersionProjectionMarkers(""){"owned"}
  val marker=registry.create(emptyList())
  val text="A"+marker+"B"+marker+"C"+marker+"D"
  val clean=StringBuilder(text)
  immersionOwnedProjectionRanges(text,registry.snapshot()).forEach{clean.delete(it.first,it.last+1)}
  assertEquals("ABCD",clean.toString())
 }
}
