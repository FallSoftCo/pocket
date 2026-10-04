package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class MarkdownContentTest {
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
