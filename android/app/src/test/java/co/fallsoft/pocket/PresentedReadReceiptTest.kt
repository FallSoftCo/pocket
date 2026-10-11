package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class PresentedReadReceiptTest {
 private fun destination(id:String="one",token:String="credential")=EnvironmentRequest(EnvironmentIdentity(id,id),"https://example.invalid",token)
 private fun receipt(d:EnvironmentRequest=destination(),kind:String="catchup",value:String="capture-a")=PresentedReadReceipt(d.environment.id,backendOwner(d.environment.id+"\u0000"+d.endpoint,d.token),"thread",kind,value,1000)
 @Test fun exactPresentedCaptureHasStableIdentityButNewViewDoesNot(){assertEquals(receipt().id,receipt().copy(at=2000).id);assertNotEquals(receipt().id,receipt(value="capture-b").id)}
 @Test fun newerViewSharesAnOrderedLaneButOtherSourcesDoNot(){assertEquals(receipt().lane,receipt(value="capture-b").lane);assertNotEquals(receipt().lane,receipt(destination("two")).lane);assertNotEquals(receipt().lane,receipt(kind="notifications",value="1").lane)}
 @Test fun sameEndpointAndThreadOnDifferentNamedEnvironmentNeverMatches(){assertTrue(receipt().matches(destination()));assertFalse(receipt().matches(destination("two")));assertFalse(receipt().matches(destination(token="repaired credential")));assertNotEquals(receipt().id,receipt(destination("two")).id)}
 @Test fun expiredCaptureCannotMarkAReplacementViewSeen(){assertTrue(receipt().valid(600999));assertFalse(receipt().valid(601000));assertFalse(receipt().valid(999));assertFalse(receipt(kind="reply").valid(2000));assertFalse(receipt(value="").valid(2000))}
}
