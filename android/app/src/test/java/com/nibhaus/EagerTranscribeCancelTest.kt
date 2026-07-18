package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.export.SettingsStore
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.repo.NoteRepository
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.OcrDeps
import com.nibhaus.ui.PenDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #9: the TRANSCRIBING badge's cancel affordance. Verifies the eager background pass's job is
 * actually cancellable mid-batch, and that whatever page already finished keeps its result — only
 * the page(s) not yet reached are skipped (and stay eligible for a later pass).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EagerTranscribeCancelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
            tmp.newFile("test_${System.nanoTime()}.preferences_pb")
        }
    )

    private fun page(id: String, lastInkAt: Long) = PageEntity(
        id = id,
        notebookId = "nb",
        addressKey = "1.1.1.$id",
        section = 1,
        owner = 1,
        book = 1,
        page = 1,
        firstSeenAt = 0L,
        lastInkAt = lastInkAt,
        transcript = null,
    )

    private fun penMgr(scope: kotlinx.coroutines.CoroutineScope) = PenConnectionManager(
        sdk = FakeNeoPenSdk(),
        prefs = InMemoryPenPrefs(),
        scope = scope,
        onDot = {},
    )

    @Test fun `cancelling the eager batch keeps the page that already finished and skips the rest`() = runTest {
        val pageDao = FakePageDao()
        // pageA sorts first (higher lastInkAt) — pagesNeedingTranscription() is newest-first — so the
        // backlog pass reaches it before pageB.
        pageDao.byId["pageA"] = page("pageA", lastInkAt = 200L)
        pageDao.byId["pageB"] = page("pageB", lastInkAt = 100L)
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = pageDao,
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )

        val calledPages = mutableListOf<String>()
        val completedPages = mutableListOf<String>()
        val barrier = CompletableDeferred<Unit>()
        val transcribeFn: suspend (String, Boolean) -> String? = { pageId, _ ->
            calledPages += pageId
            if (pageId == "pageB") barrier.await() // blocks so the test can cancel mid-batch
            completedPages += pageId
            "transcribed $pageId"
        }

        // The eager pass now gates on the same first-use on-device-OCR acknowledgement the manual
        // "Transcribe on device" flow requires; acknowledge
        // it here so this test can keep exercising the cancel-mid-batch behavior it's actually about.
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        val vm = InkViewModel(
            repo = repo,
            settings = s,
            pen = PenDeps(penManager = penMgr(backgroundScope), scanner = PenScanner()),
            ocr = OcrDeps(transcribeOnDevice = transcribeFn),
        )

        vm.startEagerTranscription()
        testScheduler.advanceUntilIdle()

        // pageA finished; pageB is the one "in progress" (1-based readout: 2 of 2).
        assertThat(completedPages).containsExactly("pageA")
        assertThat(calledPages).containsExactly("pageA", "pageB")
        assertThat(vm.transcribeProgress.value).isEqualTo(InkViewModel.TranscribeProgress(2, 2))

        vm.cancelEagerTranscription()
        testScheduler.advanceUntilIdle()

        // The badge clears (job actually cancelled)…
        assertThat(vm.transcribeProgress.value).isNull()
        // …and pageB never got to finish — cancelling kept pageA's result instead of discarding it,
        // and skipped pageB rather than racing/corrupting it.
        assertThat(completedPages).containsExactly("pageA")

        barrier.complete(Unit) // cleanup — no-op, the job is already gone
    }

    @Test fun `eager batch stops remaining pages when on-device OCR is reset mid-batch`() = runTest {
        val pageDao = FakePageDao()
        pageDao.byId["pageA"] = page("pageA", lastInkAt = 200L) // newest-first, so pageA runs before pageB
        pageDao.byId["pageB"] = page("pageB", lastInkAt = 100L)
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = pageDao,
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        val calledPages = mutableListOf<String>()
        val transcribeFn: suspend (String, Boolean) -> String? = { pageId, _ ->
            calledPages += pageId
            // Reset the disclosure mid-batch (as the Settings reset can): remaining pages must be skipped.
            if (pageId == "pageA") s.resetOnDeviceOcrDisclosure()
            "transcribed $pageId"
        }
        val vm = InkViewModel(
            repo = repo,
            settings = s,
            pen = PenDeps(penManager = penMgr(backgroundScope), scanner = PenScanner()),
            ocr = OcrDeps(transcribeOnDevice = transcribeFn),
        )

        vm.startEagerTranscription()
        testScheduler.advanceUntilIdle()

        // pageA transcribed, then consent was revoked, so pageB never reaches the engine.
        assertThat(calledPages).containsExactly("pageA")
    }

    @Test fun `cancelEagerTranscription with nothing running is a safe no-op`() = runTest {
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = FakePageDao(),
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val vm = InkViewModel(
            repo = repo,
            settings = settings(),
            pen = PenDeps(penManager = penMgr(backgroundScope), scanner = PenScanner()),
            ocr = OcrDeps(transcribeOnDevice = { _, _ -> "x" }),
        )
        vm.cancelEagerTranscription() // nothing started yet
        assertThat(vm.transcribeProgress.value).isNull()
    }
}
