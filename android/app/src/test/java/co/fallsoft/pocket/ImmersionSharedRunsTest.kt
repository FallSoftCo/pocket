package co.fallsoft.pocket

import androidx.compose.ui.text.*
import org.junit.Assert.*
import org.junit.Test

class ImmersionSharedRunsTest {
 @Test fun flipFlopReplacesEveryAlignedWordIncludingFunctionWords(){
  fun sentence(words:List<String>)=buildAnnotatedString{
   words.forEachIndexed{index,word->
    if(index>0)append(' ')
    val start=length;append(word)
    addStringAnnotation("immersion-reserve","lexical:"+index,start,length)
   }
  }
  val italian=listOf("Il","lavoro","continua")
  val english=listOf("The","work","continues")
  val forms=immersionFlipForms(sentence(italian),sentence(english))!!
  assertEquals(english.joinToString(" "),forms[0].text);assertEquals(italian.joinToString(" "),forms.last().text)
  val first=forms[0].text.split(' ');val second=forms.last().text.split(' ')
  italian.indices.forEach{index->
   assertEquals(setOf(italian[index],english[index]),setOf(first[index],second[index]))
  }
  assertEquals(3,forms[0].getStringAnnotations("immersion-reserve",0,forms[0].length).size)
 }
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

 @Test fun punctuationStaysBesideThePhraseWithoutDuplicatingContent(){
  fun input(word:String)=buildAnnotatedString{append("Please ");val at=length;append(word);addStringAnnotation("immersion-reserve","phrase",at,length);append(", next.")}
  val source=input("search");val target=input("cerca")
  val runs=requireNotNull(immersionSharedRuns(listOf(source,target)))
  assertEquals(listOf("search,","cerca,"),runs.single{it.id=="phrase"}.forms.map{it.text})
  assertEquals(listOf(" next."," next."),runs.last().forms.map{it.text})
  assertEquals(source.text,runs.joinToString(""){it.forms[0].text})
  assertEquals(target.text,runs.joinToString(""){it.forms[1].text})
 }

 @Test fun grammaticalPhraseAssignmentsStayWholeAndComplementary() {
  val pairs=listOf("Puoi aprire" to "Can-you open","l’ultimo messaggio" to "the latest message","in questa conversazione" to "in this conversation")
  fun form(italian:Boolean)=buildAnnotatedString {
   pairs.forEachIndexed { i,pair ->
    if(i>0)append(" ")
    val at=length;append(if(italian)pair.first else pair.second)
    addStringAnnotation("immersion-reserve","phrase:$i",at,length)
   }
   append("?")
  }
  val forms=requireNotNull(immersionFlipForms(form(true),form(false))).map{it.text}
  pairs.forEach{(it,en)->
   assertTrue(forms.any{form->form.contains(it)})
   assertTrue(forms.any{form->form.contains(en)})
  }
  assertTrue(forms.all{it.endsWith("?")})
 }

}
