package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class CoordinatorSpeechTest {
    @Test fun coordinatorReaderRequiresExactOwnershipAndCurrentActiveChunk(){
        val current=SpeechCaptionState(42,"Coordinator","Current spoken chunk",0,2,"Speaking")
        fun preview(owner:String?="coordinator-reader",id:Long?=42,paused:Boolean=false,state:SpeechCaptionState=current)=coordinatorLiveSpeechCaption("coordinator-reader",owner,id,paused,state)
        assertEquals("Current spoken chunk",preview())
        assertEquals("",preview(owner=null)) // Automatic speech belongs to other Codex sessions.
        assertEquals("",preview(owner="another-reader"))
        assertEquals("",preview(id=43))
        assertEquals("",preview(id=null))
        assertEquals("",preview(paused=true))
        assertEquals("",preview(state=current.complete()))
        assertEquals("",preview(state=current.dismiss()))
    }
    @Test fun giantCoordinatorSpeechNeverProducesAnUnboundedOrBrokenUnicodePreview(){
        val caption=SpeechCaptionState(42,"Coordinator","😀".repeat(25000),0,1,"Speaking")
        val preview=coordinatorLiveSpeechCaption("reader","reader",42,false,caption)
        assertEquals(321,preview.codePointCount(0,preview.length))
        assertTrue(preview.endsWith("…"))
        assertEquals("",coordinatorLiveSpeechCaption("reader","reader",42,true,caption))
    }
}
