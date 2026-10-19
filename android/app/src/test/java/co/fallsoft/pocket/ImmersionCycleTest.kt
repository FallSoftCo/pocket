package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionCycleTest {
    @Test fun authoredUiCatalogKeepsCleanPairedUnitsAndCanonicalSources() {
        InterfaceGrammarScaffolds.translations.forEach { (source,target) ->
            val span=requireNotNull(InterfaceGrammarScaffolds.find(source,target)) { source }
            assertTrue("Invalid authored alignment: "+source,alignedTargetValid(span))
            assertEquals(source,span.source)
        val linkedSource="NextComp server address"
        val linked=requireNotNull(InterfaceGrammarScaffolds.find(linkedSource,requireNotNull(InterfaceGrammarScaffolds.italian(linkedSource))))
        assertTrue(immersionGrammarSupport(linked).contains("of-the"))
            assertEquals(target,span.targetSegments.joinToString(""){it.target})
            assertFalse("Leaked phrase delimiter: "+source,target.contains('~'))
            val support=requireNotNull(immersionGrammarForm(span,true,"catalog")) { source }
            assertTrue("Support vocabulary missing: "+source,support.text.isNotBlank())
            assertFalse("Leaked support delimiter: "+source,support.text.contains('~'))
        }
        val errorSource="Background refresh: connection lost (E123)"
        val errorTarget=requireNotNull(InterfaceGrammarScaffolds.italian(errorSource))
        assertEquals("Aggiornamento in background: connection lost (E123)",errorTarget)
        val errorSpan=requireNotNull(InterfaceGrammarScaffolds.find(errorSource,errorTarget))
        assertEquals("connection lost (E123)",errorSpan.targetSegments.last().target)
        assertEquals(errorSpan.targetSegments.last().target,errorSpan.targetSegments.last().meaning)
        val source="Speech uses media volume; quiet mode, notification mute and Do Not Disturb still silence it."
        val target=requireNotNull(InterfaceGrammarScaffolds.italian(source))
        val span=requireNotNull(InterfaceGrammarScaffolds.find(source,target))
        val unit=span.targetSegments.first{it.target=="Non disturbare"}
        assertEquals("Do Not Disturb",unit.meaning)
        assertEquals("Non disturbare",target.substring(unit.start,unit.end))
        assertEquals(source,span.source)
    }

    private fun aligned(start:Int,source:String,units:String):ImmersionSpan {
        val pairs=units.split(" ").flatMapIndexed{index,value->
            val split=value.split("|",limit=2)
            val pair=split[0] to split.getOrElse(1){split[0]}
            if(index==0)listOf(pair)else listOf(" " to " ",pair)
        }
        return authoredImmersionScaffold(source,pairs).copy(start=start,end=start+source.length)
    }
    @Test fun punctuationStaysAttachedWithoutConsumingTheFollowingWord(){
        assertEquals(7,immersionPhraseEnd("phrase, next",6))
        assertEquals(9,immersionPhraseEnd("phrase…). next",6))
        assertEquals(6,immersionPhraseEnd("phrase next",6))
        assertEquals(6,immersionPhraseEnd("phrase's next",6))
    }
    @Test fun longerTargetDwellAndStableIndependentStaggers(){
        val a=immersionCycleTiming("message:10","new sessions","nuove sessioni")
        assertTrue(a.targetMs>a.sourceMs)
        assertEquals(a,immersionCycleTiming("message:10","new sessions","nuove sessioni"))
        val phases=(10..20).map{immersionCycleTiming("message:$it","new sessions","nuove sessioni").staggerMs}
        assertTrue(phases.distinct().size>=9)
        assertTrue(phases.maxOrNull()!!-phases.minOrNull()!!>2000)
    }
    @Test fun longProseReceivesUncappedReadingHoldsAndTargetStaysLonger(){
        val fiveHundred=List(500){"session"}.joinToString(" ")
        val a=immersionCycleTiming("long",fiveHundred,fiveHundred)
        assertEquals(150000L,a.sourceMs)
        assertEquals(229000L,a.targetMs)
        val thousand=List(1000){"sessione"}.joinToString(" ")
        val b=immersionCycleTiming("longer",thousand,thousand)
        assertTrue(b.sourceMs>a.sourceMs);assertTrue(b.targetMs>a.targetMs)
        val shortTarget=immersionCycleTiming("unequal",fiveHundred,"pronto")
        assertTrue(shortTarget.targetMs>=shortTarget.sourceMs+4000L)
        assertEquals(4000L,immersionCycleTiming("unicode","l'azione è pronta","l’azione è pronta").sourceMs)
    }
    @Test fun phaseBoundariesAreDeterministicWithoutFrameTicking(){
        val timing=ImmersionCycleTiming(2500,4000,0)
        assertEquals(ImmersionCyclePhase(false,4000),immersionCyclePhase(0,timing))
        assertEquals(ImmersionCyclePhase(true,2500),immersionCyclePhase(4000,timing))
        assertEquals(ImmersionCyclePhase(false,4000),immersionCyclePhase(6500,timing))
    }
    @Test fun oneFlowPreservesProtectedContentAndIndependentChoices(){
        val source="Check `git status`: two new sessions and an important question."
        val a=source.indexOf("two new sessions");val b=source.indexOf("an important question")
        val spans=listOf(aligned(a,"two new sessions","due|two nuove|new sessioni|sessions"),aligned(b,"an important question","una domanda|question importante|important"))
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        assertEquals("Check `git status`: two new sessions and una domanda importante.",immersionCyclePlan(plan,setOf(0)).text)
        assertEquals("Check `git status`: two new sessions and una question important.",immersionCyclePlan(plan,setOf(0,1)).text)
        assertEquals(source,plan.source)
        assertEquals(plan.text,immersionCyclePlan(plan,emptySet()).text)
        assertFalse(immersionCyclePlan(plan,setOf(0)).text.contains('\n'))
    }
    @Test fun reserveSlotsKeepExactFormsAndWrapAtWordBoundaries(){
        val words=immersionReserveWords("to the new session","alla nuova sessione")
        assertEquals("to the new session",words.joinToString(""){it.source})
        assertEquals("alla nuova sessione",words.joinToString(""){it.target})
        assertEquals(4,words.size)
        for(scale in listOf(1,2)){
            val reserved=words.map{maxOf(it.source.length,it.target.length)*scale}
            words.forEachIndexed{index,word->assertTrue(reserved[index]>=word.source.length*scale);assertTrue(reserved[index]>=word.target.length*scale)}
            assertTrue(reserved.maxOrNull()!!<maxOf("to the new session".length,"alla nuova sessione".length)*scale)
        }
    }
    @Test fun shortPhraseIsOneNaturalSlotAndLongFormsWrapWithoutWordColumns(){
        val short=immersionReserveChunks("two new sessions","due nuove sessioni",200){it.length*5}
        assertEquals(listOf(ImmersionReserveWord("two new sessions","due nuove sessioni")),short)
        val source="to the very important new conversation tomorrow"
        val target="alla nuova conversazione molto importante domani"
        val long=immersionReserveChunks(source,target,100){it.length*5}
        assertEquals(source,long.joinToString(""){it.source})
        assertEquals(target,long.joinToString(""){it.target})
        assertTrue(long.size>1)
        assertTrue(long.size<source.split(" ").size)
        assertTrue(long.all{it.source.length*5<=100&&it.target.length*5<=100})
    }
    @Test fun paragraphFormsPreserveNaturalSpacingAndRejectOverlappingRanges(){
        val text="Read nuove sessioni, then check `git status`."
        val at=text.indexOf("nuove sessioni")
        assertEquals("Read new sessions, then check `git status`.",immersionParagraphForm(text,listOf(Triple(at,at+14,"new sessions"))))
        assertNull(immersionParagraphForm(text,listOf(Triple(0,5,"a"),Triple(3,6,"b"))))
        assertTrue(immersionCycleTiming("message","one word","una parola").sourceMs>=4000)
        assertTrue(immersionCycleTiming("message","one word","una parola").targetMs>=8000)
    }
    @Test fun recreatedCompositionRecoversCapturedMixedSnapshot(){
        val source="Read new sessions and important questions now."
        val a=source.indexOf("new sessions");val b=source.indexOf("important questions")
        val spans=listOf(aligned(a,"new sessions","nuove|new sessioni|sessions"),aligned(b,"important questions","domande|questions importanti|important"))
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        val captured=immersionCyclePlan(plan,setOf(1)).text
        assertEquals(setOf(1),immersionCapturedOriginals(plan,"Context: $captured"))
        assertEquals(setOf(0,1),immersionCapturedOriginals(plan,immersionCyclePlan(plan,setOf(0,1)).text))
        assertNull(immersionCapturedOriginals(plan,source)) // Natural-English reordering is not a scaffold snapshot.
        assertEquals(emptySet<Int>(),immersionCapturedOriginals(plan,plan.text))
        assertNull(immersionCapturedOriginals(plan,captured.replace("now.","later.")))
    }
    @Test fun invalidCanonicalPlanCannotCycleUnverifiedText(){
        val plan=ImmersionPresentation("wrong","original",true,listOf(ImmersionSpan(0,8,"original","nuovo")))
        assertEquals("original",immersionCyclePlan(plan,emptySet()).text)
        assertTrue(immersionCyclePlan(plan,emptySet()).spans.isEmpty())
    }
    @Test fun ordinaryInteractionDoesNotRestartTheFullReadingHold(){
        var now=0L
        val deadline=ImmersionCueDeadline{now}
        assertEquals(8000L,deadline.waitMs(8000))
        now=6000
        assertEquals(2000L,deadline.waitMs(8000))
        now=60000 // Background, keyboard, or speech cannot produce a catch-up storm.
        assertEquals(1000L,deadline.waitMs(8000))
        deadline.hold(4000)
        assertEquals(4000L,deadline.waitMs(8000))
        now+=2500
        assertEquals(1500L,deadline.waitMs(8000))
    }
    @Test fun firstCueDependsOnVisiblePhraseRatherThanThousandsOfOffscreenWords(){
        val long=List(1000){"session"}.joinToString(" ")
        val spans=listOf(ImmersionSpan(0,long.length,long,long),ImmersionSpan(long.length+1,long.length+13,"new sessions","nuove sessioni"))
        val visible=setOf("${spans[1].start}:${spans[1].end}")
        val timing=immersionVisibleTiming("message",spans,0,visible)!!
        assertEquals(4000L,timing.sourceMs)
        assertEquals(8000L,timing.targetMs)
        assertNull(immersionVisibleTiming("message",spans,0,emptySet()))
    }

    @Test fun pausedSourceHoldReturnsToTargetAfterOnlyItsRemainingDeadline(){
        var now=0L
        val deadline=ImmersionCueDeadline{now}
        var original=false
        assertTrue(immersionNeedsSourceHandoff(original))
        original=true // Semantic handoff occurred; cancelled animation cannot undo it.
        deadline.hold(4000)
        now=2500 // Touch interrupted the source reading hold.
        assertEquals(1500L,deadline.waitMs(8000))
        now+=1500
        assertFalse(immersionNeedsSourceHandoff(original))
        // Cancellation before handoff retains target, which still needs a source cue.
        assertTrue(immersionNeedsSourceHandoff(false))
    }

}
