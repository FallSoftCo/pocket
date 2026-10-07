package co.fallsoft.pocket
import org.junit.Assert.*
import org.junit.Test
class WorkUpdatesPolicyTest {
 @Test fun onlyScheduledAssistantReportsMigrateOutOfCoordinator(){assertTrue(scheduledCoordinatorReport("report-abcdef012345-30-1234",""));assertFalse(scheduledCoordinatorReport("report-abcdef012345-30-1234","Please write a report"));assertFalse(scheduledCoordinatorReport("user-report-title",""));assertFalse(scheduledCoordinatorReport("coordinator-123",""))}
 @Test fun latestRefreshPreservesOldestLoadedBoundary(){val c=WorkInboxCursor();c.accept(61,true,false,false,false);c.accept(21,true,true,true,false);c.accept(61,true,false,true,true);assertEquals(21L,c.before);assertTrue(c.hasEarlier)}
 @Test fun latestGapLoadsUntilItRejoinsCachedHistory(){val c=WorkInboxCursor();c.accept(1,false,false,false,false);c.accept(961,true,false,true,false);assertEquals(961L,c.before);c.accept(921,true,true,true,false);assertEquals(921L,c.before);c.accept(61,true,true,true,true);assertEquals(1L,c.before);assertFalse(c.hasEarlier)}
}
