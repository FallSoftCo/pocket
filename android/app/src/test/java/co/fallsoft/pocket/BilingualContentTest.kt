package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class BilingualContentTest {
 @Test fun paragraphsPairWithAnEnglishSupportLane(){val rows=bilingualUnits("Hello.\n\nReady?","Ciao.\n\nPronto?");assertEquals(2,rows.size);assertEquals("Hello.",rows[0].original);assertEquals("Pronto?",rows[1].target)}
 @Test fun fencedCodeAppearsOnlyOnce(){val original="Run this.\n\n```sh\ngit status\n```\n\nThen wait.";val target="Esegui questo.\n\n```sh\ngit status\n```\n\nPoi aspetta.";val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertNull(rows[1].original);assertTrue(rows[1].target.contains("git status"))}
 @Test fun unmatchedParagraphsUseWholeMessageSupportWithoutDuplicatingCode(){val rows=bilingualUnits("First.\n\n```sh\nls\n```\n\nSecond.","Primo. Secondo.\n\n```sh\nls\n```");assertEquals(1,rows.size);assertFalse(rows[0].original!!.contains("```"));assertTrue(rows[0].target.contains("```"))}
 @Test fun commonLabelsAvailableOfflineButPathsAndShellSyntaxUntouched(){assertEquals("Invia",ImmersionLexicon.italian("Send"));assertEquals("In coda",ImmersionLexicon.italian("Queue"));assertNull(ImmersionLexicon.italian("git status"));assertNull(ImmersionLexicon.italian("/path/Send"));assertEquals("Checks Git status",ImmersionCommands.meaning("git status --short")!!.second);assertNull(ImmersionCommands.meaning("git statusful"))}
 @Test fun tildeAndFourBacktickFencesKeepNestedMarkersInSingleCodeUnit(){
  for(fence in listOf("~~~","````")){
   val original="Before.\n\n${fence}sh\n```\necho ok\n```\n$fence\n\nAfter."
   val target="Prima.\n\n${fence}sh\n```\necho ok\n```\n$fence\n\nDopo."
   val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertNull(rows[1].original);assertTrue(rows[1].target.contains("echo ok"))
  }
 }
 @Test fun fenceWithTrailingWordsDoesNotCloseCode(){val original="Before.\n\n~~~~sh\necho ok\n~~~~ not a closer\necho done\n~~~~\n\nAfter.";val target=original.replace("Before.","Prima.").replace("After.","Dopo.");val rows=bilingualUnits(original,target);assertEquals(3,rows.size);assertTrue(rows[1].code);assertTrue(rows[1].target.contains("echo done"));assertNull(rows[1].original)}

 private fun phrase():ImmersionSpan {
  val target="una domanda importante"
  return ImmersionSpan(0,21,"an important question",target,targetSegments=listOf(
   ImmersionTargetSegment(0,3,"una","a","determiner",relations=listOf(ImmersionRelation("agreesWith",2))),
   ImmersionTargetSegment(3,4," ","","separator"),
   ImmersionTargetSegment(4,11,"domanda","question","noun"),
   ImmersionTargetSegment(11,12," ","","separator"),
   ImmersionTargetSegment(12,22,"importante","important","adjective",relations=listOf(ImmersionRelation("agreesWith",2)))
  ))
 }
 @Test fun exactTargetCoverageRejectsMissingSpellingAndOutOfRangeRelation(){
  val span=phrase();assertTrue(alignedTargetValid(span))
  assertFalse(alignedTargetValid(span.copy(targetSegments=span.targetSegments.drop(1))))
  assertFalse(alignedTargetValid(span.copy(targetSegments=span.targetSegments.mapIndexed{i,s->if(i==2)s.copy(target="domande")else s})))
  assertFalse(alignedTargetValid(span.copy(targetSegments=span.targetSegments.mapIndexed{i,s->if(i==0)s.copy(relations=listOf(ImmersionRelation("agreesWith",99)))else s})))
  assertFalse(alignedTargetValid(span.copy(targetSegments=span.targetSegments.mapIndexed{i,s->if(i==1)s.copy(meaning="invented grammar")else s})))
 }
 @Test fun alignedReadingFlowKeepsItalianOrderSurroundingTextAndPunctuation(){
  val span=phrase();val content="Ask una domanda importante, then continue."
  val tokens=immersionReadingTokens(content,span)!!
  assertEquals(content,tokens.joinToString(""){content.substring(it.start,it.end)})
  assertEquals(listOf("a","question","important"),tokens.map{it.meaning}.filter{it.isNotBlank()})
  assertEquals(listOf("una ","domanda ","importante, "),tokens.filter{it.segment!=null}.map{content.substring(it.start,it.end)})
  assertEquals("Ask ",content.substring(tokens.first().start,tokens.first().end))
  assertEquals("an important question",span.source)
 }
 @Test fun agreementHighlightsBothEndsWithoutInventingVerbAgreement(){
  assertEquals(setOf(0,2,4),agreementSegments(phrase()))
  val verb=ImmersionTargetSegment(0,4,"sono","are","verb",features=listOf(ImmersionFeature("number","plural")))
  assertTrue(agreementSegments(ImmersionSpan(0,3,"are","sono",targetSegments=listOf(verb))).isEmpty())
 }
 @Test fun contextualCueUsesTeacherFeaturesAndNegationPrecedence(){
  val v=ImmersionTargetSegment(0,4,"sono","are","verb")
  assertEquals("modality",grammarCue(v.copy(features=listOf(ImmersionFeature("tense","conditional")))))
  assertEquals("aspect",grammarCue(v.copy(features=listOf(ImmersionFeature("aspect","progressive")))))
  assertEquals("past",grammarCue(v.copy(features=listOf(ImmersionFeature("tense","past")))))
  assertEquals("negation",grammarCue(v.copy(features=listOf(ImmersionFeature("aspect","perfect")),relations=listOf(ImmersionRelation("negates",0)))))
  assertEquals("verb",grammarCue(v))
 }
 @Test fun repeatedPhraseHasNoGuessedReadingAlignment(){
  assertNull(immersionReadingTokens("una domanda importante, una domanda importante",phrase()))
  assertNull(immersionReadingTokens("missing",phrase()))
 }
 @Test fun inseparableItalianContractionIsNeverSplicedIntoEnglish(){
  val segment=ImmersionTargetSegment(0,4,"alla","to the","preposition")
  val span=ImmersionSpan(0,6,"to the","alla",targetSegments=listOf(segment))
  val tokens=immersionReadingTokens("Torna alla sessione.",span)!!
  assertEquals(1,tokens.count{it.segment!=null})
  assertEquals("alla ",tokens.single{it.segment!=null}.let{"Torna alla sessione.".substring(it.start,it.end)})
  assertEquals("to the",tokens.single{it.segment!=null}.meaning)
 }
 @Test fun separateAgreementHeadsReceiveStableGroupsAndOtherRelationsDoNotJoin(){
  val target="le nuove sessioni e il modello"
  val segments=listOf(
   ImmersionTargetSegment(0,2,"le","the","determiner",relations=listOf(ImmersionRelation("agreesWith",4))),
   ImmersionTargetSegment(2,3," ","","separator"),
   ImmersionTargetSegment(3,8,"nuove","new","adjective",relations=listOf(ImmersionRelation("agreesWith",4))),
   ImmersionTargetSegment(8,9," ","","separator"),
   ImmersionTargetSegment(9,17,"sessioni","sessions","noun"),
   ImmersionTargetSegment(17,18," ","","separator"),
   ImmersionTargetSegment(18,19,"e","and","conjunction",relations=listOf(ImmersionRelation("head",10),ImmersionRelation("auxiliaryOf",4),ImmersionRelation("negates",10))),
   ImmersionTargetSegment(19,20," ","","separator"),
   ImmersionTargetSegment(20,22,"il","the","determiner",relations=listOf(ImmersionRelation("agreesWith",10))),
   ImmersionTargetSegment(22,23," ","","separator"),
   ImmersionTargetSegment(23,30,"modello","model","noun")
  )
  val span=ImmersionSpan(0,1,"x",target,targetSegments=segments)
  assertEquals(mapOf(0 to 0,2 to 0,4 to 0,8 to 1,10 to 1),agreementGroups(span))
  assertFalse(agreementGroups(span).containsKey(6))
  assertEquals(agreementGroups(span),agreementGroups(span.copy(targetSegments=segments.map{s->s.copy(relations=s.relations.reversed())})))
  assertTrue(agreementGroups(span.copy(targetSegments=segments.mapIndexed{i,s->if(i==0)s.copy(relations=listOf(ImmersionRelation("agreesWith",99)))else s})).isEmpty())
 }
 @Test fun compatibleFeaturesAloneNeverCreateAnAgreementGroup(){
  val s=ImmersionTargetSegment(0,4,"sono","are","verb",features=listOf(ImmersionFeature("number","plural")))
  assertTrue(agreementGroups(ImmersionSpan(0,3,"are","sono",targetSegments=listOf(s))).isEmpty())
 }

}
