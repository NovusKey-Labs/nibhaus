package com.nibhaus.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun syncStateToString(s: SyncState): String = s.name

    @TypeConverter
    fun stringToSyncState(s: String): SyncState = SyncState.valueOf(s)
}

@Database(
    entities = [
        NotebookEntity::class,
        PageEntity::class,
        StrokeEntity::class,
        PendingDotEntity::class,
        OutboxEntry::class,
        ExportRecord::class,
        RecordingEntity::class,
        PageTag::class,
        PageFts::class,
        PendingRemoteDelete::class,
        PendingLocalDeleteCleanup::class,
    ],
    version = 13,
    exportSchema = false,
)
@TypeConverters(Converters::class)
@Suppress("TooManyFunctions") // Room requires one abstract accessor per DAO owned by the database.
abstract class InkDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun pendingDotDao(): PendingDotDao
    abstract fun outboxDao(): OutboxDao
    abstract fun ingestDao(): IngestDao
    abstract fun exportDao(): ExportDao
    abstract fun recordingDao(): RecordingDao
    abstract fun tagDao(): TagDao
    abstract fun pendingRemoteDeleteDao(): PendingRemoteDeleteDao
    abstract fun pendingLocalDeleteCleanupDao(): PendingLocalDeleteCleanupDao
    abstract fun deleteDao(): DeleteDao
}

/**
 * v4 → v5: add the `recordings` table WITHOUT wiping captured pages (the destructive fallback would
 * delete the user's real notes). These CREATE statements must match Room's generated schema for
 * [RecordingEntity] exactly, or the runtime identity check fails.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recordings` (`id` TEXT NOT NULL, `pageId` TEXT NOT NULL, " +
                "`path` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_recordings_pageId` ON `recordings` (`pageId`)")
    }
}

/** v5 → v6: add the voice-note `title` column (default empty) without touching captured data. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''")
    }
}

/** v6 → v7: add the `page_tags` table (Phase E) without touching captured data. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `page_tags` (`pageId` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                "PRIMARY KEY(`pageId`, `tag`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_tags_tag` ON `page_tags` (`tag`)")
    }
}

/** v7 → v8: add the page `transcript` column (OCR text imported from the sync folder). */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `pages` ADD COLUMN `transcript` TEXT")
    }
}

/**
 * v8 → v9: add the `page_fts` full-text index over transcripts (FTS4, porter tokenizer) and backfill
 * it from every page that already has a transcript. Purely additive — no captured data is touched.
 * The CREATE VIRTUAL TABLE must match Room's generated schema for [PageFts] (name, columns,
 * tokenizer) or Room's post-migration identity check fails; the instrumented FtsMigrationTest runs
 * this migration on a real Android image so a mismatch is caught in CI, never on a user's device.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `page_fts` USING FTS4(" +
                "`pageId` TEXT, `transcript` TEXT, tokenize=porter)",
        )
        db.execSQL(
            "INSERT INTO `page_fts` (`pageId`, `transcript`) " +
                "SELECT `id`, `transcript` FROM `pages` WHERE `transcript` IS NOT NULL",
        )
    }
}

/** v9 → v10: bind recordings to the Ncode address so audio survives a page being recreated. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `recordings` ADD COLUMN `addressKey` TEXT NOT NULL DEFAULT ''")
    }
}

/** v10 → v11: index the export outbox's drain order (peek sorts by enqueuedAt on every drain). */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_enqueuedAt` ON `outbox` (`enqueuedAt`)")
    }
}

/**
 * v11 → v12: add the `pending_remote_deletes` durable queue (mirrors the v10→v11 outbox index) so
 * "also delete the exported copy" survives an unreachable sync target instead of silently stranding
 * the remote copy forever.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_remote_deletes` (`pageId` TEXT NOT NULL, " +
                "`basePath` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL, " +
                "`attempts` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`pageId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_remote_deletes_enqueuedAt` " +
                "ON `pending_remote_deletes` (`enqueuedAt`)",
        )
    }
}

val MIGRATION_12_13 = object : Migration(SCHEMA_VERSION_12, SCHEMA_VERSION_13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_local_delete_cleanup` (`id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `target` TEXT NOT NULL, `enqueuedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_local_delete_cleanup_enqueuedAt` " +
                "ON `pending_local_delete_cleanup` (`enqueuedAt`)",
        )
    }
}

private const val SCHEMA_VERSION_12 = 12
private const val SCHEMA_VERSION_13 = 13
