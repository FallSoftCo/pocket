package co.fallsoft.pocket

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A room's live head and older pages have independent cursors and failure domains.
 * No read can submit, retry, resume or retarget work. The composition owns lifetime.
 */
@Stable
internal class HollywoodRoomFeed(val room:String,val view:String?=null,private val read:suspend (String)->JSONObject) {
    init{require(view==null||view in listOf("conversation","activity","all"))}
    private fun history(direction:String)="rooms/$room/history?$direction&limit=100"+(view?.let{"&view=$it"}.orEmpty())
    var events by mutableStateOf(emptyList<JSONObject>());private set
    var detail by mutableStateOf(JSONObject());private set
    var justAccepted by mutableStateOf(emptyList<JSONObject>());private set
    private var acceptanceRevision=0L
    var attention by mutableStateOf(emptyList<JSONObject>());private set
    var cursor:Long? by mutableStateOf(null);private set
    var older:Long? by mutableStateOf(null);private set
    var loading by mutableStateOf(true);private set
    var loadingOlder by mutableStateOf(false);private set
    var historyError by mutableStateOf("");private set
    var detailError by mutableStateOf("");private set
    var attentionError by mutableStateOf("");private set
    var verifiedAt by mutableLongStateOf(0L);private set
    private data class Gap(val after:Long,val before:Long)
    private var gaps by mutableStateOf(emptyList<Gap>())
    val catchingUp get()=gaps.isNotEmpty()
    private val gapMutex=Mutex()
    private val latestMutex=Mutex()
    private val olderMutex=Mutex()
    private val detailMutex=Mutex()
    private val attentionMutex=Mutex()
    private fun checked(page:JSONObject):List<JSONObject> {
        require(view==null||page.s("view")==view){"Room history projection changed"}
        require(page.s("roomId")==room){"History belongs to another room"}
        val incoming=page.getJSONArray("events").objects()
        require(incoming.all{hollywoodUuid(it.s("id"))&&it.optLong("seq")>0}&&incoming.zipWithNext().all{(a,b)->a.optLong("seq")<b.optLong("seq")}){"Room history order could not be verified"}
        require(page.getLong("nextCursor")>=0){"Room history cursor could not be verified"}
        return incoming
    }
    private fun merge(incoming:List<JSONObject>) {
        if(incoming.isEmpty())return
        val known=events.associateBy{it.s("id")}
        // Canonical events are immutable. Empty/unchanged reads do not rebuild
        // every message or invalidate text layout while the human is typing.
        if(incoming.all{known[it.s("id")]?.toString()==it.toString()})return
        events=(events+incoming).associateBy{it.s("id")}.values.sortedBy{it.optLong("seq")}
    }
    suspend fun refreshHistory(preferTail:Boolean=false)=latestMutex.withLock {
        try {
            val previous=cursor
            val tail=previous==null||preferTail
            val page=read(history(if(tail)"before=9007199254740991" else "after=$previous"))
            val incoming=checked(page);val next=page.getLong("nextCursor")
            require(previous==null||next>=previous){"Room history cursor moved backwards"}
            merge(incoming)
            if(previous==null)older=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")
            if(tail&&previous!=null&&incoming.isNotEmpty()&&incoming.first().optLong("seq")>previous)
                gaps=gaps+Gap(previous,incoming.first().optLong("seq"))
            cursor=next;loading=false;historyError="";verifiedAt=System.currentTimeMillis()
            if(!tail&&incoming.size==100&&next!=previous){
                // An overnight backlog must not delay the newest human answer.
                // Jump to its canonical tail, retaining a separate gap to fill.
                val latest=read(history("before=9007199254740991"))
                val recent=checked(latest);val head=latest.getLong("nextCursor")
                require(head>=next){"Room history head moved backwards"}
                merge(recent)
                if(recent.isNotEmpty()&&recent.first().optLong("seq")>next)gaps=gaps+Gap(next,recent.first().optLong("seq"))
                cursor=head;verifiedAt=System.currentTimeMillis()
            }
        }catch(e:CancellationException){throw e}
        catch(e:Exception){historyError=e.message?:"Messages could not refresh"}
        finally{loading=false}
    }
    suspend fun fillGapPage()=gapMutex.withLock {
        val gap=gaps.firstOrNull()?:return@withLock
        try{
            val page=read(history("after=${gap.after}"))
            val incoming=checked(page);val next=page.getLong("nextCursor")
            require(next>=gap.after){"Missed history cursor moved backwards"}
            merge(incoming)
            val finished=incoming.size<100||incoming.any{it.optLong("seq")>=gap.before}||next>=gap.before
            gaps=if(finished)gaps.filter{it!=gap}else gaps.map{if(it==gap)Gap(next,gap.before)else it}
        }catch(e:CancellationException){throw e}
        catch(e:Exception){historyError=e.message?:"Missed messages could not load"}
    }
    suspend fun loadOlder()=olderMutex.withLock {
        val before=older?:return@withLock
        loadingOlder=true
        try {
            val page=read(history("before=$before"))
            val incoming=checked(page)
            require(incoming.all{it.optLong("seq")<before}){"Earlier history did not move backwards"}
            val next=page.takeUnless{it.isNull("olderCursor")}?.optLong("olderCursor")
            require(next==null||next<before){"Earlier history cursor did not advance"}
            merge(incoming);older=next;historyError=""
            // Never change the live head or its freshness with an older-page read.
        }catch(e:CancellationException){throw e}
        catch(e:Exception){historyError=e.message?:"Earlier messages could not load"}
        finally{loadingOlder=false}
    }
    /** Fresh submit receipts may assert queued delivery, never permissions or execution. */
    fun accepted(receipt:JSONObject){
        val event=receipt.optJSONObject("event")?:return
        if(!receipt.optBoolean("accepted")||event.s("room")!=room||event.s("kind")!="request")return
        val rows=receipt.optJSONArray("deliveries")?.objects().orEmpty()
        if(!rows.all{hollywoodUuid(it.s("id"))&&it.s("event")==event.s("id")&&it.s("state")=="pending"})return
        if(rows.isNotEmpty()){
            acceptanceRevision++
            justAccepted=(justAccepted+rows).associateBy{it.s("id")}.values.toList()
        }
    }
    suspend fun refreshDetail()=detailMutex.withLock{try{
        val before=acceptanceRevision
        val value=read("rooms/$room");require(value.optJSONObject("room")?.s("id")==room){"Room identity changed"}
        if(detail.toString()!=value.toString())detail=value
        // Only a read started after the latest acceptance can supersede it.
        if(before==acceptanceRevision)justAccepted=emptyList()
        detailError=""
    }catch(e:CancellationException){throw e}catch(e:Exception){detailError=e.message?:"Work status could not refresh"}}
    suspend fun refreshAttention()=attentionMutex.withLock{try{
        val value=read("rooms/$room/attention?after=0&limit=100").getJSONArray("attention").objects()
        if(attention.map{it.toString()}!=value.map{it.toString()})attention=value
        attentionError=""
    }catch(e:CancellationException){throw e}catch(e:Exception){attentionError=e.message?:"Questions could not refresh"}}
}
