package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class BilingualContentTest {
 @Test fun labelsRemainOfflineAndTechnicalLiteralsUntouched(){assertEquals("Invia",ImmersionLexicon.italian("Send"));assertNull(ImmersionLexicon.italian("git status"));assertNull(ImmersionLexicon.italian("/path/Send"))}

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

 private fun replacementPlan():ImmersionPresentation {
  val source="Check the sessions, then the sessions; ask a question with `notes.md` and gpt-6-luna."
  val first=source.indexOf("the sessions");val second=source.indexOf("the sessions",first+1);val third=source.indexOf("a question")
  val spans=listOf(ImmersionSpan(first,first+12,"the sessions","le sessioni"),ImmersionSpan(second,second+12,"the sessions","le sessioni"),ImmersionSpan(third,third+10,"a question","una domanda"))
  return ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
 }
 @Test fun replacementRescueUsesExactOffsetsDespiteRepeatedSourceAndTarget(){
  val plan=replacementPlan()
  assertEquals("Check le sessioni, then le sessioni; ask una domanda with `notes.md` and gpt-6-luna.",immersionReplacementText(plan))
  assertEquals("Check le sessioni, then the sessions; ask una domanda with `notes.md` and gpt-6-luna.",immersionReplacementText(plan,plan.spans[1]))
  assertEquals("Check le sessioni, then le sessioni; ask a question with `notes.md` and gpt-6-luna.",immersionReplacementText(plan,plan.spans[2]))
 }
 @Test fun replacementOriginalOverrideContainsOnlyOneOriginalFlow(){
  val plan=replacementPlan();assertEquals(plan.source,immersionReplacementText(plan,plan.spans[1],true))
  assertFalse(immersionReplacementText(plan,plan.spans[1]).contains("\n"))
  assertFalse(immersionReplacementText(plan,plan.spans[1]).contains("["))
  assertTrue(immersionReplacementText(plan,plan.spans[1]).endsWith("with `notes.md` and gpt-6-luna."))
 }
 @Test fun invalidOrStalePlanNeverSplicesTextIntoChangedSource(){
  val plan=replacementPlan()
  val changed=plan.copy(source=plan.source.replace("sessions","workers"))
  assertEquals(changed.source,immersionReplacementText(changed,plan.spans[0]))
  assertEquals(plan.source,immersionReplacementText(plan.copy(text=plan.text+" extra")))
  assertEquals(plan.source,immersionReplacementText(plan.copy(spans=plan.spans.reversed())))
  assertEquals(plan.text,immersionReplacementText(plan,plan.spans[0].copy(target="different")))
 }
 @Test fun replacementPreservesExistingNewlinesAndProtectedMarkupWithoutAddingAny(){
  val source="Ready?\n\n[Open](https://example.test/a) `notes.md`\n```sh\ngit status\n```"
  val span=ImmersionSpan(0,5,"Ready","Pronto");val plan=ImmersionPresentation(contextualHybridText(source,listOf(span))!!,source,true,listOf(span))
  val shown=immersionReplacementText(plan)
  assertEquals(source.count{it=='\n'},shown.count{it=='\n'})
  assertEquals(source.substring(5),shown.substring(6))
  assertEquals(source,immersionReplacementText(plan,span))
 }
}
