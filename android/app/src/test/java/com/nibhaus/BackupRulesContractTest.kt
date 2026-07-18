package com.nibhaus

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BackupRulesContractTest {
    @Test fun `both backup rule formats exclude the Room notes database and content stores`() {
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { name ->
            val rules = File("src/main/res/xml/$name").readText()
            listOf("nibhaus.db", "nibhaus.db-wal", "nibhaus.db-shm", "nibhaus_secrets.xml", "exports/")
                .forEach { exclusion -> assertThat(rules).contains("path=\"$exclusion\"") }
        }
    }
}
