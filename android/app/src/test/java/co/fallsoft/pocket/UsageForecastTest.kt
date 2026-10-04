package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class UsageForecastTest {
    private val now=3600000L
    private val usage=WeeklyUsage(remainingPercent=20.0,resetsAt=86400,updatedAt=now)
    private val samples=listOf(UsageSample(now-1800000,30.0,86400),UsageSample(now,20.0,86400))
    @Test fun predictsDepletionFromObservedRate(){assertEquals(now+3600000,UsageForecast.deadline(samples,usage,now,true))}
    @Test fun suppressesStaleSparseAndResetCrossingEstimates(){
        assertNull(UsageForecast.deadline(samples,usage,now,false))
        assertNull(UsageForecast.deadline(samples.takeLast(1),usage,now,true))
        assertNull(UsageForecast.deadline(samples,usage.copy(resetsAt=7200),now,true))
        assertNull(UsageForecast.deadline(samples,usage.copy(stale=true),now,true))
    }
    @Test fun resetAndAllowanceIncreaseStartNewHistory(){
        assertEquals(1,UsageForecast.record(samples,usage.copy(updatedAt=now+300000,resetsAt=90000)).size)
        assertEquals(1,UsageForecast.record(samples,usage.copy(updatedAt=now+300000,remainingPercent=50.0)).size)
    }
    @Test fun duplicateAndStaleObservationsDoNotExtendHistory(){
        assertEquals(samples,UsageForecast.record(samples,usage))
        assertEquals(samples,UsageForecast.record(samples,usage.copy(updatedAt=now+300000,stale=true)))
    }
}
