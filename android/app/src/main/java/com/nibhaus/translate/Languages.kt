package com.nibhaus.translate

/**
 * The selectable languages for the translate picker (UI support data; stays in the freemium :app).
 *
 * Static snapshot of ML Kit's supported BCP-47 codes — the on-device fallback's set; the LLM path
 * handles these and more. Decoupled from ML Kit so :app needs no ML Kit dependency (the engine lives
 * in :premium). The codes mirror `TranslateLanguage.getAllLanguages()`.
 * // ponytail: static list; refresh from TranslateLanguage.getAllLanguages() if ML Kit adds languages.
 */
object Languages {
    private val CODES = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et",
        "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl", "pt", "ro", "ru",
        "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    /** (code, display name) sorted by name. */
    val all: List<Pair<String, String>> by lazy {
        CODES.map { code -> code to java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code } }
            .sortedBy { it.second.lowercase() }
    }

    fun nameOf(code: String?): String =
        if (code == null) "Auto-detect"
        else all.firstOrNull { it.first == code }?.second
            ?: java.util.Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
}
