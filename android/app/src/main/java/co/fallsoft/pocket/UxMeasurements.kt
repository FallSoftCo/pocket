package co.fallsoft.pocket

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

internal enum class UxJourney { OPEN, SEND, HISTORY_REQUEST }
internal enum class UxMetric {
    OPEN_FIRST_CONTENT_LAYOUT, OPEN_CACHED_FIRST_CONTENT_LAYOUT, OPEN_FRESH_CONTENT_LAYOUT,
    SEND_FEEDBACK_COMMITTED, SEND_ADMITTED_CALLBACK, HISTORY_RESPONSE
}
internal enum class UxEnd { SUPERSEDED, BACKGROUNDED, FAILED, UNKNOWN, CANCELLED, COMPLETE }

/** Recent foreground-only aggregate diagnostics. Accepts enums/monotonic time,
 * never conversation IDs, text, credentials, URLs, input events or device IDs.
 * No timers or Compose state; each milestone is recorded once per journey.
 */
internal open class UxMeasurementStore(private val clock:()->Long) {
    private data class Flight(val kind:UxJourney,val at:Long,val seen:MutableSet<UxMetric> = mutableSetOf())
    private val flights=linkedMapOf<Long,Flight>()
    private val samples=mutableMapOf<UxMetric,java.util.ArrayDeque<Long>>()
    private val counts=mutableMapOf<String,Long>()
    private var serial=0L
    private var foreground=false
    private fun count(key:String){counts[key]=((counts[key]?:0)+1).coerceAtMost(1_000_000)}
    @Synchronized fun foreground(value:Boolean){
        foreground=value
        if(!value){flights.values.forEach{count("${it.kind.name.lowercase()}_backgrounded")};flights.clear()}
    }
    @Synchronized fun active(token:Long)=flights.containsKey(token)
    @Synchronized fun begin(kind:UxJourney):Long {
        if(!foreground)return 0
        if(flights.size>=32){count("capacity_dropped");flights.remove(flights.keys.first())}
        val token=++serial;flights[token]=Flight(kind,clock());count("${kind.name.lowercase()}_started");return token
    }
    @Synchronized fun mark(token:Long,metric:UxMetric){
        val f=flights[token]?:return
        val expected=when(metric){
            UxMetric.OPEN_FIRST_CONTENT_LAYOUT,UxMetric.OPEN_CACHED_FIRST_CONTENT_LAYOUT,UxMetric.OPEN_FRESH_CONTENT_LAYOUT->UxJourney.OPEN
            UxMetric.SEND_FEEDBACK_COMMITTED,UxMetric.SEND_ADMITTED_CALLBACK->UxJourney.SEND
            UxMetric.HISTORY_RESPONSE->UxJourney.HISTORY_REQUEST
        }
        if(!foreground||f.kind!=expected||!f.seen.add(metric))return
        val elapsed=clock()-f.at
        if(elapsed<0){count("invalid_clock");return}
        val values=samples.getOrPut(metric){java.util.ArrayDeque()};if(values.size==128)values.removeFirst();values.addLast(elapsed)
        if(f.kind==UxJourney.SEND&&f.seen.containsAll(setOf(UxMetric.SEND_FEEDBACK_COMMITTED,UxMetric.SEND_ADMITTED_CALLBACK)))end(token,UxEnd.COMPLETE)
    }
    @Synchronized fun contentLayout(token:Long,fresh:Boolean){
        val f=flights[token]?:return
        if(UxMetric.OPEN_FIRST_CONTENT_LAYOUT !in f.seen){mark(token,UxMetric.OPEN_FIRST_CONTENT_LAYOUT);if(!fresh)mark(token,UxMetric.OPEN_CACHED_FIRST_CONTENT_LAYOUT)}
        if(fresh){mark(token,UxMetric.OPEN_FRESH_CONTENT_LAYOUT);end(token,UxEnd.COMPLETE)}
    }
    @Synchronized fun end(token:Long,reason:UxEnd){
        val f=flights.remove(token)?:return;count("${f.kind.name.lowercase()}_${reason.name.lowercase()}")
    }
    @Synchronized fun snapshot():Map<String,Any> {
        val metrics=samples.entries.associate{(metric,values)->
            val sorted=values.sorted();val n=sorted.size
            metric.name.lowercase() to mapOf("count" to n,"minMs" to sorted.first(),"maxMs" to sorted.last(),
                "p50Ms" to sorted[((n*50+99)/100-1).coerceAtLeast(0)],"p95Ms" to sorted[((n*95+99)/100-1).coerceAtLeast(0)])
        }
        return mapOf("schema" to 1,"retention" to "Last 128 samples per enum metric in this process; at most 32 active journeys; foreground only",
            "metrics" to metrics,"counts" to counts.toMap(),"pending" to UxJourney.entries.associate{kind->kind.name.lowercase() to flights.values.count{it.kind==kind}},
            "evidence" to "History response is transport completion, not presentation. Content layout is a visible LazyColumn content observation, not proof pixels were seen. Send feedback is committed UI state; admission callback is a receipt, not task completion. No automatic upload.")
    }
}
internal object UxMeasurements:UxMeasurementStore({System.nanoTime()/1_000_000})

/** Finite presentation observer shared by the real Compose hook and tests.
 * Streaming emissions do not cancel a frame wait. Ownership loss or the fresh
 * milestone ends collection, so later scrolling creates no measurement work.
 */
internal suspend fun observeOpenUxPresentation(changes:Flow<Triple<Boolean,Boolean,Boolean>>,
    active:()->Boolean,visible:()->Boolean,fresh:()->Boolean,focused:()->Boolean,
    frame:suspend()->Unit,observed:(Boolean)->Unit){
    changes.first{(enabled,hasContent,_)->
        if(!enabled)return@first true
        if(!hasContent)return@first false
        frame();val freshBeforeFrame=fresh();frame()
        if(!freshBeforeFrame&&fresh())frame()
        if(!active())return@first true
        if(visible()&&focused())observed(fresh())
        !active()
    }
}
