package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test

class WorkCatalogStateTest {
 private val first=WorkSourceKey("one","owner1")
 private val second=WorkSourceKey("two","owner2")
 private fun row(source:WorkSourceKey,kind:WorkKind=WorkKind.SESSION,time:Long=5)=WorkEntry(WorkItemKey(source,kind,if(kind==WorkKind.ROOM)"authority" else "", "same-id"),time,"body")
 @Test fun sameIdsAcrossHostsAndRoomKindsRemainDistinctAndFailureDoesNotHideHealthyHost(){
  val state=WorkCatalogState<String>();state.reconcile(listOf(first,second))
  val a=state.begin(first)!!;val b=state.begin(second)!!
  state.accept(second,b,listOf(row(second)),100)
  assertEquals(1,state.ordered(emptyList()).size)
  state.accept(first,a,listOf(row(first),row(first,WorkKind.ROOM)),101)
  assertEquals(3,state.ordered(emptyList()).map{it.key}.toSet().size)
  val failure=state.begin(first)!!;state.fail(first,failure,"Offline")
  assertEquals(3,state.ordered(emptyList()).size)
  assertFalse(state.snapshots().first{it.key==first}.available)
  assertTrue(state.snapshots().first{it.key==second}.available)
 }
 @Test fun removedCredentialResponseCannotReenterAndRefreshDoesNotCreateRecency(){
  val state=WorkCatalogState<String>();state.reconcile(listOf(first,second))
  val old=state.begin(first)!!;state.reconcile(listOf(second));state.reconcile(listOf(first,second))
  assertFalse(state.accept(first,old,listOf(row(first)),500))
  val a=state.begin(first)!!;val b=state.begin(second)!!
  state.accept(first,a,listOf(row(first,time=10)),99999)
  state.accept(second,b,listOf(row(second,time=20)),100)
  assertEquals(second,state.ordered(emptyList()).first().key.source)
  val retained=state.ordered(emptyList()).map{it.key}
  val refresh=state.begin(first)!!;state.accept(first,refresh,listOf(row(first,time=10)),1000000)
  assertEquals(retained,state.ordered(retained).map{it.key})
  assertFalse(state.restore(first,listOf(row(first,time=1000))))
 }
 @Test fun rejectsForeignSourceAndKeepsOneReadPerSource(){
  val state=WorkCatalogState<String>();state.reconcile(listOf(first))
  val ticket=state.begin(first)!!;assertNull(state.begin(first))
  try{state.accept(first,ticket,listOf(row(second)),1);fail("Foreign item accepted")}catch(expected:IllegalArgumentException){}
  assertTrue(state.fail(first,ticket,"Failed"))
 }

 @Test fun slowSourceDoesNotDelayHealthySourceAndRepeatedRefreshDoesNotDuplicateReads()=kotlinx.coroutines.runBlocking {
  val state=WorkCatalogState<String>();var snapshots=emptyList<WorkSourceSnapshot<String>>()
  val child=kotlinx.coroutines.CoroutineScope(coroutineContext+kotlinx.coroutines.SupervisorJob(coroutineContext[kotlinx.coroutines.Job]))
  val slow=kotlinx.coroutines.CompletableDeferred<Unit>();val ready=kotlinx.coroutines.CompletableDeferred<Unit>()
  var starts=0
  val poller=WorkCatalogPoller(child,state,{snapshots=it;if(it.any{s->s.key==second&&s.available})ready.complete(Unit)},{100L})
  try{
   poller.reconcile(linkedMapOf(first to {starts++;slow.await();listOf(row(first))},second to {starts++;listOf(row(second))}))
   poller.refresh();ready.await()
   assertTrue(snapshots.first{it.key==second}.available)
   assertTrue(snapshots.first{it.key==first}.refreshing)
   poller.refresh();kotlinx.coroutines.yield()
   assertEquals(3,starts) // A new completed healthy read is allowed; slow read stays single flight.
   slow.complete(Unit);kotlinx.coroutines.yield()
   assertTrue(snapshots.all{it.available})
   val retired=WorkSourceKey("one","replacement-credential")
   poller.reconcile(mapOf(retired to {listOf(row(retired))}))
   assertEquals(listOf(retired),snapshots.map{it.key})
  }finally{poller.stop();child.coroutineContext[kotlinx.coroutines.Job]!!.cancel()}
 }

}
