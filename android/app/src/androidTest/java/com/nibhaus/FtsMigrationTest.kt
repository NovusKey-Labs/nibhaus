package com.nibhaus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.InkDatabase
import com.nibhaus.data.MIGRATION_8_9
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.PageEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates the v8→v9 migration (adds the `page_fts` full-text index) on a real Android image — the
 * one place the FTS virtual table, its porter tokenizer, and Room's post-migration schema-identity
 * check actually run. A hand-written CREATE VIRTUAL TABLE that doesn't match Room's generated schema
 * for [com.nibhaus.data.PageFts] would throw here, in CI, instead of crash-looping on a user's
 * device. We have no exported v8 schema JSON (exportSchema was off through v8), so rather than
 * MigrationTestHelper we rewind a Room-built v9 file to v8: the FTS table is the *only* v8→v9 delta,
 * so dropping it and setting user_version=8 faithfully reproduces a pre-FTS database — including a
 * real transcript for the migration's backfill to pick up.
 */
@RunWith(AndroidJUnit4::class)
class FtsMigrationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "fts_migration_test.db"

    private fun open() = Room.databaseBuilder(ctx, InkDatabase::class.java, name)
        .addMigrations(MIGRATION_8_9)
        .build()

    @Before fun clean() { ctx.deleteDatabase(name) }
    @After fun cleanup() { ctx.deleteDatabase(name) }

    @Test
    fun migration_8_9_createsFtsIndexAndBackfillsExistingTranscripts() = runTest {
        // 1) Current (v9) schema, with a page transcribed *before* the index would have existed.
        var db = open()
        db.notebookDao().insert(NotebookEntity("nb", book = 1, instanceSeq = 0, locked = false, title = "Notes", createdAt = 0))
        db.pageDao().insert(
            PageEntity("p1", "nb", "1.1.1.1", 1, 1, 1, 1, firstSeenAt = 0, lastInkAt = 5, transcript = "quarterly running budget review"),
        )
        val path = db.openHelper.writableDatabase.path!!
        db.close()

        // 2) Rewind the file to look like v8.
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE IF EXISTS `page_fts`")
            raw.execSQL("PRAGMA user_version = 8")
        }

        // 3) Reopen → Room runs MIGRATION_8_9 and validates the result against the v9 schema.
        db = open()
        // Backfill repopulated the index from `pages`; porter stemming + prefix make "run*" hit "running".
        assertThat(db.pageDao().searchByTranscriptFts("run*").map { it.id }).contains("p1")
        // A fresh transcript write stays in sync through the funnel, replacing the old text in the index.
        db.pageDao().setTranscriptIndexed("p1", "weekly grocery list eggs milk")
        assertThat(db.pageDao().searchByTranscriptFts("milk*").map { it.id }).contains("p1")
        assertThat(db.pageDao().searchByTranscriptFts("run*")).isEmpty()
        db.close()
    }
}
