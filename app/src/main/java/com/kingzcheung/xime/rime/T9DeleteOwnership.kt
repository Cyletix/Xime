package com.kingzcheung.xime.rime

/** A T9 undo or a consumed final syllable must never fall through to a second editor delete. */
internal fun t9DeleteConsumed(processed: Boolean, undoneSegments: Int, composingBefore: Boolean?): Boolean =
    processed || undoneSegments > 0 || composingBefore != false
