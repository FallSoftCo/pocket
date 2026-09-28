package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class SpeechQueueTest {
    private fun message(id:Long,text:String="A complete spoken update.")=SpokenMessage(id,"Update","update",text)
    @Test fun pauseRetainsCursorAndNewMessagesWait(){
        val q=SpeechQueue();q.enqueue(message(1));q.pause(8123,"Music")
        q.enqueue(message(2));q.enqueue(message(1))
        assertTrue(q.paused);assertEquals(8123,q.positionMs);assertEquals(listOf(1L,2L),q.messages.map{it.id})
        // A late completion from the released player must not erase paused speech.
        assertFalse(q.completed(1,0));assertEquals(1L,q.current!!.id)
        q.resume();assertEquals(8123,q.positionMs);assertTrue(q.completed(1,0))
        assertEquals(2L,q.current!!.id);assertEquals(0,q.positionMs)
    }
    @Test fun restoreMidMessageContinuesThroughEveryChunkAndFollowingMessage(){
        val text=(1..100).joinToString(" "){"Sentence number $it has useful information."}
        val q=SpeechQueue();q.enqueue(message(1,text));q.enqueue(message(2))
        val first=q.chunk!!;assertTrue(q.completed(1,0));q.pause(4200,"Paused")
        val restored=SpeechQueue(q.messages.toMutableList(),q.chunkIndex,q.positionMs,true,"Saved")
        assertEquals(q.chunk,restored.chunk);assertEquals(4200,restored.positionMs)
        assertFalse(restored.completed(1,0));restored.resume()
        val remaining=StringBuilder(first)
        while(restored.current?.id==1L){remaining.append(restored.chunk);assertTrue(restored.completed(1,restored.chunkIndex))}
        assertEquals(SpeechText.clean(text),remaining.toString());assertEquals(2L,restored.current!!.id)
        assertFalse(restored.completed(1,0));assertTrue(restored.completed(2,0));assertNull(restored.current)
    }
    @Test fun queueNeverDropsAnInterruptedMessageToMakeRoom(){
        val q=SpeechQueue();q.enqueue(message(1));q.pause(3000,"Other audio")
        (2L..20L).forEach{q.enqueue(message(it))}
        assertEquals(20,q.messages.size);assertEquals(1L,q.current!!.id);assertEquals(3000,q.positionMs)
    }
}
