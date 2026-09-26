package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class DeletionTargetTest {
    @Test fun delayedCandidateBarCannotMakeCompositionDeleteTheDocument() {
        assertEquals(DeletionTarget.COMPOSITION, deletionTarget(true, false, false, false, false, false))
    }
    @Test fun partiallySelectedT9WordsStillBelongToTheIme() {
        assertEquals(DeletionTarget.COMPOSITION, deletionTarget(false, false, true, false, false, false))
    }
    @Test fun busyEngineNeverAuthorizesDocumentDeletion() {
        assertEquals(DeletionTarget.WAIT, deletionTarget(null, false, false, false, false, false))
    }
    @Test fun predictionBeforeAndAfterDeliveryHasTheSameDeleteTarget() {
        assertEquals(DeletionTarget.CANDIDATES, deletionTarget(false, false, false, false, false, true))
        assertEquals(DeletionTarget.CANDIDATES, deletionTarget(false, false, false, false, true, false))
    }
    @Test fun idleEditorAndDirectEnglishStillDeleteNormally() {
        assertEquals(DeletionTarget.DOCUMENT, deletionTarget(false, false, false, false, false, false))
        assertEquals(DeletionTarget.ENGLISH, deletionTarget(false, false, false, true, false, true))
    }
}
