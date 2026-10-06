package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class RecentTranscriptCacheTest {
    @Test fun backendAndThreadAreBothRequiredForAHit(){
        val cache=RecentTranscriptCache<String>(4,120000)
        cache.put("workstation","same-id","workstation history",10)
        assertNull(cache.get("phone","same-id",20))
        assertNull(cache.get("workstation","other-id",20))
        assertEquals("workstation history",cache.get("workstation","same-id",20))
    }
    @Test fun recentlyOpenedConversationSurvivesEvictionButNotExpiry(){
        val cache=RecentTranscriptCache<String>(2,100)
        cache.put("a","one","one",0);cache.put("a","two","two",10)
        assertEquals("one",cache.get("a","one",20))
        cache.put("a","three","three",30)
        assertNull(cache.get("a","two",40))
        assertNull(cache.get("a","one",101))
        assertEquals("three",cache.get("a","three",101))
    }
    @Test fun explicitLatestAndMemoryReleaseInvalidateSnapshots(){
        val cache=RecentTranscriptCache<String>(4,100)
        cache.put("a","one","one",0);cache.put("b","one","other",0)
        cache.remove("a","one")
        assertNull(cache.get("a","one",1));assertEquals("other",cache.get("b","one",1))
        cache.clear();assertNull(cache.get("b","one",2))
    }
}
