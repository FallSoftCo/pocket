package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class WeeklyUsageTest{
    @Test fun allowanceLabelsDistinguishUnknownExhaustedAndAlmostExhausted(){
        assertEquals("18% left",WeeklyUsage(remainingPercent=18.0).remainingLabel(Locale.US))
        assertEquals("0% left",WeeklyUsage(remainingPercent=0.0).remainingLabel(Locale.US))
        assertEquals("<0.1% left",WeeklyUsage(remainingPercent=0.01).remainingLabel(Locale.US))
        assertEquals("Loading…",WeeklyUsage().remainingLabel(Locale.US))
        assertEquals("Unavailable",WeeklyUsage(state="unavailable").remainingLabel(Locale.US))
    }
    @Test fun disconnectedOldAndExpiredAllowancesAreMarkedLastKnown(){
        val usage=WeeklyUsage(remainingPercent=18.0,updatedAt=1000,resetsAt=1000)
        assertFalse(usage.isStale(2000,true))
        assertTrue(usage.isStale(2000,false))
        assertTrue(usage.isStale(121001,true))
        assertTrue(usage.copy(updatedAt=1000000).isStale(1000000,true))
        assertTrue(usage.copy(stale=true).isStale(2000,true))
    }
}
