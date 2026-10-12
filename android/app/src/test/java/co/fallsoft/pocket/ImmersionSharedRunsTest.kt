package co.fallsoft.pocket

import androidx.compose.ui.text.*
import org.junit.Assert.*
import org.junit.Test

class ImmersionSharedRunsTest {
 private fun form(word:String)=buildAnnotatedString{
  append("Please ");val start=length;append(word)
  addStringAnnotation("immersion-reserve","7:13",start,length)
  append(" existing sessions.")
 }
 @Test fun unequalReplacementKeepsSurroundingRunsAndNativeOffsets(){
  val runs=immersionSharedRuns(listOf(form("search"),form("cerca fra tutti i risultati")))!!
  assertEquals(listOf(null,"7:13",null),runs.map{it.id})
  assertEquals(listOf(" existing sessions."," existing sessions."),runs.last().forms.map{it.text})
  assertEquals(listOf("search","cerca fra tutti i risultati"),runs[1].forms.map{it.text})
 }
 @Test fun unexpectedOutsideChangeRejectsFalseGeometryGuarantee(){
  assertNull(immersionSharedRuns(listOf(form("search"),AnnotatedString("Unrelated paragraph"))))
 }
 @Test fun repeatedPhraseIdentityCannotAliasTwoTapTargets(){
  val bad=buildAnnotatedString{append(form("search"));append(form("search"))}
  assertNull(immersionSharedRuns(listOf(bad,bad)))
 }
 @Test fun emptyFormsAndUnchangedParagraphRemainDefined(){
  assertEquals(emptyList<ImmersionSharedRun>(),immersionSharedRuns(emptyList()))
  assertEquals("Already equal",immersionSharedRuns(listOf(AnnotatedString("Already equal"),AnnotatedString("Already equal")))!!.single().forms.first().text)
 }
 @Test fun chunkingPreservesWholePhraseAndItsInlineAnnotations(){
  val form=buildAnnotatedString{
   append("one two three")
   addStringAnnotation("meaning","context",0,length)
   addLink(LinkAnnotation.Url("https://example.invalid/owned"),0,length)
  }
  val chunks=immersionAnnotatedChunks(form,7){it.length}
  assertEquals(form.text,chunks.joinToString(""){it.text})
  assertEquals(listOf("one ","two ","three"),chunks.map{it.text})
  assertTrue(chunks.all{it.getLinkAnnotations(0,it.length).size==1})
  assertTrue(chunks.all{it.getStringAnnotations("meaning",0,it.length).single().item=="context"})
 }
 @Test fun chunkingDoesNotSplitAnUnbreakableTokenOrInventWordAlignment(){
  val chunks=immersionAnnotatedChunks(AnnotatedString("longunbreakabletoken tail"),5){it.length}
  assertEquals(listOf("longunbreakabletoken ","tail"),chunks.map{it.text})
 }

 @Test fun firstChunkUsesRemainingLineWidthThenFullWidth(){
  val form=AnnotatedString("one two three")
  val chunks=immersionAnnotatedChunks(form,12,4){it.length}
  assertEquals(listOf("one ","two three"),chunks.map{it.text})
  assertEquals(form.text,chunks.joinToString(""){it.text})
 }

}
