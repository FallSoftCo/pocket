package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRoutingNativeTest {
    @Test fun lobbyDefaultNeverInheritsWorkerReplyAcrossRooms(){
        val j=SessionRoomJourney()
        try{
            j.start();j.click(j.lobby);j.keyboard();j.type("Retained Global draft")
            j.click("team-reply:203");j.type("Explicit Amber reply");j.click("team-send")
            j.await{j.sends().size==1&&j.tag("team-input")?.text=="Retained Global draft"}
            val explicit=j.sends().single();assertEquals("Amber",explicit.getJSONArray("targets").getString(0));assertEquals(203L,explicit.optLong("replyTo"))
            j.click("team-reply:203");j.type("Saved Amber draft");j.back();j.click(j.leaf);j.keyboard();j.type("Retained other room draft")
            j.back();j.click(j.lobby);j.keyboard();j.await{j.tag("team-input")?.text=="Retained Global draft"}
            assertNull(j.tag("team-reply-clear"));j.click("team-send");j.await{j.sends().size==2}
            val root=j.sends()[1];assertEquals("lobby",root.s("task"));assertEquals(1,root.getJSONArray("targets").length());assertEquals("Violet",root.getJSONArray("targets").getString(0));assertFalse(root.has("replyTo"))
            assertEquals("Saved Amber draft",LosangelexClient(j.owner).draft(TeamScope("studio","lobby","Amber",replyTo=203)))
            assertEquals("Retained other room draft",LosangelexClient(j.owner).draft(TeamScope("studio","texture")))
        }finally{j.close()}
    }

    @Test fun visibleRoomListRefreshesAuthoritativeMembershipWithoutReopening(){
        val j=SessionRoomJourney()
        try{
            j.start();j.fixture("/live-room-change",true)
            j.await{j.device.hasObject(By.text("Updated lobby"))}
            val lobby=requireNotNull(j.tag(j.lobby));assertTrue(lobby.hasObject(By.text("1")))
            assertFalse(lobby.hasObject(By.text("2")))
            assertTrue(requireNotNull(j.tag(j.leaf)).hasObject(By.text("2")))
            assertTrue(j.fixture().getJSONArray("requests").objects().none{it.s("method") in listOf("send","control","membership")})
            j.click(j.lobby);j.keyboard();j.type("Draft survives live metadata")
            j.back();j.click(j.lobby);j.keyboard();j.await{j.tag("team-input")?.text=="Draft survives live metadata"}
        }finally{j.close()}
    }
}
