package com.nibhaus.text

/**
 * Derives a page's display title from its transcript (Phase B): the first non-empty line, trimmed
 * and length-capped. Pure → unit-tested. Falls back to null when there's no transcript yet (callers
 * use "Page N"). User-editable downstream; this only seeds the title when a transcript arrives.
 */
object AutoTitle {
    fun fromTranscript(transcript: String?, maxLen: Int = 60): String? {
        val line = transcript
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (line.length <= maxLen) line else line.take(maxLen).trimEnd() + "…"
    }
}
