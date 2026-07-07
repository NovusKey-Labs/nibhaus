package com.nibhaus.capture

import com.nibhaus.pen.PenDot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/** One recorded pen dot with its full Ncode address — the raw fact the pen reports. */
data class CapturedDot(
    val section: Int, val owner: Int, val book: Int, val page: Int,
    val x: Float, val y: Float, val pressure: Float, val phase: String, val t: Long,
)

/**
 * Forks the live pen-dot stream into a recordable log for the Capture Lab (measuring scale, icon
 * boxes, planner reference points…). While recording, dots are consumed — they don't become ink —
 * so calibration scribbles never dirty a page. Everything here is the pen's own output; no app or
 * cloud is scraped.
 */
class CaptureLog {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    private val buffer = ArrayList<CapturedDot>()

    fun start() { synchronized(buffer) { buffer.clear() }; _recording.value = true }

    fun stop(): List<CapturedDot> {
        _recording.value = false
        return synchronized(buffer) { buffer.toList() }
    }

    /** True if the dot was consumed (we're recording) so the caller skips normal ink ingest. */
    fun onDot(dot: PenDot): Boolean {
        if (!_recording.value) return false
        synchronized(buffer) {
            buffer.add(
                CapturedDot(dot.section, dot.owner, dot.book, dot.page, dot.x, dot.y, dot.pressure, dot.phase.name, dot.timestamp),
            )
        }
        return true
    }
}

/** One labelled scale line: a notebook type, the real length drawn, and the captured dots. */
data class Measurement(val notebook: String, val knownCm: Float, val dots: List<CapturedDot>) {
    /** Ncode units per cm implied by this line (0 if not derivable). */
    val unitsPerCm: Float get() = if (knownCm > 0f) traceSpan(dots) / knownCm else 0f
}

/** CSV of all measurements, tagged by notebook + known length, so it cross-references with photos. */
fun measurementsCsv(rows: List<Measurement>): String = buildString {
    append("notebook,known_cm,units_per_cm,section,owner,book,page,x,y,pressure,phase,t\n")
    rows.forEach { m ->
        val upc = "%.3f".format(m.unitsPerCm)
        m.dots.forEach { d ->
            append("\"${m.notebook}\",${m.knownCm},$upc,${d.section},${d.owner},${d.book},${d.page},${d.x},${d.y},${d.pressure},${d.phase},${d.t}\n")
        }
    }
}

/** Ncode units spanned by a captured trace (its bounding-box diagonal ≈ a drawn line's length). */
fun traceSpan(dots: List<CapturedDot>): Float {
    if (dots.size < 2) return 0f
    val minX = dots.minOf { it.x }; val maxX = dots.maxOf { it.x }
    val minY = dots.minOf { it.y }; val maxY = dots.maxOf { it.y }
    return hypot(maxX - minX, maxY - minY)
}

/** CSV (one row per dot) for export — readable, and easy to cross-reference with a page photo. */
fun toCsv(dots: List<CapturedDot>): String = buildString {
    append("section,owner,book,page,x,y,pressure,phase,t\n")
    dots.forEach {
        append("${it.section},${it.owner},${it.book},${it.page},${it.x},${it.y},${it.pressure},${it.phase},${it.t}\n")
    }
}
