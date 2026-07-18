package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.PageEntity
import com.nibhaus.data.RecordingEntity
import com.nibhaus.export.SettingsStore
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.repo.NoteRepository
import com.nibhaus.ui.InkViewModel
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

/**
 * the library grid used to open one Flow subscription per visible card
 * (`notebookPageCount(id)` / `notebookHasAudio(id)` / `pageHasAudio(id)`) — Room invalidates per
 * TABLE, so any stroke write re-ran those queries for every card on screen, and `notebookPageCount`
 * loaded a notebook's whole page list just to read its `.size`. These cover the batched replacements
 * ([com.nibhaus.data.PageDao.observePageCounts], [com.nibhaus.data.RecordingDao.observePagesWithAudio],
 * [com.nibhaus.data.RecordingDao.observeNotebooksWithAudio]) — one grouped/distinct query each,
 * shared across every visible card — mirroring how [com.nibhaus.data.PageDao.observeNonBlankPageIds]
 * already batches per-notebook instead of per-page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBatchingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun page(id: String, notebookId: String, page: Int) = PageEntity(
        id = id,
        notebookId = notebookId,
        addressKey = "1.1.1.$page",
        section = 1,
        owner = 1,
        book = 1,
        page = page,
        firstSeenAt = 0L,
        lastInkAt = 0L,
    )

    private fun recording(id: String, pageId: String) = RecordingEntity(
        id = id, pageId = pageId, path = "/tmp/$id", startedAt = 0L, durationMs = 0L,
    )

    // Scoped to testDispatcher (not DataStore's default background scope) so advanceUntilIdle()
    // deterministically waits for its internal read/write coroutine too — see OcrProgressTest's
    // settings() kdoc for why an unscoped DataStore can leak work past a torn-down Dispatchers.Main.
    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
            tmp.newFile("test_${System.nanoTime()}.preferences_pb")
        }
    )

    // ---- DAO / repo level ----

    @Test fun `notebookPageCounts groups pages by notebook in one query`() = runTest {
        val pageDao = FakePageDao()
        pageDao.insert(page("p1", "nb-a", 1))
        pageDao.insert(page("p2", "nb-a", 2))
        pageDao.insert(page("p3", "nb-b", 1))
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = pageDao, strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        )

        val counts = repo.notebookPageCounts().first()

        assertThat(counts).containsExactly("nb-a", 2, "nb-b", 1)
    }

    @Test fun `notebookPageCounts is empty with no pages`() = runTest {
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = FakePageDao(), strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        )

        assertThat(repo.notebookPageCounts().first()).isEmpty()
    }

    @Test fun `pagesWithAudio lists every page carrying a recording, one query not one per page`() = runTest {
        val recordingDao = FakeRecordingDao()
        recordingDao.insert(recording("r1", "p1"))
        recordingDao.insert(recording("r2", "p2"))
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = FakePageDao(), strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = recordingDao, tagDao = FakeTagDao(),
        )

        assertThat(repo.pagesWithAudio().first()).containsExactly("p1", "p2")
    }

    @Test fun `pagesWithAudio is empty with no recordings`() = runTest {
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = FakePageDao(), strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        )

        assertThat(repo.pagesWithAudio().first()).isEmpty()
    }

    @Test fun `notebooksWithAudio resolves each recording's page to its notebook, one query`() = runTest {
        val pageDao = FakePageDao()
        pageDao.insert(page("p1", "nb-a", 1))
        pageDao.insert(page("p2", "nb-b", 1))
        pageDao.insert(page("p3", "nb-b", 2))
        val recordingDao = FakeRecordingDao(pageDao)
        recordingDao.insert(recording("r1", "p1"))
        recordingDao.insert(recording("r2", "p3"))
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = pageDao, strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = recordingDao, tagDao = FakeTagDao(),
        )

        assertThat(repo.notebooksWithAudio().first()).containsExactly("nb-a", "nb-b")
    }

    // ---- ViewModel level: the removed per-card factories are replaced by shared StateFlows ----

    private fun penMgr(scope: kotlinx.coroutines.CoroutineScope) = PenConnectionManager(
        sdk = FakeNeoPenSdk(), prefs = InMemoryPenPrefs(), scope = scope, onDot = {},
    )

    @Test fun `viewmodel exposes one shared batched flow per data point, not a per-id factory`() = runTest {
        val pageDao = FakePageDao()
        pageDao.insert(page("p1", "nb-a", 1))
        pageDao.insert(page("p2", "nb-a", 2))
        val recordingDao = FakeRecordingDao(pageDao)
        recordingDao.insert(recording("r1", "p1"))
        val repo = NoteRepository(
            notebookDao = FakeNotebookDao(), pageDao = pageDao, strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = recordingDao, tagDao = FakeTagDao(),
        )
        val vm = InkViewModel(
            repo = repo,
            settings = settings(),
            pen = PenDeps(penManager = penMgr(backgroundScope), scanner = PenScanner()),
        )

        assertThat(vm.notebookPageCounts.first()).containsExactly("nb-a", 2)
        assertThat(vm.notebooksWithAudio.first()).containsExactly("nb-a")
        assertThat(vm.pagesWithAudio.first()).containsExactly("p1")
    }
}
