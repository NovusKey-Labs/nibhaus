package com.nibhaus.feedback

import android.content.Context
import java.io.File

/** Safety cap so a pathological crash (e.g. a deep recursive StackOverflowError) can't write an
 *  unbounded file. Keeps the head — the exception + innermost frames are the most diagnostic part. */
private const val MAX_STACK_TRACE_CHARS = 20_000

/**
 * Crash capture (feedback mechanism, part 2): a default [Thread.UncaughtExceptionHandler] that
 * writes a bounded crash report to `filesDir/crash/last_crash.txt` and then ALWAYS delegates to
 * whatever handler was previously installed, so system crash handling (process death, any other
 * installed handler) is unchanged. File write only — no DB access, so this never touches the
 * persist-first ingest invariant.
 */
object CrashCapture {
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    /**
     * Install the handler. Call once, as early as possible in Application.onCreate — before that,
     * a startup crash wouldn't be captured. Every step is wrapped in try/catch: a crash *in* the
     * crash handler must still reach the previous handler, or the process would die silently
     * instead of the normal (visible, debuggable) way.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashFile(appContext, System.currentTimeMillis(), throwable.stackTraceToString())
            } catch (_: Throwable) {
                // Never let the crash handler itself mask the real crash.
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun writeCrashFile(context: Context, timestampMillis: Long, stackTrace: String) {
        val dir = File(context.filesDir, CRASH_DIR)
        dir.mkdirs()
        File(dir, CRASH_FILE).writeText(formatCrashReport(timestampMillis, stackTrace))
    }

    /** Raw contents of the last crash file, or null if there isn't one / it can't be read. */
    fun readLastCrash(context: Context): String? =
        File(File(context.filesDir, CRASH_DIR), CRASH_FILE)
            .takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
}

/** Bounds [stackTrace] to at most [maxChars], appending a truncation marker when cut. */
fun boundedStackTrace(stackTrace: String, maxChars: Int = MAX_STACK_TRACE_CHARS): String =
    if (stackTrace.length <= maxChars) stackTrace else stackTrace.take(maxChars) + "\n… [truncated]"

/** The exact bytes [CrashCapture] writes: a parseable `CRASH_AT=<epochMillis>` header line, then
 *  the (bounded) stack trace. */
fun formatCrashReport(timestampMillis: Long, stackTrace: String, maxChars: Int = MAX_STACK_TRACE_CHARS): String =
    "CRASH_AT=$timestampMillis\n${boundedStackTrace(stackTrace, maxChars)}"

/** Parses the `CRASH_AT=` header [formatCrashReport] writes; null if absent/malformed. */
fun parseCrashTimestamp(crashFileContent: String?): Long? =
    crashFileContent?.lineSequence()?.firstOrNull()?.removePrefix("CRASH_AT=")?.toLongOrNull()

/** Everything after the header line — just the stack trace, for display/attachment. Null if the
 *  file is missing, empty, or somehow only a header. */
fun crashReportBody(crashFileContent: String?): String? {
    val content = crashFileContent ?: return null
    val idx = content.indexOf('\n')
    if (idx < 0) return null
    return content.substring(idx + 1).takeIf { it.isNotBlank() }
}

/** The excerpt embedded in a [FeedbackBundle]: the last [maxChars] characters of the crash body —
 *  the literal "tail" — so a huge trace doesn't dominate the email. Null when there's nothing to show. */
fun crashTail(crashBody: String?, maxChars: Int = 2_000): String? {
    val body = crashBody?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (body.length <= maxChars) body else "…" + body.takeLast(maxChars)
}

/**
 * Whether the "Nibhaus crashed last time" card should show: a crash report exists and its
 * timestamp doesn't match the one already acknowledged (0L = never acknowledged). A NEW crash
 * after an old acknowledged one still prompts, since its timestamp differs.
 */
fun crashPromptEligible(crashTimestamp: Long?, acknowledgedTimestamp: Long): Boolean =
    crashTimestamp != null && crashTimestamp != acknowledgedTimestamp
