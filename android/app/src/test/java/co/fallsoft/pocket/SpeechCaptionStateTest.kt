package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class SpeechCaptionStateTest {
    @Test fun dismissalSurvivesProgressButNewMessageReturns(){
        val first=SpeechCaptionState().update(4,"Build","Compiling",0,2).dismiss()
        assertFalse(first.update(4,"Build","Tests passed",1,2).visible)
        assertTrue(first.update(5,"Release","Ready",0,1).visible)
    }
    @Test fun completionKeepsActualSpokenTextAndChunkProgress(){
        val state=SpeechCaptionState().update(4,"Build","Tests passed",1,2).complete()
        assertEquals("Tests passed",state.text);assertTrue(state.visible)
        assertEquals("Spoken · Part 2 of 2",state.label)
        assertTrue(state.pause().visible)
    }
}
