package co.fallsoft.pocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CommandRunProjectionTest {
 private fun row(id:String,kind:String="activity",type:String="commandExecution",turn:String="a")=
  JSONObject().put("id",id).put("kind",kind).put("type",type).put("turnId",turn).put("text",id)
 @Test fun commandGroupsPreserveOutputBoundariesSourceIdentityAndExpandedLazyRows() {
  val source=listOf(row("user","user"),row("run1"),row("run2"),
   row("answer","message"),row("run3"),row("question","request"),
   row("run4"),row("run5",turn="b"),row("thought",type="reasoning"),row("final","message"))
  val original=source.map{it.toString()}
  val closed=commandRunProjection(source,emptySet())
  assertEquals(listOf("user","run1","answer","run3","question","run4","run5","thought","final"),closed.rows.map{closed.canonicalId(it.s("id"))})
  assertEquals(closed.headerFor("run1"),closed.displayId("run2"))
  assertEquals("answer",closed.displayId("answer"))
  assertEquals("activity",source[1].s("kind"))
  assertEquals(original,source.map{it.toString()})
  val open=commandRunProjection(source,setOf("run2"))
  assertEquals(source.map{it.s("id")},open.rows.map{open.canonicalId(it.s("id"))}.distinct())
  assertEquals(listOf("run1","run2","thought"),open.rows.filter{it.s("kind")=="activity"}.map{it.s("id")})
  assertEquals("run2",open.displayId("run2"))
  assertEquals(listOf("run1","run2"),open.groups.getValue(requireNotNull(open.headerFor("run1"))).map{it.s("id")})
  // A new older page may prepend a member; an expanded surviving member
  // keeps that group expanded without matching text or provider timestamps.
  val older=commandRunProjection(listOf(row("older"))+source.drop(1),setOf("run1"))
  assertEquals("run2",older.displayId("run2"))
  assertEquals(listOf("older","run1","run2"),older.groups.getValue(requireNotNull(older.headerFor("older"))).map{it.s("id")})
 }
}
