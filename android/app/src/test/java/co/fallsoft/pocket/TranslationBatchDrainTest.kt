package co.fallsoft.pocket

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationBatchDrainTest {
    @Test fun arrivalDuringRequestIsDeliveredWithoutAnotherOffer()=runBlocking {
        val queue=mutableListOf("first thought")
        val submitted=mutableListOf<List<String>>()
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val worker=launch {
            drainTranslationBatches(isCurrent={true},coalesce={},nextBatch={queue.toList().also{queue.clear()}},submit={batch->
                submitted.add(batch)
                if(submitted.size==1){started.complete(Unit);release.await()}
            })
        }
        started.await();queue.add("newer thought");release.complete(Unit);worker.join()
        assertEquals(listOf(listOf("first thought"),listOf("newer thought")),submitted)
        assertTrue(queue.isEmpty())
    }
    @Test fun profileSwitchDuringRequestDoesNotSendQueuedTexts()=runBlocking {
        val queue=mutableListOf("first")
        var current=true;var calls=0
        drainTranslationBatches(isCurrent={current},coalesce={},nextBatch={queue.toList().also{queue.clear()}},submit={
            calls++;queue.add("different profile text");current=false
        })
        assertEquals(1,calls);assertEquals(listOf("different profile text"),queue)
    }
    @Test fun failureDoesNotAutomaticallyRetryOrConsumeNextBatch()=runBlocking {
        val queue=mutableListOf("first");var calls=0
        try {
            drainTranslationBatches(isCurrent={true},coalesce={},nextBatch={queue.toList().also{queue.clear()}},submit={
                calls++;queue.add("newer");throw IllegalStateException("offline")
            })
            fail("Failure must stop delivery")
        } catch(_:IllegalStateException){}
        assertEquals(1,calls);assertEquals(listOf("newer"),queue)
    }
}
