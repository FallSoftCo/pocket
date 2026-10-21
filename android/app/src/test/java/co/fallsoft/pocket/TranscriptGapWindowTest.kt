package co.fallsoft.pocket

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptGapWindowTest {
 @Test fun multipleIntervalsRestoreWithoutCollapsingSourceWindowsOrGroupingAcrossThem(){
  val rows=listOf(
   JSONObject().put("id","old").put("kind","activity").put("turnId","t"),
   JSONObject().put("id","middle").put("kind","activity").put("turnId","t"),
   JSONObject().put("id","new").put("kind","message").put("turnId","t"))
  val windows=listOf(TranscriptGapWindow("a","cursor-a","middle","old"),TranscriptGapWindow("b","cursor-b","new","middle"))
  val restored=restoreTranscriptGaps(JSONObject().put("historyGaps",JSONArray(windows.map{it.json()})),rows)
  assertEquals(windows,restored)
  val display=transcriptRowsWithGaps(rows,restored.map{TranscriptHistoryGap(it.id,it.cursor,it.beforeRow,false,"")})
  assertEquals(listOf("old","ui:history-gap:a","middle","ui:history-gap:b","new"),display.map{it.s("id")})
  assertEquals(2,commandRunProjection(display,emptySet()).groups.size)
  assertEquals(listOf("old","middle","new"),rows.map{it.s("id")})
 }
 @Test fun cacheMigrationUsesCanonicalSendIdentityAndMoreThanOneLegacyWindowOfAnchors(){
  val old=JSONObject().put("id","t/provider-user").put("itemId","provider-user").put("kind","user").put("turnId","t").put("clientId","verified-send")
  val newer=JSONObject().put("id","new").put("kind","message").put("turnId","t")
  val value=JSONObject().put("bridgeBefore","older-cursor").put("bridgeBoundary","new").put("bridgeKnown",JSONArray(listOf("t/provider-user")))
  val migrated=restoreTranscriptGaps(value,listOf(old,newer)).single()
  assertEquals("t/client-user/verified-send",migrated.olderAnchor)
  val many=(0 until 1100).map{JSONObject().put("id","row-"+it).put("kind","message")}
  val anchors=many.mapTo(hashSetOf()){it.s("id")}
  assertEquals(anchors,migrateTranscriptAnchors(many,anchors).keys)
 }

 @Test fun headWindowCannotHideMissingMiddleOrChooseAnUnorderedOldAnchor(){
  fun row(id:String)=JSONObject().put("id",id).put("kind","message")
  val old=listOf(row("oct10-a"),row("oct10-z"))
  val head=listOf(row("today-a"),row("today-z"))
  val page=JSONObject().put("hasEarlier",true).put("before","canonical-older-cursor")
  val gap=missingTranscriptWindow(old,head,page,emptyList())!!
  assertEquals("oct10-z",gap.olderAnchor)
  assertEquals("today-a",gap.beforeRow)
  assertNull(missingTranscriptWindow(old,head,page,listOf(gap)))
  assertNull(missingTranscriptWindow(head,head,page,emptyList()))
  assertNull(missingTranscriptWindow(old,head,JSONObject().put("hasEarlier",false),emptyList()))
 }
}
