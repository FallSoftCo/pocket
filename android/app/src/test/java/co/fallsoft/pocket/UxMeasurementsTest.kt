package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
class UxMeasurementsTest {
    @Suppress("UNCHECKED_CAST") private fun metrics(store:UxMeasurementStore)=store.snapshot()["metrics"] as Map<String,Map<String,Number>>
    @Test fun cachedContentResponseAndFreshLayoutRemainSeparate(){
        var now=0L;val store=UxMeasurementStore{now};store.foreground(true)
        val open=store.begin(UxJourney.OPEN);val read=store.begin(UxJourney.HISTORY_REQUEST)
        now=12;store.contentLayout(open,false);now=90;store.mark(read,UxMetric.HISTORY_RESPONSE);store.end(read,UxEnd.COMPLETE)
        assertEquals(12L,metrics(store).getValue("open_cached_first_content_layout").getValue("p95Ms"))
        assertFalse(metrics(store).containsKey("open_fresh_content_layout"))
        now=135;store.contentLayout(open,true);store.contentLayout(open,true)
        assertEquals(135L,metrics(store).getValue("open_fresh_content_layout").getValue("p95Ms"))
        assertEquals(1,metrics(store).getValue("open_first_content_layout").getValue("count"))
    }
    @Test fun supersededCancelledAndBackgroundedJourneysAreNotSuccesses(){
        val store=UxMeasurementStore{10};store.foreground(true)
        val old=store.begin(UxJourney.OPEN);store.end(old,UxEnd.SUPERSEDED)
        val read=store.begin(UxJourney.HISTORY_REQUEST);store.end(read,UxEnd.CANCELLED);store.mark(read,UxMetric.HISTORY_RESPONSE)
        val current=store.begin(UxJourney.OPEN);store.foreground(false);store.contentLayout(old,true);store.contentLayout(current,true)
        assertEquals(0L,store.begin(UxJourney.SEND));assertTrue(metrics(store).isEmpty())
        val counts=store.snapshot()["counts"] as Map<*,*>;assertEquals(1L,counts["open_superseded"]);assertEquals(1L,counts["open_backgrounded"]);assertEquals(1L,counts["history_request_cancelled"])
    }
    @Test fun retentionBoundsSampleCountWithoutClippingSlowSuccessfulJourneys(){
        var now=0L;val store=UxMeasurementStore{now};store.foreground(true)
        repeat(150){n->val open=store.begin(UxJourney.OPEN);now+=if(n==149)180_000 else 10;store.contentLayout(open,true)}
        assertEquals(128,metrics(store).getValue("open_fresh_content_layout").getValue("count"))
        assertEquals(180_000L,metrics(store).getValue("open_fresh_content_layout").getValue("maxMs"))
        assertEquals(setOf("schema","retention","metrics","counts","pending","evidence"),store.snapshot().keys)
    }

    @Test fun continuousUpdatesDoNotStarvePresentationAndCollectionStopsAfterFreshContent()=runBlocking{
        var now=0L;val store=UxMeasurementStore{now};store.foreground(true);val open=store.begin(UxJourney.OPEN)
        val changes=MutableSharedFlow<Triple<Boolean,Boolean,Boolean>>(replay=1,extraBufferCapacity=16)
        changes.tryEmit(Triple(true,true,true));var frames=0;var observations=0
        observeOpenUxPresentation(changes,active={store.active(open)},visible={true},fresh={true},focused={true},
            frame={frames++;now+=16;changes.tryEmit(Triple(true,true,true))},
            observed={fresh->observations++;store.contentLayout(open,fresh)})
        assertEquals(2,frames);assertEquals(1,observations);assertEquals(0,changes.subscriptionCount.value)
        repeat(12){changes.tryEmit(Triple(false,true,true))};assertEquals(2,frames)
        assertEquals(32L,metrics(store).getValue("open_fresh_content_layout").getValue("p95Ms"))
    }
    @Test fun ownershipLostBetweenFramesCannotPromoteCachedLayoutToFresh()=runBlocking{
        val store=UxMeasurementStore{10};store.foreground(true);val open=store.begin(UxJourney.OPEN)
        val changes=MutableSharedFlow<Triple<Boolean,Boolean,Boolean>>(replay=1);changes.tryEmit(Triple(true,true,true))
        var frames=0
        observeOpenUxPresentation(changes,active={store.active(open)},visible={true},fresh={true},focused={true},
            frame={frames++;if(frames==1)store.end(open,UxEnd.SUPERSEDED)},observed={store.contentLayout(open,it)})
        assertTrue(metrics(store).isEmpty());assertEquals(0,changes.subscriptionCount.value)
    }
}
