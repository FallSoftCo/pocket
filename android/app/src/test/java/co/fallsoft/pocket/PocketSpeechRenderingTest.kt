package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class PocketSpeechRenderingTest {
    private fun message(text:String="Original English notification.")=SpokenMessage(7,"Session","completed",text)
    @Test fun longerItalianRenderingFinishesAllChunksWithoutOverwritingSource() {
        val original=message();val queue=SpeechQueue(mutableListOf(original));val rendering=listOf("Prima parte","Seconda parte","Terza parte")
        assertTrue(completeRenderedSpeechChunk(queue,7,0,rendering,"device-a","device-a"))
        assertNotNull(queue.current);assertEquals(1,queue.chunkIndex)
        assertEquals("Original English notification.",queue.current!!.text)
        assertTrue(completeRenderedSpeechChunk(queue,7,1,rendering,"device-a","device-a"))
        assertNotNull(queue.current)
        assertTrue(completeRenderedSpeechChunk(queue,7,2,rendering,"device-a","device-a"))
        assertNull(queue.current);assertEquals(0,queue.chunkIndex)
        assertEquals("Original English notification.",original.text)
    }
    @Test fun shorterRenderingCompletesWithoutReadingAnExtraSourceChunk() {
        val original=message("Source sentence. ".repeat(500));val queue=SpeechQueue(mutableListOf(original))
        assertTrue(SpeechText.chunks(original.text).size>1)
        assertTrue(completeRenderedSpeechChunk(queue,7,0,listOf("Risposta breve."),"a","a"))
        assertTrue(queue.messages.isEmpty());assertEquals("Source sentence. ".repeat(500),original.text)
    }
    @Test fun stalePlayerCallbacksAndOtherProfileCannotMoveCursor() {
        val queue=SpeechQueue(mutableListOf(message()),positionMs=350)
        for(args in listOf(Triple(8L,0,"a"),Triple(7L,1,"a"),Triple(7L,0,"other-device"))) {
            assertFalse(completeRenderedSpeechChunk(queue,args.first,args.second,listOf("Uno","Due"),"a",args.third))
            assertEquals(0,queue.chunkIndex);assertEquals(350,queue.positionMs);assertNotNull(queue.current)
        }
    }
    @Test fun pauseResumeKeepsExactRenderedCursorAndIgnoresRepeatedCompletion() {
        val queue=SpeechQueue(mutableListOf(message()));val chunks=listOf("Uno","Due")
        queue.pause(780,"Paused")
        assertFalse(completeRenderedSpeechChunk(queue,7,0,chunks,"a","a"));assertEquals(780,queue.positionMs)
        queue.resume()
        assertTrue(completeRenderedSpeechChunk(queue,7,0,chunks,"a","a"));assertEquals(1,queue.chunkIndex);assertEquals(0,queue.positionMs)
        assertFalse(completeRenderedSpeechChunk(queue,7,0,chunks,"a","a"));assertEquals(1,queue.chunkIndex)
        assertTrue(completeRenderedSpeechChunk(queue,7,1,chunks,"a","a"));assertTrue(queue.messages.isEmpty())
    }
}
