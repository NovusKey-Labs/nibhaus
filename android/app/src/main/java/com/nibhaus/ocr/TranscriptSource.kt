package com.nibhaus.ocr

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// TranscriptSource (interface) and TranscriptFile now live in :premiumapi (com.nibhaus.ocr) for the
// open-core split; the sources below implement that contract via same-package resolution.
// The premium TailnetTranscriptSource lives in :premium (resolved via PremiumServices).

/**
 * DEFAULT: reads transcripts from the SAF folder a folder-sync app (Syncthing, Nextcloud, …) fills.
 * Walks the tree recursively because pages file under type/label sub-folders (e.g. `pnb/Work/…`).
 */
class SafTranscriptSource(
    private val context: Context,
    private val treeUri: String,
) : TranscriptSource {
    override suspend fun listTranscripts(): List<TranscriptFile> = withContext(Dispatchers.IO) {
        if (treeUri.isBlank()) return@withContext emptyList()
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?: return@withContext emptyList()
        val out = ArrayList<TranscriptFile>()
        val stack = ArrayDeque<Pair<DocumentFile, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            val (dir, prefix) = stack.removeLast()
            for (file in dir.listFiles()) {
                val name = file.name ?: continue
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                when {
                    file.isDirectory -> stack.addLast(file to rel)
                    name.endsWith(".txt") -> {
                        val sidecar = dir.findFile(name.removeSuffix(".txt") + ".json")
                        out.add(
                            TranscriptFile(
                                path = rel,
                                read = { readUri(file.uri) },
                                sidecar = { sidecar?.let { readUri(it.uri) } },
                            )
                        )
                    }
                }
            }
        }
        out
    }

    private fun readUri(uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
}
