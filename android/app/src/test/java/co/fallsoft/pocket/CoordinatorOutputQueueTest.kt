package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class CoordinatorOutputQueueTest {
 @Test fun repliesOwnOutputWhileNewOutcomesCoalesceByEnvironmentAndSession(){
  val q=CoordinatorOutputQueue()
  q.update(CoordinatorUtterance("old","Old result","Mac",environmentId="mac",threadId="same",seq=1))
  q.update(CoordinatorUtterance("new","Current result","Mac",environmentId="mac",threadId="same",seq=2))
  q.update(CoordinatorUtterance("pc","Other source","PC",environmentId="pc",threadId="same",seq=2))
  q.response(CoordinatorUtterance("reply","Your request was accepted.","Coordinator"))
  assertEquals(3,q.pending);assertEquals("reply",q.next()!!.id);assertNull(q.next())
  assertEquals("reply",q.complete("reply")!!.id);assertEquals("new",q.next()!!.id)
  assertNull(q.complete("old"));assertEquals("new",q.current!!.id)
  q.complete("new");assertEquals("pc",q.next()!!.id)
 }
 @Test fun bargeInNeverAcknowledgesOrQueuesHistoricalSpeechAndPendingQuestionsRemainDistinct(){
  val q=CoordinatorOutputQueue();val one=CoordinatorUtterance("one","First response","Coordinator")
  q.response(one);q.next();q.bargeIn();assertNull(q.complete("one"));assertEquals(0,q.pending)
  q.response(one);assertEquals("one",q.next()!!.id);q.complete("one");q.response(one);assertNull(q.next())
  q.update(CoordinatorUtterance("question","Choose a destination","Mac",environmentId="mac",threadId="same",seq=9,urgent=true))
  q.update(CoordinatorUtterance("result","Other completed work","Mac",environmentId="mac",threadId="same",seq=10))
  assertEquals(2,q.pending);assertEquals("question",q.next()!!.id)
 }
}
