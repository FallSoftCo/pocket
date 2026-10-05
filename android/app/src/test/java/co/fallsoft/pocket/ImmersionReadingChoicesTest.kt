package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class ImmersionReadingChoicesTest {
    private val source="I opened two new sessions today."
    private val span=ImmersionSpan(9,25,"two new sessions","due nuove sessioni")
    private val plan=ImmersionPresentation(contextualHybridText(source,listOf(span))!!,source,true,listOf(span))
    private fun key(p:ImmersionPresentation=plan,owner:String="owner",conversation:String="thread",message:String="row",sourceLanguage:String="source",targetLanguage:String="it")=
        immersionReadingKey(owner,conversation,message,p,sourceLanguage,targetLanguage)

    @Test fun unchangedHydrationRebindsOpenHelpToCurrentMetadata(){
        val choices=ImmersionReadingChoices();choices.restore("owner");choices.select(key(),span)
        val hydrated=span.copy(note="Fresh metadata",targetSegments=listOf(ImmersionTargetSegment(0,span.target.length,span.target,"two new sessions","other")))
        val backfill=plan.copy(spans=listOf(hydrated))
        assertFalse(choices.restore("owner"))
        assertSame(hydrated,choices.selected(key(backfill),backfill))
        assertEquals("I opened two new sessions today.",immersionReplacementText(backfill,choices.selected(key(backfill),backfill)))
    }

    @Test fun temporarySourceFallbackAndComposableRemountDoNotEraseExactChoice(){
        val choices=ImmersionReadingChoices();choices.restore("owner");choices.select(key(),span)
        val pending=ImmersionPresentation(source,source,false)
        assertNull(choices.selected(key(pending),pending))
        assertEquals(span,choices.selected(key(),plan))
        choices.select(key(),null)
        assertNull(choices.selected(key(),plan))
    }

    @Test fun changedOwnerConversationMessageContentOrLanguagesNeverInheritHelp(){
        val choices=ImmersionReadingChoices();choices.restore("owner");choices.select(key(),span)
        assertNull(choices.selected(key(conversation="other"),plan))
        assertNull(choices.selected(key(message="other"),plan))
        assertNull(choices.selected(key(sourceLanguage="fr"),plan))
        assertNull(choices.selected(key(targetLanguage="de"),plan))
        val changed=plan.copy(source=source.replace("today","tomorrow"),text=plan.text.replace("today","tomorrow"))
        assertNull(choices.selected(key(changed),changed))
        val newTarget=span.copy(target="due sessioni nuove")
        val changedTarget=plan.copy(text=contextualHybridText(source,listOf(newTarget))!!,spans=listOf(newTarget))
        assertNull(choices.selected(key(changedTarget),changedTarget))
        assertTrue(choices.restore("different-owner"))
        assertNull(choices.selected(key(owner="different-owner"),plan))
        choices.restore("owner")
        assertNull(choices.selected(key(),plan))
    }

    @Test fun changedAnchorsCannotApplyOldChoiceEvenIfRenderedTextMatches(){
        val choices=ImmersionReadingChoices();choices.restore("owner");choices.select(key(),span)
        assertNull(choices.selected(key(),plan.copy(spans=listOf(span.copy(start=10)))))
        assertNull(choices.selected(key(),plan.copy(spans=emptyList())))
    }

    @Test fun readingChoiceCacheIsBoundedAndExplicitResetClearsIt(){
        val choices=ImmersionReadingChoices(2);choices.restore("owner")
        choices.select(key(message="one"),span);choices.select(key(message="two"),span);choices.select(key(message="three"),span)
        assertNull(choices.selected(key(message="one"),plan))
        assertEquals(span,choices.selected(key(message="two"),plan))
        choices.clear();assertNull(choices.selected(key(message="two"),plan))
    }
}
