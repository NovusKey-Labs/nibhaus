package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.SettingsStore
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.premiumapi.VlmDownloadState
import com.nibhaus.repo.NoteRepository
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.OcrDeps
import com.nibhaus.ui.PenDeps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Unit tests for the [InkViewModel.OcrProgress] derived StateFlow and [InkViewModel.cancelOcr].
 * No device required: plain JVM + coroutines test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrProgressTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ---- helpers ----

    /**
     * A real (file-backed) DataStore, but explicitly run on [testDispatcher]'s own scheduler
     * instead of its default `Dispatchers.IO`-backed scope. `PreferenceDataStoreFactory` serializes
     * every read/write through an internal coroutine on whatever [CoroutineScope] it's given; left
     * at its default, that's a real background thread that `advanceUntilIdle()` — which only drains
     * [testDispatcher]'s virtual-time scheduler — can't wait for, so a `Flow.first()` read inside a
     * `viewModelScope.launch {}` (e.g. [InkViewModel.doTranscribe]) can occasionally still be
     * in-flight when an assertion runs right after `advanceUntilIdle()`. Pointing DataStore's
     * internal scope at [testDispatcher] puts its work on the *same* scheduler as everything else
     * in the test, so `advanceUntilIdle()` deterministically waits for it too.
     */
    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
            tmp.newFile("test_${System.nanoTime()}.preferences_pb")
        }
    )

    private fun repo() = NoteRepository(
        notebookDao = FakeNotebookDao(),
        pageDao = FakePageDao(),
        strokeDao = FakeStrokeDao(),
        outboxDao = FakeOutboxDao(),
        recordingDao = FakeRecordingDao(),
        tagDao = FakeTagDao(),
    )

    private fun penMgr(scope: kotlinx.coroutines.CoroutineScope) = PenConnectionManager(
        sdk = FakeNeoPenSdk(),
        prefs = InMemoryPenPrefs(),
        scope = scope,
        onDot = {},
    )

    private fun makeVm(
        settings: SettingsStore = settings(),
        vlmState: Flow<VlmDownloadState>? = null,
        scope: kotlinx.coroutines.CoroutineScope,
        transcribeOnDevice: (suspend (String, Boolean) -> String?)? = null,
    ) = InkViewModel(
        repo = repo(),
        settings = settings,
        pen = PenDeps(penManager = penMgr(scope), scanner = PenScanner()),
        ocr = OcrDeps(premiumPresent = true, vlmState = vlmState, transcribeOnDevice = transcribeOnDevice),
    )

    // ---- tests ----

    @Test fun `ocrProgress starts as Idle`() = runTest {
        val vm = makeVm(scope = backgroundScope)
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Idle)
    }

    @Test fun `ocrProgress becomes Transcribing while inference is blocked`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        val barrier = CompletableDeferred<Unit>()
        val vm = makeVm(settings = s, scope = backgroundScope) { _, _ ->
            barrier.await()
            "text"
        }
        vm.openPage("page-1")

        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Idle)

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        // barrier.await() is blocking — _transcribing should be true → Transcribing
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Transcribing)

        // clean up
        barrier.complete(Unit)
        testScheduler.advanceUntilIdle()
    }

    @Test fun `cancelOcr stops the job and ocrProgress returns to Idle`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        val barrier = CompletableDeferred<Unit>()
        val vm = makeVm(settings = s, scope = backgroundScope) { _, _ ->
            barrier.await()
            "text"
        }
        vm.openPage("page-1")
        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        // Confirm in-flight
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Transcribing)

        vm.cancelOcr()
        testScheduler.advanceUntilIdle()

        // After cancel: Idle (no error toast)
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Idle)
        // exportStatus should be null (no error message on cancel)
        assertThat(vm.exportStatus.value).isNull()
    }

    @Test fun `ocrProgress maps vlmModelState Downloading to Downloading when transcribing`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        val vlmFlow = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
        val barrier = CompletableDeferred<Unit>()

        val vm = makeVm(settings = s, vlmState = vlmFlow, scope = backgroundScope) { _, _ ->
            vlmFlow.value = VlmDownloadState.Downloading(42)
            barrier.await()
            "text"
        }
        vm.openPage("page-1")
        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        // vlmFlow emits Downloading(42) just before barrier.await() suspends
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Downloading(42))

        // clean up
        vm.cancelOcr()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Idle)
    }

    @Test fun `ocrProgress shows indeterminate Downloading when pct is -1`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        val vlmFlow = MutableStateFlow<VlmDownloadState>(VlmDownloadState.Idle)
        val barrier = CompletableDeferred<Unit>()

        val vm = makeVm(settings = s, vlmState = vlmFlow, scope = backgroundScope) { _, _ ->
            vlmFlow.value = VlmDownloadState.Downloading(-1)  // size unknown
            barrier.await()
            "text"
        }
        vm.openPage("page-1")
        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        val state = vm.ocrProgress.value
        assertThat(state).isInstanceOf(InkViewModel.OcrProgress.Downloading::class.java)
        assertThat((state as InkViewModel.OcrProgress.Downloading).pct).isEqualTo(-1)

        vm.cancelOcr()
        testScheduler.advanceUntilIdle()
    }

    @Test fun `ocrProgress returns to Idle after successful transcription`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        val vm = makeVm(settings = s, scope = backgroundScope) { _, _ -> "hello world" }
        vm.openPage("page-1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        // After completion the progress is gone (Idle), status briefly shows success
        assertThat(vm.ocrProgress.value).isEqualTo(InkViewModel.OcrProgress.Idle)
    }
}
