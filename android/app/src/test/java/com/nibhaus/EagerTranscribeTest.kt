package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.ui.InkViewModel
import org.junit.Test

/**
 * Unit tests for the eager Tier-0 transcription pass's core LOGIC:
 * [InkViewModel.selectPagesToTranscribe] — pure, idempotent selection of which pages still need a
 * transcript (given some pages with transcripts and some without), excluding any already claimed by
 * an in-flight pass. This is the exact function [InkViewModel] calls from both the backlog catch-up
 * pass (on [InkViewModel.startEagerTranscription]) and the settle-debounced watcher, so it captures
 * the trigger/idempotency behavior end-to-end without needing a database or coroutine dispatcher.
 *
 * Deliberately no VM-construction test here: constructing an [InkViewModel] pulls in a real
 * DataStore-backed [com.nibhaus.export.SettingsStore] under `UnconfinedTestDispatcher`, and this
 * suite already has other tests ([OcrProgressTest], [OcrSettingsTest]) with the exact same setup —
 * adding another one destabilizes their real-DataStore-vs-virtual-time race (confirmed independently:
 * their flakiness reproduces even with zero lines of new production code, from constructing extra
 * plain InkViewModels alone). The pure function below is what the production code actually branches
 * on, so it is what breaks — deterministically — if the idempotency logic regresses.
 */
class EagerTranscribeTest {

    private fun page(id: String, transcript: String?, lastInkAt: Long = 0L) = PageEntity(
        id = id,
        notebookId = "nb",
        addressKey = "1.2.3.$id",
        section = 1,
        owner = 2,
        book = 3,
        page = 0,
        firstSeenAt = 0L,
        lastInkAt = lastInkAt,
        transcript = transcript,
    )

    @Test fun `selects pages with a null transcript`() {
        val pages = listOf(page("a", transcript = null))
        assertThat(InkViewModel.selectPagesToTranscribe(pages)).containsExactly("a")
    }

    @Test fun `selects pages with a blank transcript`() {
        val pages = listOf(page("a", transcript = ""), page("b", transcript = "   "))
        assertThat(InkViewModel.selectPagesToTranscribe(pages)).containsExactly("a", "b")
    }

    @Test fun `excludes pages that already have a transcript (idempotent)`() {
        val pages = listOf(page("a", transcript = null), page("b", transcript = "already transcribed"))
        assertThat(InkViewModel.selectPagesToTranscribe(pages)).containsExactly("a")
    }

    @Test fun `excludes pages already claimed by an in-flight pass`() {
        val pages = listOf(page("a", transcript = null), page("b", transcript = null))
        assertThat(InkViewModel.selectPagesToTranscribe(pages, inFlight = setOf("b"))).containsExactly("a")
    }

    @Test fun `returns nothing when every page already has a transcript`() {
        val pages = listOf(page("a", transcript = "done"), page("b", transcript = "also done"))
        assertThat(InkViewModel.selectPagesToTranscribe(pages)).isEmpty()
    }

    @Test fun `mixed backlog selects only the pages that still need a transcript`() {
        val pages = listOf(
            page("needs-1", transcript = null),
            page("has-transcript", transcript = "verbatim text"),
            page("needs-2", transcript = "   "),
        )
        assertThat(InkViewModel.selectPagesToTranscribe(pages)).containsExactly("needs-1", "needs-2")
    }
}
