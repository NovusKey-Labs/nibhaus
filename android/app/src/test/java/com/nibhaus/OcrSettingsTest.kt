package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.export.SettingsStore
import com.nibhaus.export.TranscriptionQuality
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OcrSettingsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // Scope pinned to testDispatcher (not DataStore's real-thread default) so a write launched from
    // inside a VM coroutine (e.g. confirmAccuracyDisclaimer's settings.setOcrDisclaimerShown()) is
    // deterministically drained by advanceUntilIdle() instead of racing it on a real background
    // thread — same fix as OcrProgressTest/EagerTranscribeCancelTest's settings() helper.
    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
            tmp.newFile("test_${System.nanoTime()}.preferences_pb")
        }
    )

    // ---- TranscriptionQuality enum (pure, no DataStore) ----

    @Test fun `TranscriptionQuality default is AUTO`() {
        assertThat(TranscriptionQuality.DEFAULT).isEqualTo(TranscriptionQuality.AUTO)
    }

    @Test fun `TranscriptionQuality fromKey round-trips all values`() {
        TranscriptionQuality.entries.forEach { q ->
            assertThat(TranscriptionQuality.fromKey(q.key)).isEqualTo(q)
        }
    }

    @Test fun `TranscriptionQuality fromKey null falls back to AUTO`() {
        assertThat(TranscriptionQuality.fromKey(null)).isEqualTo(TranscriptionQuality.AUTO)
    }

    // ---- New SettingsStore flag defaults ----

    @Test fun `vlmAllowMetered defaults to false`() = runTest {
        assertThat(settings().vlmAllowMetered.first()).isFalse()
    }

    @Test fun `vlmForceOnDevice defaults to false`() = runTest {
        assertThat(settings().vlmForceOnDevice.first()).isFalse()
    }

    @Test fun `ocrDisclaimerShown defaults to false`() = runTest {
        assertThat(settings().ocrDisclaimerShown.first()).isFalse()
    }

    @Test fun `transcriptionQuality defaults to AUTO`() = runTest {
        assertThat(settings().transcriptionQuality.first()).isEqualTo(TranscriptionQuality.AUTO)
    }

    // ---- Accurate routing via InkViewModel ----

    private fun makeAccurateRoutingVm(
        s: SettingsStore,
        scope: CoroutineScope,
        onTranscribe: (String, Boolean) -> String?,
    ): InkViewModel {
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(),
            pageDao = FakePageDao(),
            strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(),
            recordingDao = FakeRecordingDao(),
            tagDao = FakeTagDao(),
        )
        val penMgr = PenConnectionManager(
            sdk = FakeNeoPenSdk(),
            prefs = InMemoryPenPrefs(),
            scope = scope,
            onDot = {},
        )
        return InkViewModel(
            repo = repo,
            settings = s,
            pen = PenDeps(penManager = penMgr, scanner = PenScanner()),
            ocr = OcrDeps(
                premiumPresent = true,
                transcribeOnDevice = { pageId, accurate -> onTranscribe(pageId, accurate) },
            ),
        )
    }

    @Test fun `transcribeCurrentPageAccurate passes accurate=true when disclaimer shown and on-device OCR acknowledged`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()

        var capturedAccurate: Boolean? = null
        var capturedPageId: String? = null
        val vm = makeAccurateRoutingVm(s, backgroundScope) { pageId, accurate ->
            capturedPageId = pageId
            capturedAccurate = accurate
            "ok"
        }

        // Simulate a page being selected.
        vm.openPage("page-1")

        vm.transcribeCurrentPageAccurate()
        // Allow all coroutines to run.
        testScheduler.advanceUntilIdle()

        assertThat(capturedAccurate).isTrue()
        assertThat(capturedPageId).isEqualTo("page-1")
    }

    // ---- On-device OCR (ML Kit download) consent gate on the accurate path: RoutedInk's accurate
    // chain falls back to the instant ML Kit engine whenever no
    // accurate engine is configured or all of them fail, so "Improve transcription" must never reach
    // it without the same first-use disclosure the instant tier requires. ----

    @Test fun `transcribeCurrentPageAccurate does not transcribe and raises the disclosure when on-device OCR is not acknowledged`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        // onDeviceOcrAcknowledged NOT set.

        var called = false
        val vm = makeAccurateRoutingVm(s, backgroundScope) { _, _ -> called = true; "ok" }
        vm.openPage("page-1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        assertThat(called).isFalse()
        assertThat(vm.showOcrDisclosure.value).isTrue()
    }

    @Test fun `confirming the on-device OCR disclosure after transcribeCurrentPageAccurate proceeds with accurate=true`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)

        var capturedAccurate: Boolean? = null
        var capturedPageId: String? = null
        val vm = makeAccurateRoutingVm(s, backgroundScope) { pageId, accurate ->
            capturedPageId = pageId
            capturedAccurate = accurate
            "ok"
        }
        vm.openPage("page-1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()
        assertThat(vm.showOcrDisclosure.value).isTrue()

        vm.confirmOcrDisclosure()
        testScheduler.advanceUntilIdle()

        assertThat(vm.showOcrDisclosure.value).isFalse()
        assertThat(capturedAccurate).isTrue()
        assertThat(capturedPageId).isEqualTo("page-1")
    }

    // ---- shouldAutoRunAccuratePass pure-function matrix ----
    // Tests all 3 quality × (capable=true|false) × (metered=true|false) combinations.
    // This is a pure function — no DataStore or ViewModel needed.

    @Test fun `shouldAutoRunAccuratePass INSTANT is always false`() {
        for (capable in listOf(true, false)) for (metered in listOf(true, false)) {
            assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.INSTANT, capable, metered, entitled = true))
                .isFalse()
        }
    }

    @Test fun `shouldAutoRunAccuratePass ACCURATE true when vlmCapable regardless of metered`() {
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.ACCURATE, vlmCapable = true, isMetered = false, entitled = true)).isTrue()
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.ACCURATE, vlmCapable = true, isMetered = true, entitled = true)).isTrue()
    }

    @Test fun `shouldAutoRunAccuratePass ACCURATE false when not vlmCapable`() {
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.ACCURATE, vlmCapable = false, isMetered = false, entitled = true)).isFalse()
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.ACCURATE, vlmCapable = false, isMetered = true, entitled = true)).isFalse()
    }

    @Test fun `shouldAutoRunAccuratePass AUTO true only when capable and unmetered`() {
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.AUTO, vlmCapable = true,  isMetered = false, entitled = true)).isTrue()
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.AUTO, vlmCapable = true,  isMetered = true, entitled = true )).isFalse()
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.AUTO, vlmCapable = false, isMetered = false, entitled = true)).isFalse()
        assertThat(InkViewModel.shouldAutoRunAccuratePass(TranscriptionQuality.AUTO, vlmCapable = false, isMetered = true, entitled = true )).isFalse()
    }

    @Test fun `shouldAutoRunAccuratePass is always false when not entitled`() {
        for (q in TranscriptionQuality.entries) {
            for (capable in listOf(true, false)) for (metered in listOf(true, false)) {
                assertThat(InkViewModel.shouldAutoRunAccuratePass(q, capable, metered, entitled = false))
                    .isFalse()
            }
        }
    }

    // ---- VM-level integration: auto-accurate pass triggered by quality setting ----

    private fun makeVm(
        s: SettingsStore,
        scope: kotlinx.coroutines.CoroutineScope,
        vlmCapable: Boolean = false,
        metered: Boolean = false,
        onTranscribe: (suspend (String, Boolean) -> String?)? = null,
    ) = InkViewModel(
        repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = FakePageDao(), strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        ),
        settings = s,
        pen = PenDeps(
            penManager = PenConnectionManager(
                sdk = FakeNeoPenSdk(), prefs = InMemoryPenPrefs(), scope = scope, onDot = {},
            ),
            scanner = PenScanner(),
        ),
        // vlmState non-null ↔ VLM-capable build (the proxy InkViewModel uses for vlmCapable)
        ocr = OcrDeps(
            premiumPresent = true,
            vlmState = if (vlmCapable) kotlinx.coroutines.flow.flowOf(com.nibhaus.premiumapi.VlmDownloadState.Ready) else null,
            isMetered = { metered },
            transcribeOnDevice = onTranscribe,
        ),
    )

    @Test fun `auto accurate pass fires when quality=ACCURATE, disclaimerShown, vlmCapable`() = runTest {
        val s = settings()
        // Acknowledge both the on-device OCR first-use notice and the accuracy disclaimer so the
        // transcription path runs without any dialog intercepts.
        s.acknowledgeOnDeviceOcr()
        s.setPremiumUnlocked(true)
        s.setOcrDisclaimerShown()
        s.setTranscriptionQuality(TranscriptionQuality.ACCURATE)

        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = false) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        // Instant pass (false) auto-chained with accurate pass (true).
        assertThat(calls).containsExactly(false, true).inOrder()
    }

    @Test fun `auto accurate pass does NOT fire when quality=INSTANT`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        s.setPremiumUnlocked(true)
        s.setOcrDisclaimerShown()
        s.setTranscriptionQuality(TranscriptionQuality.INSTANT)

        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = false) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        // Only the instant pass (false) — no auto accurate.
        assertThat(calls).containsExactly(false).inOrder()
    }

    @Test fun `auto accurate pass does NOT fire when quality=AUTO and metered`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        s.setPremiumUnlocked(true)
        s.setOcrDisclaimerShown()
        s.setTranscriptionQuality(TranscriptionQuality.AUTO)

        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(false).inOrder()
    }

    @Test fun `auto accurate pass does NOT fire when disclaimer not yet shown`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        s.setPremiumUnlocked(true)
        // ocrDisclaimerShown NOT set → should block auto accurate
        s.setTranscriptionQuality(TranscriptionQuality.ACCURATE)

        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = false) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        // Only instant — auto accurate blocked by unseen disclaimer.
        assertThat(calls).containsExactly(false).inOrder()
    }

    // ---- Entitlement gating (premium gate unification, 2026-07-05) ----

    @Test fun `manual accurate pass is blocked when not entitled`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        // premium NOT unlocked: entitled = false even though premiumPresent = true in makeVm
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        assertThat(calls).isEmpty()
    }

    @Test fun `manual instant transcribe works without any entitlement`() = runTest {
        val s = settings()
        s.acknowledgeOnDeviceOcr()
        s.setOcrDisclaimerShown()
        s.setTranscriptionQuality(TranscriptionQuality.ACCURATE)
        // premium NOT unlocked: the instant pass still runs, and the auto accurate
        // follow-up is blocked by the entitlement gate.
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = false) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(false).inOrder()
    }

    @Test fun `manual accurate pass runs when entitled`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(true).inOrder()
    }

    @Test fun `confirmAccuracyDisclaimer degrades to instant when entitlement was relocked and on-device OCR is acknowledged`() = runTest {
        val s = settings()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageAccurate()   // disclaimer not shown yet, so the dialog is raised
        testScheduler.advanceUntilIdle()
        assertThat(vm.showAccuracyDisclaimer.value).isTrue()

        s.setPremiumUnlocked(false)          // relock while the dialog is up
        vm.confirmAccuracyDisclaimer()
        testScheduler.advanceUntilIdle()

        // The confirmed request degrades to the free instant pass instead of an accurate one.
        assertThat(calls).containsExactly(false).inOrder()
        assertThat(vm.showOcrDisclosure.value).isFalse()
    }

    // ---- Wave 4: dialog-confirm and retry paths must re-check consent (and, for entitlement,
    // rely on the startTranscribe funnel) fresh at fire time, not just at the moment a dialog opened.

    @Test fun `confirmAccuracyDisclaimer routes to the OCR disclosure instead of transcribing when on-device OCR is not acknowledged`() = runTest {
        val s = settings()
        s.setPremiumUnlocked(true)
        // onDeviceOcrAcknowledged NOT set.
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")
        vm.transcribeCurrentPageAccurate()   // disclaimer not shown yet, so the accuracy dialog is raised
        testScheduler.advanceUntilIdle()
        assertThat(vm.showAccuracyDisclaimer.value).isTrue()

        s.setPremiumUnlocked(false)          // relock while the accuracy dialog is up
        vm.confirmAccuracyDisclaimer()
        testScheduler.advanceUntilIdle()

        // Consent was never acknowledged, so this must not transcribe at all (not even the
        // degraded instant pass); it routes to the on-device OCR disclosure instead.
        assertThat(calls).isEmpty()
        assertThat(vm.showOcrDisclosure.value).isTrue()
    }

    @Test fun `confirmOcrDisclosure degrades to instant when entitlement was relocked while the disclosure was open`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        // onDeviceOcrAcknowledged NOT set, so the accurate request raises the OCR disclosure
        // with accurate=true pending.
        val calls = mutableListOf<Boolean>()
        val vm = makeAccurateRoutingVm(s, backgroundScope) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("page-1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()
        assertThat(vm.showOcrDisclosure.value).isTrue()

        s.setPremiumUnlocked(false)          // relock while the disclosure is open
        vm.confirmOcrDisclosure()
        testScheduler.advanceUntilIdle()

        assertThat(vm.showOcrDisclosure.value).isFalse()
        // The premium chain is never reached: the confirmed request degrades to instant.
        assertThat(calls).containsExactly(false).inOrder()
    }

    @Test fun `retryTranscribe does not run ML Kit when on-device OCR is not acknowledged`() = runTest {
        val s = settings()
        // onDeviceOcrAcknowledged NOT set.
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")

        vm.retryTranscribe()
        testScheduler.advanceUntilIdle()

        assertThat(calls).isEmpty()
        assertThat(vm.showOcrDisclosure.value).isTrue()
    }

    @Test fun `retryTranscribe degrades to instant when entitlement was relocked since the last accurate attempt`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()
        assertThat(calls).containsExactly(true).inOrder()

        s.setPremiumUnlocked(false)          // relock before the retry
        vm.retryTranscribe()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(true, false).inOrder()
    }

    @Test fun `auto accurate follow-up is blocked when on-device OCR is reset during the instant pass`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()
        s.setTranscriptionQuality(TranscriptionQuality.ACCURATE)
        val calls = mutableListOf<Boolean>()
        // The instant pass resets the disclosure mid-run (as the Settings reset can). The accurate
        // auto follow-up must re-read consent and not fire, even though quality and entitlement allow it.
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true, metered = false) { _, accurate ->
            calls += accurate
            if (!accurate) s.resetOnDeviceOcrDisclosure()
            "ok"
        }
        vm.openPage("p1")
        vm.transcribeCurrentPageOnDevice()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(false).inOrder()
    }

    @Test fun `retryTranscribe runs accurate again when still acknowledged and entitled`() = runTest {
        val s = settings()
        s.setOcrDisclaimerShown()
        s.setPremiumUnlocked(true)
        s.acknowledgeOnDeviceOcr()
        val calls = mutableListOf<Boolean>()
        val vm = makeVm(s, scope = backgroundScope, vlmCapable = true) { _, accurate -> calls += accurate; "ok" }
        vm.openPage("p1")

        vm.transcribeCurrentPageAccurate()
        testScheduler.advanceUntilIdle()
        assertThat(calls).containsExactly(true).inOrder()

        vm.retryTranscribe()
        testScheduler.advanceUntilIdle()

        assertThat(calls).containsExactly(true, true).inOrder()
    }
}
