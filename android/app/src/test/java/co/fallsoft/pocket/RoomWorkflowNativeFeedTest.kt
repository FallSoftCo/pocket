package co.fallsoft.pocket

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the private canonical feed from the real native Global/worker cancellation. */
class RoomWorkflowNativeFeedTest {
 @Test fun canonicalWorkflowFactsStayOutsideRunsAndNeverBecomeHumanMessages(){
  val path=System.getenv("HOLLYWOOD_WORKFLOW_HISTORY")
  assumeTrue("Explicit actual native workflow feed required",!path.isNullOrBlank())
  val events=JSONObject(File(path!!).readText()).getJSONArray("events").objects()
  val original=events.map{it.toString()}
  val facts=events.filter{it.optJSONObject("data")?.s("workflowAction").orEmpty().isNotBlank()||it.optJSONObject("data")?.s("type")=="workflowState"}
  assertTrue("Actual native goal/work facts required",facts.isNotEmpty())
  val source=events.map{JSONObject(it.toString()).put("isSelf",true)}
  val rows=roomTranscriptRows(source,"native-cancellation")
  val factIds=facts.map{it.s("id")}.toSet()
  val projected=rows.filter{it.s("roomEventId") in factIds}
  assertEquals(factIds.size,projected.size)
  assertTrue(projected.all{it.s("kind")=="activity"&&it.s("type")=="workflowState"&&it.s("roomAuthor")=="Hollywood"})
  assertTrue(projected.none(::isCommandRun))
  assertTrue(projected.any{it.s("title")=="Goal · cancelled"})
  assertTrue(projected.all{it.s("detail").contains("Initiated by:")&&it.s("detail").contains("Event:")})
  val collapsed=commandRunProjection(rows,emptySet())
  assertTrue(projected.all{fact->collapsed.rows.any{it.s("id")==fact.s("id")}})
  assertEquals(original,events.map{it.toString()})
 }
}

