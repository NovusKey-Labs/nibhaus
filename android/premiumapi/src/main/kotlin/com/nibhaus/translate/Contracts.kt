package com.nibhaus.translate

/** Which engine produced a translation (LLM over the user's tailnet, on-device LLM, or on-device ML Kit). */
enum class TranslationEngine { LLM, ON_DEVICE_LLM, ON_DEVICE }

/** A translation result + provenance. (Was the nested `Translator.Result` in :app.) */
data class TranslationResult(val text: String, val engine: TranslationEngine, val source: String?)

/**
 * Tiered translator (quality LLM over tailnet → on-device ML Kit fallback). Premium: Translator
 * implements this in :premium. Signatures are native-typed so :app depends only on this contract.
 */
interface InkTranslator {
    /**
     * @param target a language code (e.g. "es", "fr", "ja").
     * @param source a code, or null to detect on-device.
     */
    suspend fun translate(text: String, target: String, source: String? = null): TranslationResult?

    /** On-device language code of [text] (null if undetermined). */
    suspend fun detectLanguage(text: String): String?
}
