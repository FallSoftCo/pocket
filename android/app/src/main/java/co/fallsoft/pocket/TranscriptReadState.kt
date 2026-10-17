package co.fallsoft.pocket

import androidx.compose.runtime.*

/** Main-owned read admission. Current content and historical coverage never wait on each other.
 * Older pages and gap repair share one historical lane because they advance related boundaries.
 * Tickets prevent a retired read from releasing a replacement after navigation/reset.
 */
internal class TranscriptReadState {
    enum class Kind { LATEST, OLDER, GAP }
    data class Ticket(val kind:Kind,val serial:Long)
    private var serial=0L
    private var latest by mutableStateOf<Ticket?>(null)
    private var history by mutableStateOf<Ticket?>(null)
    val latestLoading get()=latest!=null
    val historyLoading get()=history!=null
    val olderLoading get()=history?.kind==Kind.OLDER
    val gapLoading get()=history?.kind==Kind.GAP
    val loading get()=latestLoading||historyLoading
    fun begin(kind:Kind):Ticket? {
        if(if(kind==Kind.LATEST)latest!=null else history!=null)return null
        val ticket=Ticket(kind,++serial)
        if(kind==Kind.LATEST)latest=ticket else history=ticket
        return ticket
    }
    fun active(ticket:Ticket)=if(ticket.kind==Kind.LATEST)latest==ticket else history==ticket
    fun finish(ticket:Ticket){if(!active(ticket))return;if(ticket.kind==Kind.LATEST)latest=null else history=null}
    fun reset(){latest=null;history=null;serial++}
}
