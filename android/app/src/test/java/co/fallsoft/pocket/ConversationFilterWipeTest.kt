package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class ConversationFilterWipeTest {
 @Test fun removedLeadingRowKeepsVisibleAnswerAtItsOffset(){
  assertEquals(FilterViewportAnchor(1,-140),conversationFilterViewportAnchor(listOf("old-answer","answer"),listOf(FilterVisibleRow("commentary",-70),FilterVisibleRow("answer",140))))
  assertEquals(FilterViewportAnchor(0,42),conversationFilterViewportAnchor(listOf("answer"),listOf(FilterVisibleRow("answer",-42))))
  assertNull(conversationFilterViewportAnchor(listOf("old-answer"),listOf(FilterVisibleRow("commentary",-70))))
 }
 @Test fun filterRetainsPassageAndChronology(){
  assertEquals(1,conversationFilterAnchor(listOf("a","b","c","d"),listOf("a","c","d"),2))
  assertEquals(1,conversationFilterAnchor(listOf("a","b","c","d"),listOf("a","c","d"),1))
  assertEquals(2,conversationFilterAnchor(listOf("a","c","d"),listOf("a","b","c","d"),1))
  assertEquals(-1,conversationFilterAnchor(listOf("a"),emptyList(),0))
 }
 @Test fun wipeMovesInReadingOrderAndFinishesEveryLine(){
  for(line in 0..3){assertEquals(0f,immersionWipeLineProgress(0f,line,4),0f);assertEquals(1f,immersionWipeLineProgress(1f,line,4),0f)}
  assertTrue(immersionWipeLineProgress(.5f,0,4)>immersionWipeLineProgress(.5f,2,4))
  assertTrue(immersionWipeLineProgress(.5f,2,4)>immersionWipeLineProgress(.5f,3,4))
 }
}
