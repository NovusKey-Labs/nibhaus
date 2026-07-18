package com.nibhaus

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BackupRulesContractTest {
    private val privateStores = listOf(
        "nibhaus.db", "nibhaus.db-wal", "nibhaus.db-shm", "nibhaus.db-journal",
        "nibhaus_secrets.xml", "pen_prefs.xml", "nibhaus_notebook_profiles.xml",
        "nibhaus_zones.xml", "nibhaus_backgrounds.xml",
        "datastore/", "recordings/", "exports/", "crash/",
    )

    @Test fun `legacy backup excludes every private persistent store`() {
        assertExcludesEveryStore(File("src/main/res/xml/backup_rules.xml").readText(), copies = 1)
    }

    @Test fun `cloud backup and device transfer exclude every private persistent store`() {
        assertExcludesEveryStore(File("src/main/res/xml/data_extraction_rules.xml").readText(), copies = 2)
    }

    private fun assertExcludesEveryStore(rules: String, copies: Int) {
        privateStores.forEach { exclusion ->
            assertThat(Regex("path=\\\"${Regex.escape(exclusion)}\\\"").findAll(rules).count())
                .isEqualTo(copies)
        }
    }
}
