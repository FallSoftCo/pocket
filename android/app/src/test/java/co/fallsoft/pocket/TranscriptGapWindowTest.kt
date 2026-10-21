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

 @Test fun newerLiveHeadCannotSmuggleAnOldDisconnectedPrefixIntoTheCurrentWindow(){
  fun row(id:String,version:Int=0)=JSONObject().put("id",id).put("kind","message").put("version",version)
  val canonical=listOf(row("current-a"),row("current-b"))
  val live=listOf(row("oct10"),row("current-a"),row("current-b",10),row("new-live",11))
  val merged=newerTranscriptHead(canonical,live)!!
  assertEquals(listOf("current-a","current-b","new-live"),merged.map{it.s("id")})
  assertEquals(10,merged[1].optInt("version"))
  assertNull(newerTranscriptHead(canonical,listOf(row("disconnected"))))
 }

 @Test fun legacyOverlappingCacheDoesNotResolveItsMissingMiddleAtTheFirstRecentOverlap(){
  fun row(id:String)=JSONObject().put("id",id).put("kind","message")
  val legacy=JSONObject().put("schema",1).put("coverageVerified",true)
  assertFalse(verifiedTranscriptCoverage(legacy))
  assertTrue(verifiedTranscriptCoverage(JSONObject().put("schema",2).put("coverageVerified",true)))
  val retained=listOf(row("old-1"),row("old-0"),row("recent-3"),row("recent-4"),row("head-5"))
  val head=listOf(row("head-5"))
  val gap=missingTranscriptWindow(retained,head,JSONObject().put("hasEarlier",true).put("before","before-5"),emptyList(),unknownCoverage=true)!!
  assertEquals("old-1",gap.olderAnchor)
  val first=advanceTranscriptGap(gap,listOf(row("recent-3"),row("recent-4")),JSONObject().put("hasEarlier",true).put("before","before-3"))!!
  val middle=advanceTranscriptGap(first,listOf(row("old-1"),row("missing-2")),JSONObject().put("hasEarlier",true).put("before","before-1"))!!
  assertEquals(setOf("old-0"),middle.unverified)
  assertNull(advanceTranscriptGap(middle,listOf(row("old-0")),JSONObject().put("hasEarlier",false)))
 }
 @Test fun sourceEofCannotEraseAnUnconnectedSavedWindow(){
  val gap=TranscriptGapWindow("gap","cursor","new","saved-old",unverified=setOf("saved-old","missing-from-source"))
  val stopped=advanceTranscriptGap(gap,listOf(JSONObject().put("id","saved-old").put("kind","message"),JSONObject().put("id","source-end").put("kind","message")),JSONObject().put("hasEarlier",false))!!
  assertTrue(stopped.exhausted)
  assertEquals("saved-old",stopped.olderAnchor)
  val rows=listOf(JSONObject().put("id","missing-from-source").put("kind","message"),JSONObject().put("id","saved-old").put("kind","message"),JSONObject().put("id","source-end").put("kind","message"))
  assertEquals(listOf(stopped),restoreTranscriptGaps(JSONObject().put("historyGaps",JSONArray(listOf(stopped.json()))),rows))
 }
}
