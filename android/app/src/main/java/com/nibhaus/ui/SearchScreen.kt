package com.nibhaus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nibhaus.data.PageEntity
import com.nibhaus.ui.common.EmptyState
import kotlinx.coroutines.delay

/**
 * Search across notebook names and the OCR transcripts imported from the sync folder. Notebook-name
 * matching works immediately (no OCR needed); transcript matching lights up once the watcher writes
 * `<pageId>.txt` back. Opening the screen pulls any new transcripts, then matches live as you type.
 */
@Composable
fun SearchScreen(vm: InkViewModel, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val notebooks by vm.notebooks.collectAsStateWithLifecycle()
    val titles = remember(notebooks) { notebooks.associate { it.id to it.title } }
    val recentSearches by vm.recentSearches.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    var imported by remember { mutableStateOf<Int?>(null) }

    // Pull transcripts the watcher wrote back, once, on open.
    LaunchedEffect(Unit) { imported = vm.importTranscripts() }

    // Empty box shows recent pages; otherwise debounced search as the query changes — the debounce
    // firing IS "the search running", so that's where we record it into recent-search history.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = vm.recentPages()
        } else {
            delay(250)
            results = vm.searchPages(query)
            vm.addRecentSearch(query)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).riseIn(0), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text("Back") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search notebooks and transcribed pages") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().riseIn(1),
        )
        // a plain-language example, so an empty search field doesn't feel like a dead end.
        if (query.isBlank()) {
            Text(
                "Try: meeting notes",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // the two "nothing to show" cases get a friendly icon + one-line next action instead
        // of a plain caption — everything else (recent pages, N match(es)) stays a plain status line.
        if (results.isEmpty()) {
            if (query.isBlank()) {
                EmptyState(Icons.Outlined.Search, "Write a page and it lands here.")
            } else {
                EmptyState(Icons.Outlined.SearchOff, "No matches. Notebook names match now; page text becomes searchable once transcribed.")
            }
        } else {
            val hint = if (query.isBlank())
                imported?.takeIf { it > 0 }?.let { "Imported $it new transcript(s). Recent pages:" } ?: "Recent pages:"
            else "${results.size} match(es)"
            Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }

        // Recent search queries — only relevant on the empty box, tap re-runs, "Clear" wipes history.
        if (query.isBlank() && recentSearches.isNotEmpty()) {
            Column(Modifier.riseIn(2)) {
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Recent searches", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    TextButton(onClick = { vm.clearRecentSearches() }) { Text("Clear") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentSearches.forEach { q ->
                        SuggestionChip(onClick = { query = q }, label = { Text(q) })
                    }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(results, key = { _, it -> it.id }) { idx, page ->
                Card(
                    Modifier.fillMaxWidth().riseIn(minOf(3 + idx, 4)).clickable { vm.openSearchHit(page); onBack() },
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "${titles[page.notebookId] ?: "Notebook"} · page ${page.page}",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        )
                        val sub = when {
                            !page.transcript.isNullOrBlank() -> snippet(page.transcript!!, query)
                            query.isNotBlank() -> "Matches notebook name"
                            else -> "Recently inked"
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A short context window around the first match of [q] in [text], single-lined with ellipses. */
internal fun snippet(text: String, q: String): String {
    if (q.isBlank()) return text.take(120).replace("\n", " ")
    val i = text.indexOf(q, ignoreCase = true)
    if (i < 0) return text.take(120).replace("\n", " ")
    val start = (i - 40).coerceAtLeast(0)
    val end = (i + q.length + 60).coerceAtMost(text.length)
    val body = text.substring(start, end).replace("\n", " ")
    return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
}
