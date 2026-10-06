package co.fallsoft.pocket

import androidx.compose.ui.text.*
import org.junit.Assert.*
import org.junit.Test

class ImmersionCueRangeTest {
    private fun paragraph(first:String,second:String)=buildAnnotatedString{
        append("Read ");val a=length;append(first);addStringAnnotation("immersion-reserve","5:17",a,length)
        append(", then ");val b=length;append(second);addStringAnnotation("immersion-reserve","24:31",b,length)
        append(" now.")
    }
    @Test fun cueTracksOnlyChangedPhraseNotWordsShiftedByEarlierReplacement(){
        val from=paragraph("new sessions","history")
        val to=paragraph("le nuove sessioni","history")
        val ranges=immersionChangedTextRanges(from,to)
        assertEquals(listOf(TextRange(5,17)),ranges.first)
        assertEquals(listOf(TextRange(5,22)),ranges.second)
        assertEquals("new sessions",from.subSequence(ranges.first.single().start,ranges.first.single().end).text)
        assertEquals("le nuove sessioni",to.subSequence(ranges.second.single().start,ranges.second.single().end).text)
    }
    @Test fun labelCueCoversItsMeaningfulLabelInsteadOfCoincidentalMatchingLetters(){
        val ranges=immersionChangedTextRanges(AnnotatedString("Stop"),AnnotatedString("Ferma"))
        assertEquals(listOf(TextRange(0,4)),ranges.first)
        assertEquals(listOf(TextRange(0,5)),ranges.second)
    }
    @Test fun callbackAndStyleRefreshDoesNotInventAVisibleWordChange(){
        val from=paragraph("new sessions","history")
        val to=buildAnnotatedString{append(from);addStyle(SpanStyle(color=androidx.compose.ui.graphics.Color.Yellow),0,4)}
        assertTrue(immersionChangedTextRanges(from,to).first.isEmpty())
        assertTrue(immersionChangedTextRanges(from,to).second.isEmpty())
    }
}
