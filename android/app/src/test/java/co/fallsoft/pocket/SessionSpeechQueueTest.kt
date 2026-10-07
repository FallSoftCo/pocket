package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class SessionSpeechQueueTest {
 @Test fun previewHasExplicitScopeWithoutInventingThread(){val s=SessionSpeechQueues();val preview=SpokenMessage(-11,"Audio preview","preview","Question preview");s.enqueue(preview);s.enqueue(SpokenMessage(12,"Legacy","question","Unknown"));assertEquals("@audio-preview",preview.sourceKey);assertNull(preview.threadId);assertEquals(-11L,s.queues["@audio-preview"]!!.current!!.id);assertEquals(12L,s.queues[null]!!.current!!.id);val ledger=CompletedSpeechLedger();ledger.completed(preview.id);assertFalse(ledger.contains(preview.id))}

 @Test fun voicePriorityHoldsNotificationPlaybackAcrossEntrypoints(){assertNull(notificationSpeechHoldReason(false));assertEquals("Voice mode is active · Stop voice to listen",notificationSpeechHoldReason(true))}

 @Test fun inactiveOriginCannotEnterActiveProfileQueue(){assertFalse(speechOriginMatches(false,true));assertFalse(speechOriginMatches(true,false));assertTrue(speechOriginMatches(false,false));assertTrue(speechOriginMatches(true,true));assertTrue(speechOriginMatches(null,true));assertTrue(speechOriginMatches(null,false))}

 @Test fun confirmedCompletionDeduplicatesAndBoundsHistory(){val ledger=CompletedSpeechLedger(limit=2);assertFalse(ledger.contains(1));ledger.completed(1);ledger.completed(2);ledger.completed(1);assertEquals(listOf(2L,1L),ledger.snapshot());ledger.completed(3);assertFalse(ledger.contains(2));assertTrue(ledger.contains(1));assertTrue(ledger.contains(3));ledger.completed(-1);assertEquals(2,ledger.snapshot().size)}

 private fun message(id:Long,source:String?,kind:String="answer")=SpokenMessage(id,"Session $source",kind,"An important answer",threadId=source)
 @Test fun interleavingPreservesIndependentOrder(){val s=SessionSpeechQueues();s.enqueue(message(1,"A"));s.enqueue(message(2,"B"));s.enqueue(message(3,"A"));assertEquals(listOf(1L,3L),s.queues["A"]!!.messages.map{it.id});assertEquals(listOf(2L),s.queues["B"]!!.messages.map{it.id})}
 @Test fun switchingSavesChunkAndPosition(){val s=SessionSpeechQueues();s.enqueue(message(1,"A"));s.enqueue(message(2,"B"));s.select("A");s.current.chunkIndex=2;s.current.positionMs=1730;s.select("B");s.select("A");assertEquals(2,s.current.chunkIndex);assertEquals(1730,s.current.positionMs);assertTrue(s.current.paused)}
 @Test fun completingActiveNeverDrainsAnotherSource(){val s=SessionSpeechQueues();s.enqueue(message(1,"A"));s.enqueue(message(2,"B"));s.select("A");s.current.resume();assertTrue(s.current.completed(1,0));assertNull(s.current.current);assertEquals("A",s.activeSource);assertEquals(2L,s.queues["B"]!!.current!!.id)}
 @Test fun legacyUnknownStaysUnknown(){val s=SessionSpeechQueues();s.enqueue(SpokenMessage(1,"Legacy","answer","hello"));s.enqueue(message(2,"A"));assertNull(s.queues[null]!!.current!!.threadId);assertEquals(1L,s.queues[null]!!.current!!.id)}
 @Test fun questionsAndAnswersAreNotCoalesced(){val s=SessionSpeechQueues();s.enqueue(message(1,"A","question"));s.enqueue(message(2,"A"));s.enqueue(message(3,"A","failure"));assertEquals(3,s.queues["A"]!!.messages.size)}
 @Test fun displayedSpeechParksActiveWithoutLosingOtherSources(){val s=SessionSpeechQueues();s.enqueue(message(1,"A"));s.enqueue(message(2,"B"));s.select("A");s.current.positionMs=123;val displayed=DisplayedSpeechSession();displayed.start("manual",s.current,message(-1,"A"));s.enqueue(message(3,"B"));assertSame(s.current,displayed.stop("manual"));assertEquals(123,s.current.positionMs);assertEquals(2,s.queues["B"]!!.messages.size)}
 @Test fun clearActivePreservesOtherGroups(){val s=SessionSpeechQueues();s.enqueue(message(1,"A"));s.enqueue(message(2,"B"));s.select("A");s.clearActive();assertNull(s.current.current);assertEquals(1,s.count);assertEquals(2L,s.queues["B"]!!.current!!.id)}
}
