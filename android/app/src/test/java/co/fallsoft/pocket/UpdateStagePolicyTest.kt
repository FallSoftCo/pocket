package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class UpdateStagePolicyTest {
    @Test fun verifiedNewerArtifactRemainsReadyIndependentlyOfLaterNetworkChecks(){
        assertTrue(UpdateStagePolicy.ready(27,25,true,true,7000000,7000000,"same","same"))
        assertFalse(UpdateStagePolicy.replace(27,26))
        assertFalse(UpdateStagePolicy.replace(27,27))
        assertTrue(UpdateStagePolicy.replace(27,28))
    }
    @Test fun missingUnverifiedTruncatedStaleOrForeignArtifactIsNeverReady(){
        assertFalse(UpdateStagePolicy.ready(27,25,false,true,7,7,"same","same"))
        assertFalse(UpdateStagePolicy.ready(27,25,true,false,7,7,"same","same"))
        assertFalse(UpdateStagePolicy.ready(27,25,true,true,6,7,"same","same"))
        assertFalse(UpdateStagePolicy.ready(25,25,true,true,7,7,"same","same"))
        assertFalse(UpdateStagePolicy.ready(27,25,true,true,7,7,"other","same"))
    }
}
