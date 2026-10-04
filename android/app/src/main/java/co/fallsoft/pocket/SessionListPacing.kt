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
        return !interacting && now >= heldUntil && (lastOrder == Long.MIN_VALUE || now - lastOrder >= orderInterval(active))
    }
    fun orderDelivered(now: Long) { lastOrder = now }
}

data class SessionRank(val id: String, val updated: Long, val active: Boolean)

/** Live conversations share first place. Their token timestamps cannot shuffle their seats. */
fun stableSessionOrder(previous: List<String>, entries: List<SessionRank>, now: Long): List<String> {
    val seats = previous.withIndex().associate { it.value to it.index }
    fun hot(e: SessionRank) = e.active || now - e.updated <= 30_000
    return entries.sortedWith(Comparator { a, b ->
        val aHot = hot(a); val bHot = hot(b)
        when {
            aHot && bHot -> (seats[a.id] ?: Int.MAX_VALUE).compareTo(seats[b.id] ?: Int.MAX_VALUE)
            aHot -> -1
            bHot -> 1
            else -> b.updated.compareTo(a.updated).takeIf { it != 0 }
                ?: (seats[a.id] ?: Int.MAX_VALUE).compareTo(seats[b.id] ?: Int.MAX_VALUE)
        }
    }).map { it.id }
}
