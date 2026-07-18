package com.nibhaus.export

import com.nibhaus.data.PendingLocalDeleteCleanupDao

/** Drains deletion side effects that cannot participate in the Room transaction. */
class LocalDeleteCleanupQueue(
    private val dao: PendingLocalDeleteCleanupDao,
    private val clean: suspend (kind: String, target: String) -> Unit,
) {
    suspend fun drain(): Boolean {
        var allOk = true
        dao.peek(Int.MAX_VALUE).forEach { entry ->
            val result = runCatching { clean(entry.kind, entry.target) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (result.isSuccess) dao.remove(entry.id) else allOk = false
        }
        return allOk
    }

    companion object {
        const val RECORDING_FILE = "recording_file"
        const val SAFETY_BACKUP = "safety_backup"
        const val BOOKMARK = "bookmark"
        const val NOTEBOOK_ACCENT = "notebook_accent"
    }
}
