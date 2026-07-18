package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.organize.AutoOrganizer
import com.nibhaus.pen.NcodeAddress
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutoOrganizerTest {

    private val ids = AtomicInteger(0)
    private fun organizer(): Triple<AutoOrganizer, FakeNotebookDao, FakePageDao> {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        return Triple(
            AutoOrganizer(nb, pg, now = { 1L }, newId = { "id-${ids.incrementAndGet()}" }),
            nb, pg,
        )
    }

    @Test
    fun `first page of a book auto-creates exactly one notebook`() = runTest {
        val (org, nb, _) = organizer()
        org.ensurePage(NcodeAddress(3, 27, 603, 1))
        org.ensurePage(NcodeAddress(3, 27, 603, 2))
        assertThat(nb.byId.values.map { it.book }).containsExactly(603)
        assertThat(nb.byId.values.first().title).isEqualTo("Professional notebook")
    }

    @Test
    fun `same address is idempotent - no duplicate pages`() = runTest {
        val (org, _, pg) = organizer()
        val a = org.ensurePage(NcodeAddress(3, 27, 603, 5))
        val b = org.ensurePage(NcodeAddress(3, 27, 603, 5))
        assertThat(a.id).isEqualTo(b.id)
        assertThat(pg.byId).hasSize(1)
    }

    @Test
    fun `different books file into separate notebooks automatically`() = runTest {
        val (org, nb, _) = organizer()
        org.ensurePage(NcodeAddress(3, 27, 601, 1))
        org.ensurePage(NcodeAddress(3, 27, 605, 1))
        assertThat(nb.byId.values.map { it.book }).containsExactly(601, 605)
    }

    @Test
    fun `pages retain their full addresses while grouping by book`() = runTest {
        val (org, nb, pg) = organizer()

        val first = org.ensurePage(NcodeAddress(1, 11, 603, 9))
        val second = org.ensurePage(NcodeAddress(2, 22, 603, 10))

        assertThat(nb.byId).hasSize(1)
        assertThat(first.notebookId).isEqualTo(second.notebookId)
        assertThat(pg.byId.values.map { it.section }).containsExactly(1, 2).inOrder()
        assertThat(pg.byId.values.map { it.owner }).containsExactly(11, 22).inOrder()
        assertThat(pg.byId.values.map { it.page }).containsExactly(9, 10).inOrder()
        assertThat(pg.byId.values.map { it.addressKey })
            .containsExactly("1.11.603.9", "2.22.603.10")
            .inOrder()
    }

    @Test
    fun `interleaved books reuse their own active notebook and preserve arrival ordering`() = runTest {
        val (org, nb, pg) = organizer()

        val a1 = org.ensurePage(NcodeAddress(3, 27, 601, 8))
        val b1 = org.ensurePage(NcodeAddress(3, 27, 605, 4))
        val a2 = org.ensurePage(NcodeAddress(3, 27, 601, 2))

        assertThat(nb.byId.values.map { it.book }).containsExactly(601, 605).inOrder()
        assertThat(a2.notebookId).isEqualTo(a1.notebookId)
        assertThat(a2.notebookId).isNotEqualTo(b1.notebookId)
        assertThat(pg.byId.values.map { it.page }).containsExactly(8, 4, 2).inOrder()
    }

    @Test
    fun `existing page is returned without changing first-seen metadata`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        var clock = 10L
        val org = AutoOrganizer(nb, pg, now = { clock }, newId = { "stable-id" })
        val address = NcodeAddress(3, 27, 603, 7)

        val first = org.ensurePage(address, inkAt = 20L)
        clock = 99L
        val again = org.ensurePage(address, inkAt = 30L)

        assertThat(again).isEqualTo(first)
        assertThat(pg.byId.getValue(first.id).firstSeenAt).isEqualTo(10L)
        assertThat(pg.byId.getValue(first.id).lastInkAt).isEqualTo(30L)
    }

    @Test
    fun `non-ink lookup does not reorder an existing page by touching it`() = runTest {
        val (org, _, pg) = organizer()
        val page = org.ensurePage(NcodeAddress(3, 27, 603, 1), inkAt = 50L)

        org.ensurePage(NcodeAddress(3, 27, 603, 1), inkAt = 500L, touch = false)

        assertThat(pg.byId.getValue(page.id).lastInkAt).isEqualTo(50L)
    }

    @Test
    fun `finishing a notebook starts a fresh instance instead of overlapping the reused model`() = runTest {
        val (org, nb, pg) = organizer()
        val first = org.ensurePage(NcodeAddress(3, 27, 603, 1))

        org.finishNotebook(first.notebookId)
        // Same Ncode address comes back (a new physical notebook of the same model).
        val second = org.ensurePage(NcodeAddress(3, 27, 603, 1))

        // It must NOT route onto the finished notebook's page — that was the official-app bug.
        assertThat(second.notebookId).isNotEqualTo(first.notebookId)
        assertThat(nb.byId.values.filter { it.book == 603 }.map { it.instanceSeq })
            .containsExactly(0, 1)
        assertThat(nb.byId.values.first { it.instanceSeq == 1 }.title).isEqualTo("Professional notebook #2")
        assertThat(pg.byId).hasSize(2)
    }

    @Test
    fun `finishing one model does not rotate another model`() = runTest {
        val (org, nb, _) = organizer()
        val finished = org.ensurePage(NcodeAddress(3, 27, 603, 1))
        val other = org.ensurePage(NcodeAddress(3, 27, 605, 1))

        org.finishNotebook(finished.notebookId)
        val otherAgain = org.ensurePage(NcodeAddress(4, 99, 605, 2))
        val replacement = org.ensurePage(NcodeAddress(3, 27, 603, 1))

        assertThat(otherAgain.notebookId).isEqualTo(other.notebookId)
        assertThat(replacement.notebookId).isNotEqualTo(finished.notebookId)
        assertThat(nb.byId.values.filter { it.book == 605 }).hasSize(1)
        assertThat(nb.byId.values.filter { it.book == 603 }.map { it.instanceSeq })
            .containsExactly(0, 1)
            .inOrder()
    }
}
