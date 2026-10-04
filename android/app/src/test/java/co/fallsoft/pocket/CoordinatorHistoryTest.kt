package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class CoordinatorHistoryTest {
    @Test fun lateHistorySnapshotCannotDeleteANewlyCompletedTextTurn(){
        val old=Pair("Earlier request","Earlier answer")
        val completed=Pair("New request","New answer")
        val merged=mergeCoordinatorHistory(mapOf("old" to old),mapOf("old" to old),linkedMapOf("old" to old,"new" to completed),false)
        assertEquals(listOf("old","new"),merged.keys.toList());assertEquals(completed,merged["new"])
    }
    @Test fun freshServerHistoryReplacesAnUnchangedCachedPartialResponse(){
        val partial=Pair("Request","");val final=Pair("Request","Finished")
        val merged=mergeCoordinatorHistory(mapOf("turn" to final),mapOf("turn" to partial),mapOf("turn" to partial),false)
        assertEquals(final,merged["turn"])
    }
    @Test fun olderPagesPrependAndRepeatedIdenticalMessagesRemainDistinctById(){
        val same=Pair("Check the build","Build passed")
        val merged=mergeCoordinatorHistory(linkedMapOf("first" to same,"middle" to same),mapOf("middle" to same,"last" to same),linkedMapOf("middle" to same,"last" to same),true)
        assertEquals(listOf("first","middle","last"),merged.keys.toList())
    }
}
