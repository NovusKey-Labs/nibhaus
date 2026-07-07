package com.nibhaus.export

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.Point
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyBackupTest {

    private class FakeProvider(override val id: String = "safety") : StorageProvider {
        val writes = LinkedHashMap<String, ByteArray>()
        val deletes = mutableListOf<String>()
        var failNextWrites = 0
        override suspend fun write(name: String, bytes: ByteArray) {
            if (failNextWrites > 0) { failNextWrites--; throw IOException("fail") }
            writes[name] = bytes
        }
        override suspend fun delete(name: String) { deletes += name }
    }

    private fun page(strokeCount: Int) = PageBackup(
        section = 3, owner = 27, book = 438, page = 9,
        strokes = (1..strokeCount).map {
            BackupStroke(
                color = 0xFF000000.toInt(), width = 1f,
                points = listOf(Point(it.toFloat(), it.toFloat(), 1f, it.toLong())),
            )
        },
    )

    @Test fun `coalesces rapid changes into one write`() = runTest {
        val provider = FakeProvider()
        val backup = SafetyBackup({ provider }, { page(2) }, this, debounceMs = 50)
        backup.onPageChanged("p1")
        backup.onPageChanged("p1")
        backup.onPageChanged("p1")
        advanceUntilIdle()
        assertThat(provider.writes).hasSize(1)
    }

    @Test fun `writes a backup holding all the page's strokes`() = runTest {
        val provider = FakeProvider()
        val backup = SafetyBackup({ provider }, { page(3) }, this, debounceMs = 50)
        backup.onPageChanged("p1")
        advanceUntilIdle()
        val bytes = provider.writes["3.27.438.9.bak.json"]!!
        assertThat(decodeBackup(bytes.decodeToString())!!.strokes).hasSize(3)
    }

    @Test fun `no folder set skips the backup entirely`() = runTest {
        var reads = 0
        val backup = SafetyBackup({ null }, { reads++; page(2) }, this, debounceMs = 50)
        backup.onPageChanged("p1")
        advanceUntilIdle()
        assertThat(reads).isEqualTo(0)
    }

    @Test fun `a failed write is retried on the next change`() = runTest {
        val provider = FakeProvider().apply { failNextWrites = 1 }
        val backup = SafetyBackup({ provider }, { page(2) }, this, debounceMs = 50)
        backup.onPageChanged("p1"); advanceUntilIdle()
        assertThat(provider.writes).isEmpty()
        backup.onPageChanged("p1"); advanceUntilIdle()
        assertThat(provider.writes).hasSize(1)
    }

    @Test fun `different pages produce different files`() = runTest {
        val provider = FakeProvider()
        val backup = SafetyBackup(
            { provider },
            { id -> if (id == "p1") page(1) else page(1).copy(page = 10) },
            this, debounceMs = 50,
        )
        backup.onPageChanged("p1")
        backup.onPageChanged("p2")
        advanceUntilIdle()
        assertThat(provider.writes.keys).containsExactly("3.27.438.9.bak.json", "3.27.438.10.bak.json")
    }

    @Test fun `an emptied page deletes its backup file`() = runTest {
        val provider = FakeProvider()
        val backup = SafetyBackup({ provider }, { page(0) }, this, debounceMs = 50)
        backup.onPageChanged("p1")
        advanceUntilIdle()
        assertThat(provider.deletes).containsExactly("3.27.438.9.bak.json")
        assertThat(provider.writes).isEmpty()
    }

    @Test fun `cancel stops a pending flush from writing`() = runTest {
        val provider = FakeProvider()
        val backup = SafetyBackup({ provider }, { page(2) }, this, debounceMs = 50)
        backup.onPageChanged("p1")
        backup.cancel("p1")
        advanceUntilIdle()
        assertThat(provider.writes).isEmpty()
        assertThat(provider.deletes).isEmpty()
    }
}
