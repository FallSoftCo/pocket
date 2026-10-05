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
    @Test fun explicitWorkPromotesGenuineOldInteractionAndKeepsActiveTiesStable() {
        val seats=listOf("recent","active-a","old","active-b","hydrated")
        val entries=listOf(SessionRank("hydrated",999_999,false),SessionRank("recent",100,false),SessionRank("old",1,false),SessionRank("active-b",999_000,true),SessionRank("active-a",0,true))
        assertEquals(seats,reconciledSessionOrder(seats,entries,setOf("old"),false))
        val reconciled=listOf("active-a","active-b","old","recent","hydrated")
        assertEquals(reconciled,reconciledSessionOrder(seats,entries,setOf("old"),true))
        assertEquals(reconciled,reconciledSessionOrder(reconciled,entries.map{it.copy(updated=it.updated+1_000_000)},emptySet(),false))
    }
    @Test fun userBatchTiesUseExistingSeatsInsteadOfCallbackArrivalOrTokenTime() {
        val entries=listOf(SessionRank("b",100,false),SessionRank("a",1,false),SessionRank("c",999_999,false))
        assertEquals(listOf("a","b","c"),reconciledSessionOrder(listOf("c","a","b"),entries,setOf("b","a"),true))
    }
    @Test fun reconnectReplayCannotRenewConsumedUserIntentAndMissingSessionsRemainPending() {
        var state=queueSessionPromotion(SessionPromotionState(),"old","item:user-1")
        state=queueSessionPromotion(state,"missing","reply:queued-2")
        state=acknowledgeSessionPromotions(state,setOf("old"))
        assertEquals(listOf("missing"),state.pending)
        assertEquals(state,queueSessionPromotion(state,"old","item:user-1"))
        assertEquals(listOf("missing","old"),queueSessionPromotion(state,"old","item:user-2").pending)
        assertEquals(state,queueSessionPromotion(state,"old","item:"))
    }
    @Test fun onlyExplicitSafeCompleteConnectedBoundaryCanReconcile() {
        assertTrue(sessionReconcileAllowed(true,true,true,true,true))
        assertFalse(sessionReconcileAllowed(false,true,true,true,true)) // Back / idle polling
        assertFalse(sessionReconcileAllowed(true,false,true,true,true)) // partial snapshot
        assertFalse(sessionReconcileAllowed(true,true,false,true,true)) // reconnect
        assertFalse(sessionReconcileAllowed(true,true,true,false,true)) // draining
        assertFalse(sessionReconcileAllowed(true,true,true,true,false)) // finger / scroll
    }
    @Test fun simultaneousLiveSessionsKeepTheirSeatsAcrossUnlimitedTokens() {
        var seats = listOf("a", "b", "c")
        for (tick in 1..100) {
            val entries = listOf(SessionRank("a", tick * 1_000L, true), SessionRank("b", tick * 1_000L + 700, true), SessionRank("c", tick * 1_000L + 900, true))
            seats = stableSessionOrder(seats, entries, tick * 1_000L + 900)
            assertEquals(listOf("a", "b", "c"), seats)
        }
    }
    @Test fun completionAndGraceExpiryNeverMoveExistingCards() {
        val entries=listOf(SessionRank("a", 0, true),SessionRank("b", 100_000, false),SessionRank("c", 0, false))
        assertEquals(listOf("a","b","c"),stableSessionOrder(listOf("a","b","c"),entries,120_000))
        assertEquals(listOf("b","a","c"),stableSessionOrder(listOf("b","a","c"),entries,120_000))
        assertEquals(listOf("b","a","c"),stableSessionOrder(listOf("b","a","c"),entries,131_000))
    }
    @Test fun drainingReconnectAndBackgroundTimestampsKeepSeats() {
        val seats=listOf("old","working","recent")
        val snapshots=listOf(
            listOf(SessionRank("recent",30_000,true),SessionRank("working",20_000,true),SessionRank("old",1,false)),
            listOf(SessionRank("working",100_000,false),SessionRank("old",100_000,false),SessionRank("recent",100_000,false)),
            listOf(SessionRank("recent",200_000,true),SessionRank("old",300_000,false),SessionRank("working",400_000,false))
        )
        for(entries in snapshots)assertEquals(seats,stableSessionOrder(seats,entries,500_000))
        assertEquals(seats,stableSessionOrder(seats,emptyList(),600_000))
        assertEquals(seats,stableSessionOrder(seats,listOf(SessionRank("recent",700_000,true)),700_000))
    }
    @Test fun newDiscoveriesJoinByRecencyWithoutReorderingExistingOrDuplicateSeats() {
        val entries=listOf(SessionRank("b",1,false),SessionRank("new-old",10,false),SessionRank("a",999,true),SessionRank("new",100,true),SessionRank("new",100,true))
        assertEquals(listOf("new","new-old","b","a"),stableSessionOrder(listOf("b","a","a"),entries,100))
    }
    @Test fun contentUpdatesDoNotChangeMembershipOrOrderBeforeSafeDelivery() {
        data class Card(val id:String,val text:String)
        val before=listOf(Card("selected","old"),Card("other","old"))
        val latest=listOf(Card("new","new"),Card("other","new status"),Card("selected","new preview"))
        assertEquals(listOf(Card("selected","new preview"),Card("other","new status")),sessionContentInPlace(before,latest){it.id})
        assertEquals(before,sessionContentInPlace(before,emptyList()){it.id})
        val pacing=SessionListPacing()
        assertFalse(pacing.orderDue(1_000,0,true))
        assertFalse(pacing.interactionSettled(1_749,false))
        assertTrue(pacing.interactionSettled(1_750,false))
    }
    @Test fun partialReconnectRetainsKnownWorkWhileCompleteArchiveResultRemovesIt() {
        data class Card(val id:String,val text:String)
        val previous=listOf(Card("selected","running"),Card("other","old"))
        val fetched=listOf(Card("other","completed"))
        assertEquals(listOf(Card("other","completed"),Card("selected","running")),mergeSessionSnapshot(previous,fetched,true){it.id})
        assertEquals(previous,mergeSessionSnapshot(previous,emptyList(),true){it.id})
        assertEquals(fetched,mergeSessionSnapshot(previous,fetched,false){it.id})
        assertEquals(emptyList<Card>(),mergeSessionSnapshot(previous,emptyList(),false){it.id})
    }
}
