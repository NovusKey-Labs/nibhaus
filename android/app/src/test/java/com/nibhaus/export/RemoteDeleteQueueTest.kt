package com.nibhaus.export

import com.google.common.truth.Truth.assertThat
import com.nibhaus.FakePendingRemoteDeleteDao
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * A target whose delete() can be told to fail (simulating a connection-refused/timeout NAS, or an old
 * server that doesn't implement DELETE yet — HTTP 501/405) or succeed. [StorageProvider]'s real
 * implementations already collapse "2xx" and "404 = already gone" into "delete() returns normally"
 * (see TailscalePushProvider) — so from this queue's perspective, a genuine success and "it was already
 * gone" look identical: both just don't throw.
 */
private class FakeDeleteProvider(override val id: String = "fake") : StorageProvider {
    val deleted = mutableListOf<String>()
    var failing = false
    override suspend fun write(name: String, bytes: ByteArray) {}
    override suspend fun delete(name: String) {
        if (failing) throw IOException("connection refused")
        deleted += name
    }
}

class RemoteDeleteQueueTest {

    private val dao = FakePendingRemoteDeleteDao()
    private val queue = RemoteDeleteQueue(dao, now = { 1_000L })

    @Test fun `an empty queue drains as done`() = runTest {
        assertThat(queue.drain(FakeDeleteProvider())).isTrue()
    }

    @Test fun `success removes every artifact for the page and clears its row`() = runTest {
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")
        val provider = FakeDeleteProvider()

        val ok = queue.drain(provider)

        assertThat(ok).isTrue()
        assertThat(provider.deleted).containsExactly(
            "pnb/Work/PNB_Work_Pg038.svg", "pnb/Work/PNB_Work_Pg038.inkml",
            "pnb/Work/PNB_Work_Pg038.png", "pnb/Work/PNB_Work_Pg038.pdf",
            "pnb/Work/PNB_Work_Pg038.md", "pnb/Work/PNB_Work_Pg038.json",
            "pnb/Work/PNB_Work_Pg038.bak.json", "pnb/Work/PNB_Work_Pg038.txt",
        )
        assertThat(dao.peek(10)).isEmpty()
    }

    @Test fun `already gone (404) counts as done, same as a fresh success`() = runTest {
        // A provider that never throws is exactly what a 404 response looks like from here — the real
        // TailscalePushProvider swallows 404 internally and returns normally, same as 2xx.
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")

        assertThat(queue.drain(FakeDeleteProvider())).isTrue()
        assertThat(dao.peek(10)).isEmpty()
    }

    @Test fun `an unreachable target keeps the row queued for retry, not dropped`() = runTest {
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")
        val unreachable = FakeDeleteProvider().apply { failing = true }

        val ok = queue.drain(unreachable)

        assertThat(ok).isFalse()
        assertThat(dao.peek(10).map { it.pageId }).containsExactly("P1")
        assertThat(dao.rows.getValue("P1").attempts).isEqualTo(1)
    }

    @Test fun `an old server (501 or 405 for DELETE) is retried, never treated as permanent failure`() = runTest {
        // The queue has exactly two outcomes: success (row removed) or retry (row stays, attempts
        // bumped) — there is no "give up" path, so a 501/405 an un-upgraded server returns is retried
        // forever just like a plain connection failure, until the server starts confirming.
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")
        val oldServer = FakeDeleteProvider().apply { failing = true }
        queue.drain(oldServer)
        assertThat(dao.peek(10)).isNotEmpty()

        oldServer.failing = false // the server gets upgraded / comes back
        assertThat(queue.drain(oldServer)).isTrue()
        assertThat(dao.peek(10)).isEmpty()
    }

    @Test fun `one page failing does not block another page's delete from completing`() = runTest {
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")
        queue.enqueue("P2", "pnb/Home/PNB_Home_Pg002")
        val selective = object : StorageProvider {
            override val id = "selective"
            override suspend fun write(name: String, bytes: ByteArray) {}
            override suspend fun delete(name: String) {
                if (name.startsWith("pnb/Work/")) throw IOException("connection refused")
            }
        }

        val ok = queue.drain(selective)

        assertThat(ok).isFalse()
        assertThat(dao.peek(10).map { it.pageId }).containsExactly("P1") // P2 cleared, P1 retried
    }

    @Test fun `a retry redoes every extension for a page, which is safe because delete is idempotent`() = runTest {
        queue.enqueue("P1", "pnb/Work/PNB_Work_Pg038")
        var failTxt = true
        val flaky = object : StorageProvider {
            override val id = "flaky"
            override suspend fun write(name: String, bytes: ByteArray) {}
            override suspend fun delete(name: String) {
                if (failTxt && name.endsWith(".txt")) throw IOException("connection refused")
            }
        }

        assertThat(queue.drain(flaky)).isFalse() // fails partway through P1's extensions
        failTxt = false
        assertThat(queue.drain(flaky)).isTrue()  // retried from the top; re-deleting earlier ones is a no-op

        assertThat(dao.peek(10)).isEmpty()
    }
}
