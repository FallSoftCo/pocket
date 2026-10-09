package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class EnvironmentIdentityTest {
 @Test fun unknownDeliveryCannotBeReplayed(){assertFalse(automaticReplyEligible("unknown"));assertFalse(automaticReplyEligible("cancelled"));assertTrue(automaticReplyEligible("queued"))}
 @Test fun remoteNeverOwnsPhoneStorage(){assertFalse(isPhoneEnvironment("https://ozzz.example"));assertTrue(isPhoneEnvironment("http://127.0.0.1:18880"));assertFalse(isPhoneEnvironment("http://127.0.0.1:18881"))}
 @Test fun legacyKeysStayUnchanged(){assertEquals("draft:a",EnvironmentIdentity("workstation","Workstation").key("draft:a"));assertEquals("local:draft:a",EnvironmentIdentity("phone","This phone",true).key("draft:a"))}
 @Test fun identicalThreadsAndDraftsAreSeparate(){val a=EnvironmentIdentity("workstation","Workstation");val b=EnvironmentIdentity("ozzz","OZZZ");assertNotEquals(a.session("same"),b.session("same"));assertNotEquals(a.key("draft:same"),b.key("draft:same"))}
 @Test fun capturedRequestsDoNotFollowSelection(){val a=EnvironmentRequest(EnvironmentIdentity("workstation","Workstation"),"https://a.example","synthetic-a");val b=EnvironmentRequest(EnvironmentIdentity("ozzz","OZZZ"),"https://b.example","synthetic-b");assertFalse(a.matches(b));assertFalse(a.toString().contains("synthetic-a"))}
 @Test fun renamedEnvironmentPreservesScope(){assertEquals(EnvironmentIdentity("ozzz","Old").key("receipt:x"),EnvironmentIdentity("ozzz","New").key("receipt:x"))}
}
