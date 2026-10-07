package co.fallsoft.pocket

import org.junit.Assert.*
import org.junit.Test

class BlackoutWindowSessionTest {
    @Test fun repeatedEntryDoesNotOverwriteOriginalWindowSettings() {
        val session = BlackoutWindowSession()
        assertTrue(session.enter(-1f, false))
        assertFalse(session.enter(0f, true))
        assertEquals(BlackoutWindowSession.Snapshot(-1f, false), session.exit())
        assertNull(session.exit())
    }
    @Test fun lifecycleExitRestoresExistingAwakeFlagAndAllowsFreshEntry() {
        val session = BlackoutWindowSession()
        session.enter(.7f, true)
        assertEquals(BlackoutWindowSession.Snapshot(.7f, true), session.exit())
        assertTrue(session.enter(.3f, false))
        assertEquals(BlackoutWindowSession.Snapshot(.3f, false), session.exit())
    }
}
