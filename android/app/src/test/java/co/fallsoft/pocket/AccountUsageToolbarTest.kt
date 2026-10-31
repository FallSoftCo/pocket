package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AccountUsageToolbarTest {
    private val source=EnvironmentRequest(EnvironmentIdentity("cpu","CPU"),"https://cpu.invalid","synthetic-private-credential")
    private val fresh=WeeklyUsage(remainingPercent=18.0,resetsAt=500,updatedAt=1000,
        credits=CreditUsage(balance="12345",updatedAt=1000,stale=false))

    @Test fun ownerDoesNotContainCredentialsAndCannotCrossPairingOrEnvironment(){
        val owner=accountUsageOwner(source)
        assertFalse(owner.contains(source.token));assertFalse(owner.contains(source.endpoint))
        assertTrue(accountUsageCacheMatches(owner,source))
        for(other in listOf(source.copy(token="new-pairing"),source.copy(endpoint="https://mirror.invalid"),source.copy(environment=EnvironmentIdentity("gpu","GPU"))))
            assertFalse(accountUsageCacheMatches(owner,other))
    }
    @Test fun legacyUnattributedCacheIsNotEvidenceForAnAccount(){
        assertFalse(accountUsageCacheMatches(null,source));assertFalse(accountUsageCacheMatches("",source))
        assertFalse(accountUsageCacheMatches(accountUsageOwner(source),null))
    }
    @Test fun toolbarAcceptsOnlyTheCapturedSelectedAccountObservation(){
        assertSame(fresh,accountUsageForToolbar(accountUsageOwner(source),source,source,fresh))
        for(active in listOf(null,source.copy(token="different"),source.copy(environment=EnvironmentIdentity("other","Other")))){
            val rejected=accountUsageForToolbar(accountUsageOwner(source),source,active,fresh)
            assertNull(rejected.remainingPercent);assertNull(rejected.resetsAt);assertNull(rejected.credits.balance)
            assertEquals("unavailable",rejected.state)
        }
        assertNull(accountUsageForToolbar(null,source,source,fresh).remainingPercent)
        assertNull(accountUsageForToolbar(accountUsageOwner(source),null,source,fresh).remainingPercent)
    }
    @Test fun visibleAllowanceAndCreditsRemainDistinct(){
        val facts=accountToolbarFacts(fresh,2000,true,Locale.US)
        assertEquals("18%",facts.primary);assertEquals("12.3k cr",facts.credits);assertEquals("",facts.freshness)
        assertEquals(500L,fresh.resetsAt)
    }
    @Test fun exhaustedAllowanceDoesNotBecomeUnknownOrHideExistingCredits(){
        val facts=accountToolbarFacts(fresh.copy(remainingPercent=0.0,continuationState="existing-credits-available"),2000,true,Locale.US)
        assertEquals("0%",facts.primary);assertEquals("12.3k cr",facts.credits)
        assertEquals("0 cr",accountToolbarFacts(fresh.copy(credits=fresh.credits.copy(balance="0")),2000,true,Locale.US).credits)
    }
    @Test fun creditOnlyAndUnlimitedNeverFabricateAnAllowance(){
        val usage=fresh.copy(remainingPercent=null,credits=CreditUsage(unlimited=true,updatedAt=1000,stale=false))
        val facts=accountToolbarFacts(usage,2000,true,Locale.US)
        assertEquals("—",facts.primary);assertEquals("∞ cr",facts.credits);assertEquals("",facts.freshness)
    }
    @Test fun missingLoadingAndUnavailableHaveVisibleHonestLabels(){
        assertEquals("Loading…",accountToolbarFacts(WeeklyUsage(),2000,true).freshness)
        assertEquals("Unavailable",accountToolbarFacts(WeeklyUsage(state="unavailable"),2000,true).freshness)
        assertEquals("—",accountToolbarFacts(WeeklyUsage(),2000,true).primary)
        assertNull(accountToolbarFacts(WeeklyUsage(),2000,true).credits)
    }
    @Test fun disconnectedExpiredAndIndependentlyStaleCreditObservationsSayLastKnown(){
        for(usage in listOf(fresh.copy(stale=true),fresh.copy(resetsAt=1),fresh.copy(updatedAt=1),fresh.copy(credits=fresh.credits.copy(stale=true))))
            assertEquals("Last known",accountToolbarFacts(usage,121002,true).freshness)
        assertEquals("Last known",accountToolbarFacts(fresh,2000,false).freshness)
        assertEquals("Last known",accountToolbarFacts(fresh.copy(credits=fresh.credits.copy(stale=true)),2000,true).freshness)
    }
}
