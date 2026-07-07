package com.nibhaus.export

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// StorageProvider (interface) and mimeOf() now live in :premiumapi (com.nibhaus.export) for the
// open-core split; the free providers below implement that contract via same-package resolution.
// The premium TailscalePushProvider lives in :premium (resolved via PremiumServices).

/**
 * DEFAULT target. Writes into a SAF-granted folder; a folder-sync app you run (Syncthing,
 * Nextcloud, …) watches it. We never assume a filesystem path — only the persisted tree uri.
 */
class LocalFolderProvider(private val context: Context, private val treeUri: Uri) : StorageProvider {
    override val id: String = "local_folder:$treeUri"

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("SAF folder not accessible: $treeUri")
        val parts = name.split('/')
        // Find-or-create each sub-folder segment (findFile first so we never duplicate a dir).
        var dir = root
        for (seg in parts.dropLast(1)) {
            dir = dir.findFile(seg)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(seg)
                ?: error("could not create folder '$seg' under $treeUri")
        }
        val fileName = parts.last()
        // Overwrite: SAF has no truncate, so delete-then-create keeps it idempotent (no dupes).
        dir.findFile(fileName)?.delete()
        val file = dir.createFile(mimeOf(fileName), fileName) ?: error("could not create $name in $treeUri")
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) }
            ?: error("could not open $name for writing")
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext
        var dir: DocumentFile? = root
        for (seg in name.split('/').dropLast(1)) dir = dir?.findFile(seg)?.takeIf { it.isDirectory }
        dir?.findFile(name.substringAfterLast('/'))?.delete()
        Unit
    }
}

/** Exports stay on-device (app files dir). For testing / "I'll grab them over adb". */
class LocalOnlyProvider(context: Context) : StorageProvider {
    override val id: String = "local_only"
    private val dir = File(context.filesDir, "exports").apply { mkdirs() }

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val f = File(dir, name)
        f.parentFile?.mkdirs() // name may include sub-folders (pnb/Work/…)
        f.writeBytes(bytes) // overwrite
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        File(dir, name).delete()
        Unit
    }
}
