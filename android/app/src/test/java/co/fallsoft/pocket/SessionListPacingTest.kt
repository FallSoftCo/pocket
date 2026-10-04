package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class SessionListPacingTest {
    @Test fun busyTrafficSlowsPositionMoreThanContent() {
        val calm = SessionListPacing()
        val busy = SessionListPacing().apply { record(0, 40) }
        assertTrue(busy.contentInterval(16) > calm.contentInterval(0))
        assertTrue(busy.orderInterval(16) > calm.orderInterval(0))
        assertTrue(busy.contentInterval(16) <= 1_200)
        assertTrue(busy.orderInterval(16) <= 12_000)
    }
    @Test fun continuousTrafficCannotStarveUpdates() {
        val p = SessionListPacing()
        p.contentDelivered(0); p.orderDelivered(0)
        var content = 0; var order = 0
        for (now in 100L..30_000L step 100) {
            p.record(now, 10)
            if (p.contentDue(now, 30)) { p.contentDelivered(now); content++ }
            if (p.orderDue(now, 30, false)) { p.orderDelivered(now); order++ }
        }
        assertTrue(content >= 25)
        assertTrue(order >= 2)
    }
    @Test fun fingerAndScrollHoldOrderThenRelease() {
        val p = SessionListPacing()
        assertFalse(p.orderDue(10_000, 0, true))
        assertFalse(p.orderDue(10_500, 0, false))
        assertTrue(p.orderDue(10_750, 0, false))
    }
    @Test fun trafficPressureRecoversAfterBurst() {
        val p = SessionListPacing()
        p.record(0, 40)
        val busy = p.orderInterval(0)
        p.record(2_001, 0)
        assertTrue(p.orderInterval(0) < busy)
        assertEquals(2_000, p.orderInterval(0))
    }
}

class SessionListTiesTest {
    @Test fun simultaneousLiveSessionsKeepTheirSeatsAcrossUnlimitedTokens() {
        var seats = listOf("a", "b", "c")
        for (tick in 1..100) {
            val entries = listOf(SessionRank("a", tick * 1_000L, true), SessionRank("b", tick * 1_000L + 700, true), SessionRank("c", tick * 1_000L + 900, true))
            seats = stableSessionOrder(seats, entries, tick * 1_000L + 900)
            assertEquals(listOf("a", "b", "c"), seats)
        }
    }
    @Test fun newLiveSessionPreservesOthersAndCompletionHasGracePeriod() {
        val entries=listOf(SessionRank("a", 0, true),SessionRank("b", 100_000, false),SessionRank("c", 0, false))
        assertEquals(listOf("a","b","c"),stableSessionOrder(listOf("a","b","c"),entries,120_000))
        assertEquals(listOf("b","a","c"),stableSessionOrder(listOf("b","a","c"),entries,120_000))
        assertEquals(listOf("a","b","c"),stableSessionOrder(listOf("b","a","c"),entries,131_000))
    }
}
