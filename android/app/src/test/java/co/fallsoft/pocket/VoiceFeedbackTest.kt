package co.fallsoft.pocket

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class VoiceFeedbackTest {
    @Test(timeout=8000) fun blockedAudioSetupCannotBlockControlOrAccumulateLateBeeps(){
        val executor=Executors.newSingleThreadExecutor()
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val released=CountDownLatch(1)
        val factories=AtomicInteger();val sounds=AtomicInteger()
        val channel=VoiceFeedbackChannel(executor,{0L}){
            factories.incrementAndGet();entered.countDown()
            check(unblock.await(5,TimeUnit.SECONDS))
            object:FeedbackTone {
                override fun play(ok:Boolean){sounds.incrementAndGet()}
                override fun release(){released.countDown()}
            }
        }
        try {
            val owner=channel.open();channel.play(owner,true)
            assertTrue("Factory did not reach controlled blocking boundary",entered.await(2,TimeUnit.SECONDS))
            // Real background native setup remains blocked throughout these calls.
            repeat(500){channel.play(owner,it%2==0)}
            channel.close(owner)
            assertEquals(1L,unblock.count)
            unblock.countDown()
            assertTrue("Cancelled native resource was not released",released.await(2,TimeUnit.SECONDS))
            assertEquals("Only one native owner may be constructed",1,factories.get())
            assertEquals("Cancelled queued sounds must never play",0,sounds.get())
        }finally{unblock.countDown();executor.shutdownNow();executor.awaitTermination(2,TimeUnit.SECONDS)}
    }
}
