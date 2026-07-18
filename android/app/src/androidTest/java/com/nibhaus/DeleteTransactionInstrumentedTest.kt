package com.nibhaus

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.InkDatabase
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.OutboxEntry
import com.nibhaus.data.PageDeletionPlan
import com.nibhaus.data.PageEntity
import com.nibhaus.data.PendingRemoteDelete
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeleteTransactionInstrumentedTest {
    private lateinit var db: InkDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), InkDatabase::class.java).build()
    }
    @After fun tearDown() = db.close()

    @Test fun injectedFailureRollsBackLocalRowsAndRemoteDeleteEnqueue() = runTest {
        db.notebookDao().insert(NotebookEntity("n1", 1, 0, false, "N", 1))
        db.pageDao().insert(PageEntity("p1", "n1", "1.1.1.1", 1, 1, 1, 1, 1, 1))
        db.strokeDao().insert(StrokeEntity("s1", "p1", 0, 1, 2, "[]", SyncState.PENDING))
        db.outboxDao().enqueue(OutboxEntry("s1", 1))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_page_delete BEFORE DELETE ON pages BEGIN SELECT RAISE(ABORT, 'injected'); END",
        )

        runCatching {
            db.deleteDao().deletePages(
                listOf(PageDeletionPlan("p1", PendingRemoteDelete("p1", "remote/p1", 1), emptyList(), false)),
            )
        }

        assertThat(db.pageDao().byId("p1")).isNotNull()
        assertThat(db.strokeDao().byUuids(listOf("s1"))).hasSize(1)
        assertThat(db.outboxDao().peek(10)).hasSize(1)
        assertThat(db.pendingRemoteDeleteDao().peek(10)).isEmpty()
    }
}
