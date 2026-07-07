package com.nibhaus.share

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Feature 24: human-readable names for exported/shared page artifacts — "Nibhaus — {notebook
 * title} p{page} — {yyyy-MM-dd}" — instead of the internal "page-3-12" id the renderer used to
 * hand the share sheet. [sanitize] is what makes that safe to write to disk: it drops path
 * separators and other filesystem-hostile characters, strips emoji, collapses whitespace, and caps
 * the length so a long (or hostile) notebook title can't blow past filesystem limits.
 */
object ShareFilename {

    private const val MAX_LENGTH = 120

    // Path separators, Windows-reserved characters (harmless to strip on Android too), and control
    // characters — anything that could break writing the file or confuse a share target.
    private val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|\\x00-\\x1F]")

    // \x{...} codepoint escapes (not raw \uD800-\uDFFF surrogate-unit ranges — Pattern composes
    // surrogate pairs into their real codepoint before matching, so a bare surrogate-unit range
    // never actually hits a valid emoji). U+1F300-1FAFF covers the main emoji block; the BMP Misc
    // Symbols/Dingbats range and the variation selector catch the rest, without touching ordinary
    // punctuation like the em dash used in [forPage].
    private val EMOJI = Regex("[\\x{1F300}-\\x{1FAFF}\\u2600-\\u27BF\\uFE0F]")

    private val WHITESPACE = Regex("\\s+")

    /** e.g. "Nibhaus — Field Notes p12 — 2026-07-02" (no extension — callers append their own via
     *  [PageShare.Format]). */
    fun forPage(notebookTitle: String, pageNumber: Int, now: Date = Date()): String =
        sanitize("Nibhaus — ${notebookTitle.ifBlank { "Notebook" }} p$pageNumber — ${dateStamp(now)}")

    /** e.g. "Nibhaus — Field Notes p12 replay — 2026-07-02" for a Handwriting Replay GIF export. */
    fun forReplay(notebookTitle: String, pageNumber: Int, now: Date = Date()): String =
        sanitize("Nibhaus — ${notebookTitle.ifBlank { "Notebook" }} p$pageNumber replay — ${dateStamp(now)}")

    private fun dateStamp(now: Date): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)

    /** Filesystem-safe filename (no extension): strips path separators/control characters/emoji,
     *  collapses whitespace, and caps the length. Never returns a blank string. */
    fun sanitize(raw: String): String {
        val noEmoji = EMOJI.replace(raw, "")
        val noUnsafe = UNSAFE_CHARS.replace(noEmoji, " ")
        val collapsed = noUnsafe.trim().replace(WHITESPACE, " ").take(MAX_LENGTH).trim()
        return collapsed.ifBlank { "Nibhaus page" }
    }
}
