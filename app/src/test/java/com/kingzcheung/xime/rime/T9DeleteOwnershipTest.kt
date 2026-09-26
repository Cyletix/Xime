package com.kingzcheung.xime.rime

import org.junit.Assert.*
import org.junit.Test

class T9DeleteOwnershipTest {
    @Test fun undoCountPreventsASecondDeleteEvenIfKeyResultIsFalse() {
        assertTrue(t9DeleteConsumed(false, 1, false))
        assertTrue(t9DeleteConsumed(false, 2, false))
    }
    @Test fun consumingFinalInputOrUnavailableSnapshotCannotDeleteDocument() {
        assertTrue(t9DeleteConsumed(false, 0, true))
        assertTrue(t9DeleteConsumed(false, 0, null))
    }
    @Test fun onlyUnconsumedIdleT9FallsBackToEditor() {
        assertFalse(t9DeleteConsumed(false, 0, false))
        assertTrue(t9DeleteConsumed(true, 0, false))
    }
}
