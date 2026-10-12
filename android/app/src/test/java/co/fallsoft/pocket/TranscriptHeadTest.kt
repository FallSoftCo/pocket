package co.fallsoft.pocket
import org.junit.Test
import org.junit.Assert.*
class TranscriptHeadTest {
 @Test fun unchangedAndOlderHeadsDoNotReloadHistory(){val h=TranscriptHead("one",8);assertFalse(transcriptHeadAdvanced(h,h,h));assertFalse(transcriptHeadAdvanced(h,h,TranscriptHead("one",7)));assertTrue(transcriptHeadAdvanced(h,h,TranscriptHead("one",9)))}
 @Test fun unknownEvictedHeadCannotInventNewerWork(){val h=TranscriptHead("one",8);assertFalse(transcriptHeadAdvanced(h,h,TranscriptHead("one",null)));assertEquals(h,observeTranscriptHead(h,TranscriptHead("one",null)))}
 @Test fun restartInvalidatesButDelayedOldEpochDoesNot(){val old=TranscriptHead("old",80);val fresh=TranscriptHead("new",1);assertTrue(transcriptHeadAdvanced(old,old,fresh));assertFalse(transcriptHeadAdvanced(fresh,old,old))}
 @Test fun deliveredLiveOutputMakesStatusProbeRedundant(){val old=TranscriptHead("one",8);val live=TranscriptHead("one",10);assertFalse(transcriptHeadAdvanced(live,old,TranscriptHead("one",9)));assertTrue(transcriptHeadAdvanced(live,old,TranscriptHead("one",11)))}
 @Test fun slowSnapshotRetainsNewerConcurrentEpochAndCounter(){val old=TranscriptHead("old",80);val live=TranscriptHead("new",2);assertEquals(live,snapshotTranscriptHead(live,old,old));assertEquals(live,snapshotTranscriptHead(live,TranscriptHead("new",1),TranscriptHead("new",1)))}
 @Test fun bootstrapAndPreviouslyUnknownEvidenceCanRefresh(){val unknown=TranscriptHead("one",null);assertTrue(transcriptHeadAdvanced(null,null,unknown));assertTrue(transcriptHeadAdvanced(unknown,unknown,TranscriptHead("one",1)));assertEquals(TranscriptHead("new",1),snapshotTranscriptHead(TranscriptHead("old",80),TranscriptHead("old",80),TranscriptHead("new",1)))}
}
