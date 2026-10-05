package co.fallsoft.pocket

import kotlin.math.ln

/** Separate live content delivery from physical list order; never debounce indefinitely. */
class SessionListPacing {
    private var lastContent = Long.MIN_VALUE
    private var lastOrder = Long.MIN_VALUE
    private var heldUntil = 0L
    private val changes = ArrayDeque<Pair<Long, Int>>()

    fun record(now: Long, changedSessions: Int) {
        if (changedSessions > 0) changes.addLast(now to changedSessions)
        while (changes.isNotEmpty() && now - changes.first().first > 2_000) changes.removeFirst()
    }
    private fun pressure(active: Int): Double = ln(1.0 + active + changes.sumOf { it.second } / 2.0)
    fun contentInterval(active: Int): Long = (200 + pressure(active) * 200).toLong().coerceAtMost(1_200)
    fun orderInterval(active: Int): Long = (2_000 + pressure(active) * 2_200).toLong().coerceAtMost(12_000)
    fun contentDue(now: Long, active: Int): Boolean = lastContent == Long.MIN_VALUE || now - lastContent >= contentInterval(active)
    fun contentDelivered(now: Long) { lastContent = now }
    fun orderDue(now: Long, active: Int, interacting: Boolean): Boolean {
        if (interacting) heldUntil = now + 750
        return interactionSettled(now,interacting) && (lastOrder == Long.MIN_VALUE || now - lastOrder >= orderInterval(active))
    }
    fun interactionSettled(now: Long, interacting: Boolean): Boolean = !interacting && now >= heldUntil
    fun orderDelivered(now: Long) { lastOrder = now }
}

data class SessionRank(val id: String, val updated: Long, val active: Boolean)

data class SessionPromotionState(val pending: List<String> = emptyList(), val seen: List<String> = emptyList())

/** Only origin-identified user work creates ordering intent; replay cannot renew it. */
fun queueSessionPromotion(state: SessionPromotionState, id: String, eventId: String): SessionPromotionState {
    if(id.isBlank()||eventId.isBlank()||eventId.endsWith(':')||eventId in state.seen)return state
    return SessionPromotionState((state.pending+id).distinct(),(state.seen+eventId).takeLast(512))
}

fun acknowledgeSessionPromotions(state: SessionPromotionState, available: Set<String>) = state.copy(pending=state.pending.filter { it !in available })

fun sessionReconcileAllowed(requested: Boolean, complete: Boolean, connected: Boolean, codexOnline: Boolean, settled: Boolean) = requested&&complete&&connected&&codexOnline&&settled

/** Explicit Work intent reconciles active work and genuine interactions as stable ties.
 * Merely returning, polling, reconnecting or receiving tokens never reconciles seats.
 */
fun reconciledSessionOrder(previous: List<String>, entries: List<SessionRank>, promotions: Set<String>, explicitBoundary: Boolean): List<String> {
    val stable=stableSessionOrder(previous,entries,0)
    if(!explicitBoundary)return stable
    val active=entries.filter { it.active }.map { it.id }.toSet()
    val available=entries.map { it.id }.toSet()
    val recent=promotions.intersect(available)-active
    return stable.filter { it in active }+stable.filter { it in recent }+stable.filter { it !in active && it !in recent }
}

/** Refresh visible card content without adding/removing cards before order delivery. */
fun <T> sessionContentInPlace(displayed: List<T>, latest: List<T>, id: (T) -> String): List<T> {
    val byId=latest.associateBy(id)
    return displayed.map { byId[id(it)] ?: it }
}

/** A timed-out discovery is partial; only a complete list can remove known sessions. */
fun <T> mergeSessionSnapshot(previous: List<T>, latest: List<T>, partial: Boolean, id: (T) -> String): List<T> {
    if(!partial)return latest
    val fetched=latest.map(id).toSet()
    return latest+previous.filter { id(it) !in fetched }
}

/** Activity updates content, never existing seats. New discoveries join in recency order.
 * Keep absent seats as tombstones: a partial reconnect snapshot must not reset their order.
 */
@Suppress("UNUSED_PARAMETER")
fun stableSessionOrder(previous: List<String>, entries: List<SessionRank>, now: Long): List<String> {
    val seats = previous.distinct()
    val known = seats.toSet()
    val added = entries.distinctBy { it.id }.filter { it.id !in known }
        .sortedWith(compareByDescending<SessionRank> { it.updated }.thenBy { it.id }).map { it.id }
    return added + seats
}
