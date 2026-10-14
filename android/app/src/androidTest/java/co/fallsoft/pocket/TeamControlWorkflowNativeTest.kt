package co.fallsoft.pocket

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeamControlWorkflowNativeTest {
 @Test fun existingAgentDisclosureOpensIndependentSessionAndKeepsPrivateRepliesScoped(){
  val j=SessionRoomJourney();try{j.start()
   val badge="2 agents, 0 working, 0 unread; show agents"
   j.click(By.desc(badge))
   j.click(By.text("Amber · Review · idle"))
   j.assertNoImplementationControls();j.keyboard();j.type("Retained private draft")
   j.click("team-reply:201");assertNull(j.device.findObject(By.textContains("#201")));j.type("Reply within independent session")
   j.click("team-reply-clear");j.await{j.tag("team-input")?.text=="Retained private draft"}
   j.click("team-reply:201");j.await{j.tag("team-input")?.text=="Reply within independent session"}
   j.click("team-send");j.await{j.sends().size==1}
   val sent=j.sends().single();assertEquals("lobby",sent.s("task"));assertEquals("direct",sent.s("visibility"));assertEquals("Amber",sent.getJSONArray("targets").getString(0));assertEquals(1,sent.getJSONArray("targets").length());assertEquals(201L,sent.optLong("replyTo"))
   assertEquals("Retained private draft",Pocket.prefs.getString(TeamScope("studio","lobby","Amber",true).draftKey(j.owner),null))
   j.fixture("/amber-working",true);j.await{j.device.findObject(By.desc("Stop agent"))!=null};requireNotNull(j.device.findObject(By.desc("Stop agent"))).click()
   j.await{j.fixture().getJSONArray("requests").objects().any{it.s("method")=="control"}}
   val stop=j.fixture().getJSONArray("requests").objects().single{it.s("method")=="control"}.getJSONObject("body")
   assertEquals("lobby",stop.s("task"));assertEquals("Amber",stop.s("agent"));assertEquals("interrupt",stop.s("action"))
   assertTrue(j.fixture().getJSONArray("requests").objects().any{it.s("method")=="history"&&it.s("scope")=="direct:Amber"})
  }finally{j.close()}
 }
}
