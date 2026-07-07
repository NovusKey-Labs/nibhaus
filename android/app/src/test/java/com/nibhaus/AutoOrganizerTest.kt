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
}
