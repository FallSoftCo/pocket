package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class CoordinatorRouteTest {
 @Test fun identicalTextTurnsKeepDistinctDeliveryIdentity(){
  val old=CoordinatorMessage("old","Same request","Same answer")
  val fresh=CoordinatorMessage("fresh","Same request","Same answer")
  assertNotEquals(old,fresh)
  val receipts=mapOf(old.id to "old target",fresh.id to "new target")
  assertEquals("old target",receipts[old.id]);assertEquals("new target",receipts[fresh.id])
 }

 @Test fun onlyFullyVisibleAssistantItemsCountAsPresented(){
  assertTrue(coordinatorItemFullyVisible(20,80,0,120))
  assertFalse(coordinatorItemFullyVisible(-1,80,0,120))
  assertFalse(coordinatorItemFullyVisible(20,101,0,120))
  assertFalse(coordinatorItemFullyVisible(0,240,0,120))
  assertFalse(coordinatorItemFullyVisible(0,0,0,120))
 }

 @Test fun submissionDoesNotClaimCompletion(){
  assertEquals("Submitted",CoordinatorRoute("a","Work","send","steer","submitted").label)
  assertEquals("Queued",CoordinatorRoute("a","Work","send","queue","queued").label)
  assertEquals("Completed",CoordinatorRoute("a","Work","send","steer","completed").label)
  assertEquals("Read",CoordinatorRoute("a","Work","read","","").label)
 }
 @Test fun correctionKeepsIdentityOutOfVisibleDraft(){
  val route=CoordinatorRoute("original-id","Fans","send","steer","submitted","turn-id")
  assertFalse(route.correction.contains("original-id"))
  assertFalse(route.correction.contains("turn-id"))
  assertTrue(route.correction.contains("The intended session is"))
 }
}
