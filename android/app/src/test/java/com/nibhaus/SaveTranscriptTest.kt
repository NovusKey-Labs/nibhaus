package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.SettingsStore
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.repo.NoteRepository
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.OcrDeps
import com.nibhaus.ui.PenDeps
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
 * [InkViewModel.saveTranscript] must (a) normalize the edit ([InkViewModel
 * .normalizeTranscriptEdit] — see [NormalizeTranscriptEditTest] for its own pure-logic coverage) and
 * (b) invoke the injected `saveTranscriptOp` funnel with the open page's id — mirroring how
 * [OcrSettingsTest] verifies `transcribeOnDevice` wiring, so the DAO/FTS funnel itself
 * ([com.nibhaus.data.PageDao.setTranscriptIndexed]) doesn't need a real Room database here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveTranscriptTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create { tmp.newFile("test_${System.nanoTime()}.preferences_pb") }
    )

    @Test fun `saveTranscript invokes the funnel with the open page id and trimmed text`() = runTest {
        var capturedPageId: String? = null
        var capturedText: String? = null

        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = FakePageDao(),
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val penMgr = PenConnectionManager(
            sdk = FakeNeoPenSdk(), prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
        )
        val vm = InkViewModel(
            repo = repo,
            settings = settings(),
            pen = PenDeps(penManager = penMgr, scanner = PenScanner()),
            ocr = OcrDeps(saveTranscriptOp = { pageId, text -> capturedPageId = pageId; capturedText = text }),
        )

        vm.saveTranscript("page-1", "  corrected text  ")
        testScheduler.advanceUntilIdle()

        assertThat(capturedPageId).isEqualTo("page-1")
        assertThat(capturedText).isEqualTo("corrected text")
    }

    @Test fun `saveTranscript with a blank edit passes an empty string (clears the transcript)`() = runTest {
        var capturedText: String? = "not yet called"

        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = FakePageDao(),
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val penMgr = PenConnectionManager(
            sdk = FakeNeoPenSdk(), prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
        )
        val vm = InkViewModel(
            repo = repo,
            settings = settings(),
            pen = PenDeps(penManager = penMgr, scanner = PenScanner()),
            ocr = OcrDeps(saveTranscriptOp = { _, text -> capturedText = text }),
        )

        vm.saveTranscript("page-1", "   ")
        testScheduler.advanceUntilIdle()

        assertThat(capturedText).isEmpty()
    }
}
