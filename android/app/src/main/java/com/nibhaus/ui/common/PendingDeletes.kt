package com.nibhaus.ui.common

/**
 * Pure state machine backing the soft-delete + UNDO mechanic (#6): on confirm, [schedule] marks an
 * id as pending so the UI can hide it immediately (optimistic); a caller-owned timer (the 5s UNDO
 * snackbar) later calls [fire] to run the real delete, unless the user taps UNDO first ([cancel]).
 *
 * Owns no coroutines or timers itself — the caller (InkViewModel) drives the actual delay and calls
 * back into this class, so it's trivially unit-testable with plain JUnit. Supports more than one id
 * pending at once (e.g. delete page A, then page B, before A's undo window closes).
 */
class PendingDeletes<T> {
    private val ids = LinkedHashSet<T>()

    /** Ids currently hidden pending a real delete. An immutable SNAPSHOT, not a live view of [ids] —
     *  callers (InkViewModel) publish this into a MutableStateFlow after every schedule/cancel/fire,
     *  and that flow's `.value` setter conflates by equals()/reference: handing back the same mutable
     *  set instance on every call would make every publish AFTER the first one a same-reference no-op
     *  that never notifies collectors (Compose would then only pick up the change whenever some
     *  unrelated recomposition happened to re-read it — the "visible lag" bug). A fresh copy each call
     *  guarantees a real content change is always seen as a new value. */
    val pending: Set<T> get() = ids.toSet()

    fun isPending(id: T): Boolean = id in ids

    /** Mark [id] pending (optimistic hide). Idempotent — scheduling an already-pending id is a no-op;
     *  its existing timer keeps owning when [fire] eventually runs. */
    fun schedule(id: T) {
        ids.add(id)
    }

    /** UNDO: [id] is no longer pending (restore visibility) and the real delete must not run.
     *  @return true if [id] actually was pending (false = its timer already fired, or it was never
     *  scheduled — a stale/duplicate UNDO tap is then a safe no-op). */
    fun cancel(id: T): Boolean = ids.remove(id)

    /** The undo window elapsed for [id]: it's no longer pending.
     *  @return true if the real delete should run now (false = it was already undone — the caller
     *  MUST NOT delete). */
    fun fire(id: T): Boolean = ids.remove(id)
}
