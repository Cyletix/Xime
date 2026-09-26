package com.kingzcheung.xime.service

internal enum class DeletionTarget { WAIT, COMPOSITION, ENGLISH, CANDIDATES, DOCUMENT }

/** UI can lag the engine. An unavailable engine is never evidence that the editor is idle. */
internal fun deletionTarget(engineComposing: Boolean?, uiComposing: Boolean, partialSegments: Boolean,
    pendingEnglish: Boolean, candidatesVisible: Boolean, predictionPending: Boolean): DeletionTarget = when {
    engineComposing == null -> DeletionTarget.WAIT
    engineComposing || uiComposing || partialSegments -> DeletionTarget.COMPOSITION
    pendingEnglish -> DeletionTarget.ENGLISH
    candidatesVisible || predictionPending -> DeletionTarget.CANDIDATES
    else -> DeletionTarget.DOCUMENT
}
