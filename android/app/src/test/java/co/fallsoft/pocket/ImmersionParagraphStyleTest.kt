package co.fallsoft.pocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class ImmersionParagraphStyleTest {
    @Test fun envelopeKeepsSurroundingBoldAndPhraseItalicAcrossLengthChange(){
        val content=buildAnnotatedString{append("Read new sessions now.");addStyle(SpanStyle(fontWeight=FontWeight.Bold),0,4);addStyle(SpanStyle(fontStyle=FontStyle.Italic),5,17)}
        val alternate=immersionAnnotatedParagraph(content,listOf(Triple(5,17,AnnotatedString("nuove sessioni"))))!!
        assertEquals("Read nuove sessioni now.",alternate.text)
        assertTrue(alternate.spanStyles.any{it.start==0&&it.end==4&&it.item.fontWeight==FontWeight.Bold})
        assertTrue(alternate.spanStyles.any{it.start==5&&it.end==19&&it.item.fontStyle==FontStyle.Italic})
    }
    @Test fun replacementOwnStylesRemainExactAndOverlapRejected(){
        val replacement=buildAnnotatedString{append("nuove sessioni");addStyle(SpanStyle(fontWeight=FontWeight.Bold),0,5)}
        val alternate=immersionAnnotatedParagraph(AnnotatedString("new sessions!"),listOf(Triple(0,12,replacement)))!!
        assertEquals("nuove sessioni!",alternate.text)
        assertTrue(alternate.spanStyles.any{it.start==0&&it.end==5&&it.item.fontWeight==FontWeight.Bold})
        assertNull(immersionAnnotatedParagraph(AnnotatedString("123456"),listOf(Triple(0,4,replacement),Triple(3,5,replacement))))
    }
    @Test fun canvasPresentationKeepsVisibleUrlStyleWithoutDuplicatingTheText(){
        val content=buildAnnotatedString{
            append("Read the guide.")
            addLink(LinkAnnotation.Url("https://example.com/guide",TextLinkStyles(style=SpanStyle(color=Color.Yellow,textDecoration=TextDecoration.Underline))),5,14)
        }
        val drawn=immersionDrawText(content)
        assertEquals(content.text,drawn.text)
        assertEquals(1,drawn.getLinkAnnotations(0,drawn.length).size)
        assertTrue(drawn.spanStyles.any{it.start==5&&it.end==14&&it.item.color==Color.Yellow&&it.item.textDecoration==TextDecoration.Underline})
    }

}
