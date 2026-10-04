package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class MissionBoardTest {
    private fun item(id: String, at: Long = 1_000, working: Boolean = true, kind: String = "command", text: String = "Tests passed") =
        MissionItem(id, "Conversation $id", "project", text, kind, working, at)

    @Test fun parallelActionsDoNotCrowdOutOtherConversations() {
        val result = missionItems(List(20) { item("a", at = it.toLong()) } + item("b"), emptyList(), 1_000)
        assertEquals(2, result.size)
        assertEquals(20, result.first { it.threadId == "a" }.actions)
    }
    @Test fun thinkingCannotReplaceUsefulWorkAndNeverAppearsAsFallback() {
        val result = missionItems(listOf(item("a"), item("a", at = 2_000, kind = "thinking", text = "Thinking…")),
            listOf(item("b", kind = "thinking", text = "Thinking…")), 2_000)
        assertEquals("Tests passed", result.first { it.threadId == "a" }.text)
        assertEquals("", result.first { it.threadId == "b" }.text)
        assertFalse(usefulMissionStatus("message", "Thinking..."))
        assertTrue(usefulMissionStatus("message", "We are thinking about the launch."))
        assertTrue(usefulMissionStatus("thinking", "Thinking · Comparing layouts"))
    }
    @Test fun latestWorkReplacesOldStatusAndExpiredCompletionsLeave() {
        val result = missionItems(listOf(item("a", at = 1), item("a", at = 2, text = "Built APK"),
            item("expired", at = 0, working = false)), emptyList(), 100_000)
        assertEquals(1, result.size)
        assertEquals("Built APK", result.single().text)
    }
    @Test fun survivingSentencePositionsRemainSteadyUnderRapidChanges() {
        val items=listOf(item("a"),item("b"),item("c"))
        val original=missionField(emptyList(),items,700f,600f)
        assertEquals(3,original.size)
        val updated=missionField(original,listOf(item("c",text="A much longer new thought appears here"),item("d"),item("b")),700f,600f)
        for(id in listOf("b","c"))assertEquals(original.first{it.threadId==id},updated.first{it.threadId==id})
        assertTrue(updated.any{it.threadId=="d"})
    }
    @Test fun freeformTextBoundsNeverOverlapOrLeaveFoldedUnfoldedOrLargeTypeViewport() {
        for(width in listOf(280f,370f,640f,900f))for(height in listOf(300f,620f,800f))for(scale in listOf(1f,1.5f,2f)) {
            val placements=missionField(emptyList(),(1..100).map{item("$it")},width,height,scale)
            assertTrue(placements.isNotEmpty())
            for(p in placements){assertTrue(p.width>=48f);assertTrue(p.height>=48f);assertTrue(p.x>=0);assertTrue(p.y>=0);assertTrue(p.right<=width+.01f);assertTrue(p.bottom<=height+.01f)}
            for(i in placements.indices)for(j in i+1 until placements.size)assertFalse(missionOverlap(placements[i],placements[j]))
            assertEquals(placements.size,placements.map{it.threadId}.distinct().size)
        }
        assertTrue(missionField(emptyList(),listOf(item("a")),300f,40f).isEmpty())
    }
    @Test fun foldAndFontScaleChangesRepackWithoutClipping() {
        val items=(1..20).map{item("$it")}
        val unfolded=missionField(emptyList(),items,900f,700f)
        val folded=missionField(unfolded,items,280f,600f,2f)
        assertTrue(folded.isNotEmpty())
        assertTrue(folded.all{it.right<=280f&&it.bottom<=600f&&it.height>=190f})
    }
}
