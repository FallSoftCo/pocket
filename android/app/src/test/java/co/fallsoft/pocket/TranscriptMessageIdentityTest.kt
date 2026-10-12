package co.fallsoft.pocket
import org.junit.Test
import org.junit.Assert.*
class TranscriptMessageIdentityTest {
 @Test fun reconstructedProviderIdsKeepOneSendIdentity(){
  val live=transcriptMessageIdentity("user","turn","send","turn/uuid")
  assertEquals(live,transcriptMessageIdentity("user","turn","send","turn/item-1"))
  assertEquals(live,transcriptMessageIdentity("user","turn","send","turn/item-183"))
 }
 @Test fun separateSendsAndTurnsRemainSeparate(){
  assertNotEquals(transcriptMessageIdentity("user","turn","one","x"),transcriptMessageIdentity("user","turn","two","y"))
  assertNotEquals(transcriptMessageIdentity("user","one","send","x"),transcriptMessageIdentity("user","two","send","y"))
 }
 @Test fun absentEvidenceAndNonUserItemsKeepProviderIdentity(){
  assertEquals("provider",transcriptMessageIdentity("user","turn","","provider"))
  assertEquals("provider",transcriptMessageIdentity("message","turn","send","provider"))
 }
}
