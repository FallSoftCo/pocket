package co.fallsoft.pocket

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class TeamComposerTest {
    @Test fun shiftedEnterEditsAtTheCaretInsteadOfAppending(){
        val result=teamInsertNewline(TextFieldValue("FirstSecond",TextRange(5)))
        assertEquals("First\nSecond",result.text)
        assertEquals(TextRange(6),result.selection)
    }
    @Test fun shiftedEnterReplacesSelectedTextAndCommitsComposition(){
        val result=teamInsertNewline(TextFieldValue("FirstREMOVESecond",TextRange(11,5),TextRange(5,11)))
        assertEquals("First\nSecond",result.text)
        assertEquals(TextRange(6),result.selection)
        assertNull(result.composition)
    }
}
