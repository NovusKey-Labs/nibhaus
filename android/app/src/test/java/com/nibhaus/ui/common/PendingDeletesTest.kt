package com.nibhaus.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** #6 soft-delete + UNDO: the pure schedule/cancel/fire state machine, tested without coroutines. */
class PendingDeletesTest {

    @Test fun `not pending before schedule`() {
        val d = PendingDeletes<String>()
        assertThat(d.isPending("a")).isFalse()
        assertThat(d.pending).isEmpty()
    }

    @Test fun `schedule marks an id pending`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        assertThat(d.isPending("a")).isTrue()
        assertThat(d.pending).containsExactly("a")
    }

    @Test fun `schedule is idempotent for an already-pending id`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.schedule("a")
        assertThat(d.pending).containsExactly("a")
    }

    @Test fun `multiple ids can be pending at once`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.schedule("b")
        assertThat(d.pending).containsExactly("a", "b")
    }

    @Test fun `cancel undoes a pending delete and reports it was pending`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        assertThat(d.cancel("a")).isTrue()
        assertThat(d.isPending("a")).isFalse()
        assertThat(d.pending).isEmpty()
    }

    @Test fun `cancel on an id that was never scheduled is a safe no-op`() {
        val d = PendingDeletes<String>()
        assertThat(d.cancel("ghost")).isFalse()
    }

    @Test fun `cancel only affects the named id, others stay pending`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.schedule("b")
        d.cancel("a")
        assertThat(d.pending).containsExactly("b")
    }

    @Test fun `fire runs the real delete and clears pending state`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        assertThat(d.fire("a")).isTrue()
        assertThat(d.isPending("a")).isFalse()
    }

    @Test fun `fire after cancel is a no-op and must not delete`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.cancel("a")
        assertThat(d.fire("a")).isFalse()
    }

    @Test fun `fire on an id never scheduled is a no-op`() {
        val d = PendingDeletes<String>()
        assertThat(d.fire("ghost")).isFalse()
    }

    @Test fun `cancel after fire is a no-op (already deleted)`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.fire("a")
        assertThat(d.cancel("a")).isFalse()
    }

    @Test fun `re-scheduling the same id after it fired starts a fresh pending window`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        d.fire("a")
        d.schedule("a")
        assertThat(d.isPending("a")).isTrue()
    }

    // Regression (#6 live-testing bug): InkViewModel publishes `pending` into a MutableStateFlow after
    // every schedule/cancel/fire — e.g. `_pendingDeletedNotebookIds.value = pendingNotebookDeletes.pending`.
    // MutableStateFlow.value's setter conflates by equals() and, critically, by REFERENCE — assigning
    // the exact same object it already holds is a same-value no-op that never notifies collectors. If
    // `pending` returned a live view of the internal set, the FIRST schedule()/cancel()/fire() call in
    // a session would still publish correctly (the flow's initial value is a distinct emptySet()), but
    // every call after that would silently fail to notify Compose — so the tile would keep rendering
    // until some unrelated recomposition happened to occur, matching the reported "confirm-delete a
    // notebook -> visible lag before the tile disappears" (the SECOND+ delete of a session never
    // actually signalled the UI). `pending` must hand back an immutable snapshot every time.
    @Test fun `pending returns a snapshot, unaffected by mutations made after it was read`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        val snapshotAfterFirstSchedule = d.pending

        d.schedule("b")

        assertThat(snapshotAfterFirstSchedule).containsExactly("a")
        assertThat(d.pending).containsExactly("a", "b")
    }

    @Test fun `two reads of pending with no mutation in between are equal (no spurious StateFlow emission)`() {
        val d = PendingDeletes<String>()
        d.schedule("a")
        assertThat(d.pending).isEqualTo(d.pending)
    }
}
