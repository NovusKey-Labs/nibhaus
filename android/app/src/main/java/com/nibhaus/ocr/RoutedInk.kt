package com.nibhaus.ocr

import com.nibhaus.premiumapi.InkPt
import kotlinx.coroutines.CancellationException

/** Composite InkOcr. accurate=false -> instant tier; accurate=true -> first non-blank from the
 *  best-first chain, else the instant result. Non-cancellation failures fall through to the next
 *  tier; [CancellationException] is rethrown immediately to honour structured concurrency.
 *
 *  [accurateChain] is a SUPPLIER, not a frozen list: it's invoked fresh on every accurate request so
 *  a runtime setting change (a newly configured BYO endpoint, a forced-on-device-VLM override) takes
 *  effect on the very next accurate pass instead of only after the app (and this RoutedInk) is
 *  reconstructed. See ServiceLocator.onDeviceInk's wiring. */
class RoutedInk(
    private val instant: InkOcr,
    private val accurateChain: () -> List<InkOcr>,
) : InkOcr {
    override suspend fun transcribe(strokes: List<List<InkPt>>, accurate: Boolean): String? {
        if (accurate) {
            for (engine in accurateChain()) {
                val r = runCatching { engine.transcribe(strokes, accurate = true) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
                if (!r.isNullOrBlank()) return r
            }
        }
        return runCatching { instant.transcribe(strokes, accurate = false) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
    }
}
