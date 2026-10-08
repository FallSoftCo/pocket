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
        assertTrue(busy.orderInterval(16) <= 1_200)
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
        assertTrue(order >= 25)
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
        assertEquals(750, p.orderInterval(0))
    }
}

class SessionListTiesTest {
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

class LiveSessionPriorityTest {
    private val now=1_800_000_000_000L
    @Test fun reactivatedWorkPromotesWithoutExplicitNavigation() {
        val entries=listOf(SessionRank("stale",now-180_000,true,now-180_000),SessionRank("recent",now-60_000,false,now-60_000),SessionRank("old-reactivated",1,true,now))
        assertEquals(listOf("old-reactivated","recent","stale"),liveSessionOrder(listOf("stale","recent","old-reactivated"),entries,now))
        assertEquals(listOf("old-reactivated","recent","stale"),liveSessionOrder(listOf("stale","recent","old-reactivated"),entries,now,true))
    }
    @Test fun concurrentLivePeersStayTiedAcrossTokenArrivalOrder() {
        var order=listOf("a","b","quiet")
        for(tick in 1..100){
            val at=now+tick*1000
            order=liveSessionOrder(order,listOf(SessionRank("a",1,true,at-700),SessionRank("b",1,true,at),SessionRank("quiet",1,true,now-180_000)),at)
            assertEquals(listOf("a","b","quiet"),order)
        }
    }
    @Test fun aFreshLeaseExpiresRatherThanOccupyingFrontForever() {
        val seats=listOf("finished","running")
        val entries=listOf(SessionRank("finished",1,false,now-100_000),SessionRank("running",1,true,now-1_000))
        assertEquals(listOf("running","finished"),liveSessionOrder(seats,entries,now))
        assertEquals(listOf("running","finished"),liveSessionOrder(seats,entries,now,true))
    }
    @Test fun hydrationTimestampCannotOutrankRealSecondsOldWork() {
        val entries=listOf(SessionRank("hydrated",now,false),SessionRank("live",1,true,now-2_000))
        assertEquals(listOf("hydrated","live"),liveSessionOrder(listOf("hydrated","live"),entries,now,true))
    }
    @Test fun acceptedIntentAndNewDiscoveryDoNotWaitForFirstResponse() {
        val entries=listOf(SessionRank("quiet",1,true,now-180_000),SessionRank("new",now,true,now),SessionRank("reactivated",1,false,now))
        assertEquals(listOf("new","reactivated","quiet"),liveSessionOrder(listOf("quiet","reactivated"),entries,now))
    }
    @Test fun pollCannotUndoNewLiveStatusPreviewOrRecency() {
        val old=Task("a","A","/project","idle",1,false,preview="old",activityAt=100)
        val live=old.copy(status="active",preview="Running command",activityAt=200)
        val poll=old.copy(title="Renamed",activityAt=150)
        val merged=mergeLiveTask(live,poll,old)
        assertEquals("active",merged.status);assertEquals("Running command",merged.preview);assertEquals(200L,merged.activityAt);assertEquals("Renamed",merged.title)
        assertEquals("idle",mergeLiveTask(live,poll,live).status)
        assertEquals(200L,mergeLiveTask(live,poll,live).activityAt)
    }
    @Test fun sessionArrivingDuringCompletePollCannotDisappear() {
        val old=Task("old","Old","/project","idle",1,false)
        val created=Task("new","New","/project","pending",2,false,activityAt=now)
        assertEquals(listOf(old,created),mergeLiveTaskSnapshot(listOf(old,created),listOf(old),mapOf("old" to old),false))
        assertEquals(listOf(old),mergeLiveTaskSnapshot(listOf(old,created),listOf(old),mapOf("old" to old,"new" to created),false))
    }
    @Test fun ageAndColdRankUseWorkTimeInsteadOfLaterMetadata() {
        assertEquals(1_000L,sessionActivityTime(999_000,1_000))
        assertEquals(999_000L,sessionActivityTime(999,0))
        val entries=listOf(SessionRank("metadata-only",now,false,now-900_000),SessionRank("work",1,false,now-300_000))
        assertEquals(listOf("work","metadata-only"),liveSessionOrder(listOf("metadata-only","work"),entries,now,true))
    }
    @Test fun busyOrderingIsBoundedAndNeverMovesUnderFinger() {
        val pacing=SessionListPacing().apply{record(0,1000);orderDelivered(0)}
        assertTrue(pacing.orderDue(1_200,100,false))
        assertFalse(pacing.orderDue(1_300,100,true))
        assertFalse(pacing.orderDue(2_049,100,false))
        assertTrue(pacing.orderDue(2_050,100,false))
    }
}
