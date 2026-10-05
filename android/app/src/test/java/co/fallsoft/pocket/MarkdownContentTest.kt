package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
    @Test fun repeatedWordsResolveOnlyTheSelectedSourceOccurrencesAcrossMarkupAndProtectedCode(){
        val source="**sessions** then `sessioni` and sessions."
        val first=ImmersionSpan(2,10,"sessions","sessioni");val last=ImmersionSpan(source.lastIndexOf("sessions"),source.lastIndexOf("sessions")+8,"sessions","sessioni")
        val plan=ImmersionPresentation(contextualHybridText(source,listOf(first,last))!!,source,true,listOf(first,last))
        val ranges=MarkdownContent.replacementRanges(plan,plan.text,null);val visible=MarkdownContent.preview(plan.text).text
        assertEquals(2,ranges.size);assertEquals(0,ranges[0].start);assertEquals(visible.lastIndexOf("sessioni"),ranges[1].start)
        val rescued=immersionReplacementText(plan,first);val mapped=MarkdownContent.replacementRanges(plan,rescued,first)
        assertEquals("sessions then sessioni and sessioni.",MarkdownContent.preview(rescued).text)
        assertEquals(listOf("sessions","sessioni"),mapped.map{MarkdownContent.preview(rescued).text.substring(it.start,it.end)})
        assertTrue(MarkdownContent.preview(rescued).spans.any{it.kind=="bold"});assertTrue(MarkdownContent.preview(rescued).spans.any{it.kind=="code"})
    }
    @Test fun replacementRangesIgnoreLinkDestinationMatchesAndTrackHeadingListPrefixes(){
        val source="# sessions\n\n- sessions with [docs](https://example.com/sessioni)"
        val a=source.indexOf("sessions");val b=source.lastIndexOf("sessions");val spans=listOf(ImmersionSpan(a,a+8,"sessions","sessioni"),ImmersionSpan(b,b+8,"sessions","sessioni"))
        val plan=ImmersionPresentation(contextualHybridText(source,spans)!!,source,true,spans)
        val text=MarkdownContent.preview(plan.text).text;val ranges=MarkdownContent.replacementRanges(plan,plan.text,null)
        assertEquals(2,ranges.size);assertEquals(text.indexOf("sessioni"),ranges[0].start);assertEquals(text.lastIndexOf("sessioni"),ranges[1].start)
        assertEquals("https://example.com/sessioni",MarkdownContent.preview(plan.text).spans.single{it.kind=="link"}.destination)
        assertFalse(text.contains('\uE000'));assertFalse(text.contains('\uE001'))
    }
    @Test fun nestedFormattingHasStylesWithoutLiteralDelimiters(){
        val parsed=MarkdownContent.preview("**Bold with *emphasis*** and `a ** b`.")
        assertEquals("Bold with emphasis and a ** b.",parsed.text)
        assertTrue(parsed.spans.any{it.kind=="bold"})
        assertTrue(parsed.spans.any{it.kind=="italic"})
        assertTrue(parsed.spans.any{it.kind=="code"&&parsed.text.substring(it.start,it.end)=="a ** b"})
    }
    @Test fun escapedStarsAndIncompleteStreamingMarkupStayReadable(){
        assertEquals("**literal**",MarkdownContent.preview("\\*\\*literal\\*\\*").text)
        assertEquals("**Still writing",MarkdownContent.preview("**Still writing").text)
    }
    @Test fun blocksPreserveHeadingListQuoteAndLiteralCode(){
        val blocks=MarkdownContent.blocks("## Result\n\n1. First\n2. **Second**\n\n> A quote\n\n```kotlin\nval x = \"**literal**\"\n```\n")
        assertEquals("heading",blocks.first().kind);assertEquals(2,blocks.first().level)
        assertEquals(listOf("1. ","2. "),blocks.filter{it.prefix.isNotEmpty()}.map{it.prefix})
        assertTrue(blocks.any{it.kind=="quote"})
        assertEquals("val x = \"**literal**\"",blocks.last().content.text)
        assertEquals("kotlin",blocks.last().language)
    }
    @Test fun linkLabelAndDestinationArePreservedWithoutMarkdownSyntax(){
        val inline=MarkdownContent.preview("Read [the docs](https://example.com/docs) next.")
        assertEquals("Read the docs next.",inline.text)
        assertEquals("https://example.com/docs",inline.spans.single().destination)
        assertEquals("the docs",inline.text.substring(inline.spans.single().start,inline.spans.single().end))
    }
}
