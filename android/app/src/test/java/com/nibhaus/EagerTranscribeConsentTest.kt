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
 * the eager Tier-0 transcription pass (backlog catch-up +
 * settle watcher, both funneled through [InkViewModel.startEagerTranscription]) must honor the SAME
 * first-use on-device-OCR acknowledgement gate the manual "Transcribe on device" flow enforces
 * ([SettingsStore.onDeviceOcrAcknowledged]), and the on-device OCR master switch
 * ([SettingsStore.onDeviceOcrEnabled]), otherwise the ML Kit model download can fire in the
 * background with zero consent. These tests exercise the backlog catch-up pass (the simplest,
 * deterministic entry point into the shared gate; see [EagerTranscribeCancelTest]'s doc for why
 * the settle-watcher path isn't separately exercised here).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EagerTranscribeConsentTest {

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

    private fun vmWithBacklog(settings: SettingsStore, calledPages: MutableList<String>): InkViewModel {
        val pageDao = FakePageDao()
        pageDao.byId["pageA"] = page("pageA", lastInkAt = 100L)
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = pageDao,
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val transcribeFn: suspend (String, Boolean) -> String? = { pageId, _ ->
            calledPages += pageId
            "transcribed $pageId"
        }
        return InkViewModel(
            repo = repo,
            settings = settings,
            pen = PenDeps(penManager = penMgr(kotlinx.coroutines.CoroutineScope(testDispatcher)), scanner = PenScanner()),
            ocr = OcrDeps(transcribeOnDevice = transcribeFn),
        )
    }

    @Test fun `eager transcription runs nothing when on-device OCR is not yet acknowledged`() = runTest {
        val s = settings() // default: not acknowledged, on-device OCR enabled
        val calledPages = mutableListOf<String>()
        val vm = vmWithBacklog(s, calledPages)

        vm.startEagerTranscription()
        testScheduler.advanceUntilIdle()

        assertThat(calledPages).isEmpty()
    }

    @Test fun `eager transcription runs the backlog once the disclosure is acknowledged`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        val calledPages = mutableListOf<String>()
        val vm = vmWithBacklog(s, calledPages)

        vm.startEagerTranscription()
        testScheduler.advanceUntilIdle()

        assertThat(calledPages).containsExactly("pageA")
    }

    @Test fun `the on-device OCR off switch wins even when the disclosure is acknowledged`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        s.setOnDeviceOcrEnabled(false)
        val calledPages = mutableListOf<String>()
        val vm = vmWithBacklog(s, calledPages)

        vm.startEagerTranscription()
        testScheduler.advanceUntilIdle()

        assertThat(calledPages).isEmpty()
    }
}
