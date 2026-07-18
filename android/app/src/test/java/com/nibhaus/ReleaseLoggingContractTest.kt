package com.nibhaus

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ReleaseLoggingContractTest {
    @Test fun `release R8 strips verbose debug and info logs but retains warnings and errors`() {
        val rules = File("proguard-rules.pro").readText()
        assertThat(rules).contains("-assumenosideeffects class android.util.Log")
        listOf("v", "d", "i").forEach { level ->
            assertThat(rules).contains("public static int $level(...);")
        }
        listOf("w", "e", "wtf").forEach { level ->
            assertThat(rules).doesNotContain("public static int $level(...);")
        }
    }

    @Test fun `surviving pen warnings and errors do not interpolate private pen data`() {
        val penSources = listOf(
            File("src/main/java/com/nibhaus/pen"),
            File("../pencore/src/main"),
            File("../penble/src/main"),
            File("../neosdk/src/main"),
        ).flatMap { root -> root.walkTopDown().filter { it.extension in setOf("kt", "java") }.toList() }

        val sensitiveWarning = Regex(
            "Log\\.(?:w|e|wtf)\\([^\\n]*(?:sppAddress|leAddress|penAddress|penName|coordinates?|payload|hex\\()",
            RegexOption.IGNORE_CASE,
        )
        penSources.forEach { source ->
            assertThat(sensitiveWarning.find(source.readText()))
                .isNull()
        }
    }

    @Test fun `per dot JSON logging remains debug gated`() {
        val source = File("src/main/java/com/nibhaus/ingest/StrokeIngestor.kt").readText()
        assertThat(source).contains("if (BuildConfig.DEBUG)")
        assertThat(source).contains("Log.d(")
    }
}
