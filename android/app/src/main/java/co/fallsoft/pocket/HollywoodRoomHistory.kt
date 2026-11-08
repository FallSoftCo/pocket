package co.fallsoft.pocket

import org.json.JSONObject
import java.util.WeakHashMap

internal data class HollywoodHistorySnapshot(
    val events:List<JSONObject>,val cursor:Long?,val older:Long?,val gaps:List<Pair<Long,Long>>,val verifiedAt:Long,val writer:Any
)

/** Bounded canonical content cache, separate from gateways and their live permissions.
 * A route never owns these events. Details, attention and outgoing receipts are not
 * restored as current state from this cache. Event-size accounting is reused for
 * immutable objects, avoiding repeated full-history JSON serialization on refresh.
 */
internal object HollywoodRoomHistory {
    private data class Entry(val snapshot:HollywoodHistorySnapshot,val bytes:Long)
    private val rows=LinkedHashMap<String,Entry>(16,0.75f,true)
    private val sizes=WeakHashMap<JSONObject,Int>()
    private var bytes=0L
    private const val LIMIT=8L*1024*1024
    @Synchronized fun get(key:String)=rows[key]?.snapshot
    @Synchronized fun migrate(from:String,to:String){if(from!=to)rows[from]?.snapshot?.let{put(to,it)}}
    @Synchronized fun put(key:String,incoming:HollywoodHistorySnapshot){
        val previous=rows[key]?.snapshot
        val snapshot=if(previous==null||previous.writer===incoming.writer&&previous.events===incoming.events&&
            (incoming.cursor?:0)>=(previous.cursor?:0))incoming else {
            // A cancelled old route may finish after its replacement. Merge older
            // pages, but never rewind the live cursor or resurrect closed gaps.
            val head=if((incoming.cursor?:0)>(previous.cursor?:0)||
                incoming.cursor==previous.cursor&&incoming.verifiedAt>=previous.verifiedAt)incoming else previous
            val events=(incoming.events+previous.events).associateBy{it.optString("id")}.values.sortedBy{it.optLong("seq")}
            head.copy(events=events,older=if(previous.older==null||incoming.older==null)null else minOf(previous.older,incoming.older),writer=incoming.writer)
        }
        val size=snapshot.events.sumOf{event->sizes.getOrPut(event){event.toString().toByteArray(Charsets.UTF_8).size}.toLong()}
        rows.remove(key)?.let{bytes-=it.bytes}
        if(size>LIMIT)return
        rows[key]=Entry(snapshot,size);bytes+=size
        while(bytes>LIMIT||rows.size>32){val first=rows.entries.iterator();val old=first.next();bytes-=old.value.bytes;first.remove()}
    }
}
