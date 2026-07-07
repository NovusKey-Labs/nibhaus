package com.nibhaus

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.InkDatabase
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation that the Room schema actually opens and the atomic ingest transaction
 * (stroke + outbox in one go) behaves on a real Android image. This is the kind of check the
 * emulator job covers that JVM unit tests cannot — everything except the physical pen radio.
 */
@RunWith(AndroidJUnit4::class)
class RoomInstrumentedTest {

    private lateinit var db: InkDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            InkDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun commitStroke_persistsStrokeAndEnqueuesOutbox_inOneTransaction() = runTest {
        val stroke = StrokeEntity(
            uuid = "u1", pageId = "p1", color = 0, startedAt = 1, endedAt = 2,
            pointsJson = "[]", syncState = SyncState.PENDING,
        )

        db.ingestDao().commitStroke(stroke, pageKey = "3.27.603.1")

        assertThat(db.strokeDao().byUuids(listOf("u1"))).hasSize(1)
        assertThat(db.outboxDao().peek(10).map { it.strokeUuid }).containsExactly("u1")
    }
}
