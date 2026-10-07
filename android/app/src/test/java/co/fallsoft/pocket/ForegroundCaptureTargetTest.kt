package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class ForegroundCaptureTargetTest {
    @Test fun inboxAndCoordinatorIgnoreRetainedConversationSelection(){
        assertEquals(ForegroundCaptureTarget(null,true),foregroundCaptureTarget(true,false,"stale-child",false,false))
        assertEquals(ForegroundCaptureTarget(null,true),foregroundCaptureTarget(false,true,"stale-chat",true,true))
    }
    @Test fun knownSupportedDirectConversationKeepsExplicitDestination(){
        assertEquals(ForegroundCaptureTarget("parent",true),foregroundCaptureTarget(false,false,"parent",true,true))
        assertEquals(ForegroundCaptureTarget("supported-child",true),foregroundCaptureTarget(false,false,"supported-child",true,true))
    }
    @Test fun existingCaptureCanStopWithoutRetargetingAfterNavigation(){
        assertEquals(ForegroundCaptureTarget("original-parent",true),foregroundCaptureTarget(true,false,"readonly-child",true,false,true,"original-parent"))
    }
    @Test fun unknownAndUnsupportedChildrenCannotAcquireDirectCapture(){
        assertFalse(foregroundCaptureTarget(false,false,"unknown",false,true).eligible)
        assertFalse(foregroundCaptureTarget(false,false,"child",true,false).eligible)
        assertEquals(ForegroundCaptureTarget(null,true),foregroundCaptureTarget(false,false,null,false,false))
    }
}
