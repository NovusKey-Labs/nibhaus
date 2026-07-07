package com.nibhaus.ocr

import com.nibhaus.premiumapi.InkPt

/**
 * Where watcher-written `<base>.txt` transcripts (and their `<base>.json` sidecars) come from. The
 * page-mapping in TranscriptImporter is identical regardless of backend; this abstracts only *how
 * the bytes are obtained* — a SAF folder a sync app fills (SafTranscriptSource, free, in :app), or an
 * HTTP sync endpoint pulled over the tailnet (TailnetTranscriptSource, premium, in :premium).
 */
interface TranscriptSource {
    /** Every `.txt` transcript currently available, each with loaders for its bytes + `.json` sidecar. */
    suspend fun listTranscripts(): List<TranscriptFile>
}

/** One transcript: its relative path, plus lazy loaders for the `.txt` and its sibling `.json`. */
data class TranscriptFile(
    val path: String,
    val read: suspend () -> ByteArray?,
    val sidecar: suspend () -> ByteArray?,
)

/**
 * On-device handwriting OCR (ink -> text). :app's OnDeviceInk (free ML Kit instant tier) and
 * :premium's ServerInk/VlmInk (via `accurateChain()`) implement this too. Takes PRE-DECODED
 * strokes in native [InkPt] so no :app entity (StrokeEntity/Point) crosses the boundary — :app
 * decodes its stored points to InkPt before calling.
 */
interface InkOcr {
    /** [accurate]=false → fast/instant tier; true → best-available quality tier (may be slower). */
    suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean = false): String?
}
