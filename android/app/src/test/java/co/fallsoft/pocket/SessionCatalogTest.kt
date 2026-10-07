package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class SessionCatalogTest {
    private fun task(id:String,parent:String?=null,recency:Long=100)=Task(id,id,"/project","idle",9_999_999,false,recencyAt=recency,parentThreadId=parent,isChild=parent!=null,canAcceptDirectInput=parent==null)
    @Test fun nestedAndCompletedAgentsBelongToActualRootWithoutInventingParentFromNames(){
        val tasks=listOf(task("root"),task("child","root"),task("grandchild","child"),task("another-user"),task("unknown-parent","absent"))
        val groups=sessionGroups(tasks)
        assertEquals(listOf("root","another-user"),groups.map{it.task.id})
        assertEquals(listOf("child","grandchild"),groups.first().children.map{it.id})
        assertEquals(listOf("unknown-parent"),ungroupedChildren(tasks).map{it.id})
    }
    @Test fun missingParentsCyclesAndSearchHistoryNeverDropChildResults(){
        val tasks=listOf(task("a","b"),task("b","a"),task("orphan","missing"))
        assertTrue(sessionGroups(tasks).isEmpty())
        assertEquals(tasks,ungroupedChildren(tasks))
    }
    @Test fun mixedDormantRecentAndChildInventoryKeepsUserWorkPrimary(){
        val recent=(1..30).map{task("recent-$it",recency=2000+it.toLong())}
        val old=(1..262).map{task("old-$it",recency=it.toLong())}
        val children=(1..30).map{task("child-$it","recent-$it",recency=5000)}
        val groups=sessionGroups((old+recent+children).sortedByDescending{taskWorkTime(it)})
        assertEquals(292,groups.size)
        assertEquals(recent.reversed().map{it.id},groups.take(30).map{it.task.id})
        assertEquals(30,groups.sumOf{it.children.size})
        assertEquals(100L,taskWorkTime(task("metadata-only",recency=100)))
    }
    @Test fun oldDiscoveryAndLifecycleNeverPassRecentSeatsDuringBrowsing(){
        val now=1_800_000_000_000L
        val seats=listOf("recent","last-week")
        val entries=listOf(SessionRank("recent",now-1000,false),SessionRank("last-week",now-5000,false),SessionRank("dormant",now-90*86400000L,false))
        assertEquals(listOf("recent","last-week","dormant"),liveSessionOrder(seats,entries,now))
        assertEquals(listOf("recent","last-week","dormant"),liveSessionOrder(listOf("recent","last-week","dormant"),entries.map{it.copy(active=!it.active)},now+180000))
    }
}
