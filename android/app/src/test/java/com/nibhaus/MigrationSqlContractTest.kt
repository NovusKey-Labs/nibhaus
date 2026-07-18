package com.nibhaus

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.MIGRATION_10_11
import com.nibhaus.data.MIGRATION_11_12
import com.nibhaus.data.MIGRATION_8_9
import com.nibhaus.data.MIGRATION_9_10
import java.lang.reflect.Proxy
import org.junit.Test

/** Pure-JVM coverage for migration SQL; Android SQLite/Room identity is covered by androidTest. */
class MigrationSqlContractTest {
    private fun sqlFrom(migration: Migration): List<String> {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") statements += args!![0] as String
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as SupportSQLiteDatabase
        migration.migrate(db)
        return statements
    }

    @Test fun `migration 8 to 9 creates porter FTS4 and backfills non-null transcripts`() {
        val sql = sqlFrom(MIGRATION_8_9)

        assertThat(sql).hasSize(2)
        assertThat(sql[0]).contains("CREATE VIRTUAL TABLE IF NOT EXISTS `page_fts` USING FTS4")
        assertThat(sql[0]).contains("`pageId` TEXT, `transcript` TEXT, tokenize=porter")
        assertThat(sql[1]).isEqualTo(
            "INSERT INTO `page_fts` (`pageId`, `transcript`) " +
                "SELECT `id`, `transcript` FROM `pages` WHERE `transcript` IS NOT NULL",
        )
    }

    @Test fun `migration chain 8 to 12 applies every additive schema change in order`() {
        val chain = listOf(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
        assertThat(chain.map { it.startVersion to it.endVersion })
            .containsExactly(8 to 9, 9 to 10, 10 to 11, 11 to 12).inOrder()

        val sql = chain.flatMap(::sqlFrom)
        assertThat(sql.joinToString("\n")).contains("ALTER TABLE `recordings` ADD COLUMN `addressKey`")
        assertThat(sql.joinToString("\n")).contains("index_outbox_enqueuedAt")
        assertThat(sql.joinToString("\n")).contains("CREATE TABLE IF NOT EXISTS `pending_remote_deletes`")
        assertThat(sql.joinToString("\n")).contains("index_pending_remote_deletes_enqueuedAt")
    }
}
