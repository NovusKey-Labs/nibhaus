package com.nibhaus

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.nibhaus.data.NotebookEntity
import com.nibhaus.data.PageEntity
import com.nibhaus.export.SettingsStore
import com.nibhaus.pen.FakeNeoPenSdk
import com.nibhaus.pen.InMemoryPenPrefs
import com.nibhaus.pen.PenConnectionManager
import com.nibhaus.pen.PenScanner
import com.nibhaus.repo.NoteRepository
import com.nibhaus.repo.ftsMatch
import com.nibhaus.repo.matchesAllTerms
import com.nibhaus.repo.queryTerms
import com.nibhaus.ui.InkViewModel
import com.nibhaus.ui.PenDeps
import com.nibhaus.ui.snippet
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

@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {

    @get:Rule val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun settings(): SettingsStore = SettingsStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(testDispatcher)) {
            tmp.newFile("test_${System.nanoTime()}.preferences_pb")
        }
    )

    private fun page(id: String, transcript: String?, notebookId: String = "nb") = PageEntity(
        id = id, notebookId = notebookId, addressKey = "3.27.603.1",
        section = 3, owner = 27, book = 603, page = 1, firstSeenAt = 0, lastInkAt = id.last().code.toLong(),
        transcript = transcript,
    )

    private fun notebook(id: String, title: String) = NotebookEntity(
        id = id, book = 603, instanceSeq = 0, locked = false, title = title, createdAt = 0,
    )

    /** Mirrors NoteRepository.searchPages: all words must match (any order); transcript hits first,
     *  then title-matched notebooks' pages, deduped. Uses the same pure helpers as the repo. */
    private suspend fun search(
        nb: FakeNotebookDao,
        pg: FakePageDao,
        q: String,
    ): List<PageEntity> {
        val terms = queryTerms(q)
        if (terms.isEmpty()) return emptyList()
        val match = ftsMatch(terms)
        val byTranscript = if (match.isBlank()) emptyList() else pg.searchByTranscriptFts(match)
        val seed = terms.maxByOrNull { it.length }!!
        val byTitle = nb.searchByTitle(seed)
            .filter { matchesAllTerms(it.title, terms) }
            .flatMap { pg.pagesInNotebook(it.id) }
        return (byTranscript + byTitle).distinctBy { it.id }
    }

    @Test
    fun `transcript search matches case-insensitively and skips untranscribed pages`() = runTest {
        val dao = FakePageDao()
        dao.byId["a"] = page("a", "Buy MILK and eggs")
        dao.byId["b"] = page("b", "call the dentist")
        dao.byId["c"] = page("c", null) // not transcribed yet

        assertThat(dao.searchByTranscript("milk").map { it.id }).containsExactly("a")
        assertThat(dao.searchByTranscript("zzz")).isEmpty()
    }

    @Test
    fun `search matches all words in any order, not just a contiguous substring`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        pg.byId["a"] = page("a", "eggs and milk")
        pg.byId["b"] = page("b", "just milk")

        // both words present, order-independent — the win over a single LIKE '%milk eggs%'
        assertThat(search(nb, pg, "milk eggs").map { it.id }).containsExactly("a")
        // a single word still matches broadly
        assertThat(search(nb, pg, "milk").map { it.id }).containsExactly("a", "b")
        // a word that appears nowhere rules the page out
        assertThat(search(nb, pg, "milk dentist")).isEmpty()
    }

    @Test
    fun `search finds pages by notebook title even with no transcript`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        nb.byId["work"] = notebook("work", "Work meetings")
        nb.byId["home"] = notebook("home", "Groceries")
        pg.byId["p1"] = page("p1", null, notebookId = "work")
        pg.byId["p2"] = page("p2", null, notebookId = "home")

        // "meeting" matches the Work notebook's title → its untranscribed page surfaces.
        assertThat(search(nb, pg, "meeting").map { it.id }).containsExactly("p1")
    }

    @Test
    fun `search unions transcript and title hits without duplicating a page`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        nb.byId["work"] = notebook("work", "Work notes")
        pg.byId["p1"] = page("p1", "remember the work deadline", notebookId = "work") // matches both ways
        pg.byId["p2"] = page("p2", null, notebookId = "work")                          // matches by title only

        val ids = search(nb, pg, "work").map { it.id }
        assertThat(ids).containsExactly("p1", "p2")  // p1 once, not twice
    }

    @Test
    fun `transcript search is free - handwriting and title search need no entitlement`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        nb.byId["work"] = notebook("work", "Work notes")
        pg.byId["p1"] = page("p1", "remember the milk", notebookId = "work")

        // Free tier (gate unification 2026-07-05): the handwriting transcript "milk" is
        // searchable with no premium unlock at all...
        assertThat(search(nb, pg, "milk").map { it.id }).containsExactly("p1")
        // ...and title search keeps working, unchanged.
        assertThat(search(nb, pg, "work").map { it.id }).containsExactly("p1")

        // Lock it through the REAL repository too: searchPages takes only the query (the old
        // includeTranscript parameter no longer exists) and always searches the transcript index.
        val repo = NoteRepository(
            notebookDao = nb, pageDao = pg, strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        )
        assertThat(repo.searchPages("milk").map { it.id }).containsExactly("p1")
    }

    /** RED for the gate flip: the production ViewModel search path must surface transcript hits
     *  with the premium unlock at its default (false). Fails before the includeTranscript removal
     *  because InkViewModel.searchPages currently forwards isPremium.value as the gate. */
    @Test
    fun `viewmodel search includes transcript hits without premium`() = runTest {
        val nb = FakeNotebookDao(); val pg = FakePageDao()
        pg.byId["p1"] = page("p1", "remember the milk")
        val repo = NoteRepository(
            notebookDao = nb, pageDao = pg, strokeDao = FakeStrokeDao(),
            outboxDao = FakeOutboxDao(), recordingDao = FakeRecordingDao(), tagDao = FakeTagDao(),
        )
        val vm = InkViewModel(
            repo = repo,
            settings = settings(),  // fresh DataStore: premium locked (default false)
            pen = PenDeps(
                penManager = PenConnectionManager(
                    sdk = FakeNeoPenSdk(), prefs = InMemoryPenPrefs(), scope = backgroundScope, onDot = {},
                ),
                scanner = PenScanner(),
            ),
        )
        assertThat(vm.searchPages("milk").map { it.id }).containsExactly("p1")
    }

    @Test
    fun `recent pages come back newest-inked first and capped at the limit`() = runTest {
        val pg = FakePageDao()
        // lastInkAt is derived from the id's last char code, so 'a' < 'b' < 'c'.
        pg.byId["a"] = page("a", null)
        pg.byId["b"] = page("b", null)
        pg.byId["c"] = page("c", null)

        assertThat(pg.recentPages(2).map { it.id }).containsExactly("c", "b").inOrder()
    }

    @Test
    fun `snippet windows around the match with ellipses`() {
        val text = "the quick brown fox jumps over the lazy dog and keeps on running forever"
        val s = snippet(text, "lazy")
        assertThat(s).contains("lazy")
        assertThat(s).doesNotContain("\n")
    }

    @Test
    fun `snippet falls back to a head slice when there is no match`() {
        assertThat(snippet("hello world", "absent")).isEqualTo("hello world")
    }
}
