package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.StrokeEntity
import com.nibhaus.data.SyncState
import com.nibhaus.data.PendingRemoteDelete
import com.nibhaus.edit.StrokeEditor
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Undo must revert the last *edit*, not delete the last stroke (the on-device complaint). */
class StrokeEditorTest {

    private fun stroke(uuid: String, color: Int = 0, width: Float = 1f) = StrokeEntity(
        uuid = uuid, pageId = "p1", color = color, startedAt = 0, endedAt = 1,
        pointsJson = "[]", syncState = SyncState.SYNCED, width = width,
    )

    @Test
    fun `undo reverts a recolor and keeps the stroke`() = runTest {
        val strokes = FakeStrokeDao().apply { byId["a"] = stroke("a", color = 0) }
        val editor = StrokeEditor(strokes, FakeOutboxDao())

        editor.recolor(listOf("a"), 0xFFFF0000.toInt(), "p1")
        assertThat(strokes.byId["a"]!!.color).isEqualTo(0xFFFF0000.toInt())

        editor.undo("p1")
        assertThat(strokes.byId["a"]).isNotNull()            // NOT deleted
        assertThat(strokes.byId["a"]!!.color).isEqualTo(0)   // color restored
    }

    @Test
    fun `undo restores a deleted stroke`() = runTest {
        val strokes = FakeStrokeDao().apply { byId["a"] = stroke("a") }
        val editor = StrokeEditor(strokes, FakeOutboxDao())

        editor.delete(listOf("a"), "p1")
        assertThat(strokes.byId["a"]).isNull()

        editor.undo("p1")
        assertThat(strokes.byId["a"]).isNotNull()
    }

    @Test
    fun `undo on an unedited page is a harmless no-op`() = runTest {
        val strokes = FakeStrokeDao().apply { byId["a"] = stroke("a") }
        val editor = StrokeEditor(strokes, FakeOutboxDao())

        editor.undo("p1") // nothing to undo
        assertThat(strokes.byId["a"]).isNotNull()
    }

    @Test
    fun `recolor reports the changed pageId`() = runTest {
        val strokes = FakeStrokeDao().apply { byId["a"] = stroke("a", color = 0) }
        var changed: String? = null
        val editor = StrokeEditor(strokes, FakeOutboxDao(), onChanged = { changed = it })

        editor.recolor(listOf("a"), 0xFFFF0000.toInt(), "p1")

        assertThat(changed).isEqualTo("p1")
    }

    @Test fun `failed edit transaction rolls every stroke and outbox change back`() = runTest {
        val strokes = FakeStrokeDao().apply {
            byId["a"] = stroke("a", color = 1)
            byId["b"] = stroke("b", color = 2)
        }
        val outbox = FakeOutboxDao()
        val editor = StrokeEditor(strokes, outbox, transaction = { block ->
            val strokeSnapshot = LinkedHashMap(strokes.byId)
            val outboxSnapshot = LinkedHashMap(outbox.rows)
            try {
                block()
                error("injected commit failure")
            } catch (t: Throwable) {
                strokes.byId.clear(); strokes.byId.putAll(strokeSnapshot)
                outbox.rows.clear(); outbox.rows.putAll(outboxSnapshot)
                throw t
            }
        })

        assertThat(runCatching { editor.recolor(listOf("a", "b"), 9, "p1") }.isFailure).isTrue()
        assertThat(strokes.byId["a"]!!.color).isEqualTo(1)
        assertThat(strokes.byId["b"]!!.color).isEqualTo(2)
        assertThat(outbox.rows).isEmpty()
    }

    @Test fun `deleting last stroke enqueues page artifact deletion`() = runTest {
        val strokes = FakeStrokeDao().apply { byId["a"] = stroke("a") }
        val deletes = FakePendingRemoteDeleteDao()
        val editor = StrokeEditor(
            strokes, FakeOutboxDao(), pendingRemoteDeleteDao = deletes,
            artifactDelete = { PendingRemoteDelete(it, "pnb/Test/Page001", 123L) },
        )

        editor.delete(listOf("a"), "p1")

        assertThat(strokes.byId).isEmpty()
        assertThat(deletes.rows["p1"]!!.basePath).isEqualTo("pnb/Test/Page001")
    }
}
