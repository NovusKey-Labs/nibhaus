package com.nibhaus.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Share a rendered page as a PNG or PDF. The file is written to a private cache dir and handed out
 * as a `content://` URI via [FileProvider] with a temporary read grant — nothing is uploaded; the
 * user picks the destination. The plain [share] chooser excludes email apps on purpose (there's a
 * dedicated [email] action that attaches straight to a new message).
 */
object PageShare {

    enum class Format(val mime: String, val ext: String) {
        PNG("image/png", "png"),
        PDF("application/pdf", "pdf"),
    }

    /** Write [bmp] to a cache file in [format] and return its FileProvider URI (null on failure). */
    fun fileUri(context: Context, bmp: Bitmap, format: Format, baseName: String = "page"): Uri? =
        runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "$baseName.${format.ext}")
            when (format) {
                Format.PNG -> file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Format.PDF -> writePdf(bmp, file)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()

    /** Open a share chooser for [uri], excluding email apps (use [email] for those). */
    fun share(context: Context, uri: Uri, mime: String) {
        val chooser = Intent.createChooser(baseSend(uri, mime), "Share page").apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, emailComponents(context))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    /** A content uri for an arbitrary app file (e.g. a voice note), via the shared FileProvider. */
    fun fileUri(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    /** Share several files at once (a page image + its voice note(s)), excluding email apps. */
    fun shareMultiple(context: Context, uris: List<Uri>, mime: String = "*/*") {
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share page").apply {
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, emailComponents(context))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(chooser) }
    }

    /** Attach [uri] to a new email (the selector restricts the picker to email apps). */
    fun email(context: Context, uri: Uri, mime: String) {
        val send = baseSend(uri, mime).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Page from Nibhaus")
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(send) }
    }

    private fun baseSend(uri: Uri, mime: String) = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** ComponentNames of installed email apps, so the plain chooser can exclude them. */
    private fun emailComponents(context: Context): Array<ComponentName> =
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .toTypedArray()

    /** Render [bmp] to a single-page PDF in memory (null on failure) — for the export-folder copy. */
    fun pdfBytes(bmp: Bitmap): ByteArray? = runCatching {
        java.io.ByteArrayOutputStream().use { out ->
            val doc = PdfDocument()
            val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
            val page = doc.startPage(info)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            doc.finishPage(page)
            doc.writeTo(out)
            doc.close()
            out.toByteArray()
        }
    }.getOrNull()

    private fun writePdf(bmp: Bitmap, file: File) {
        pdfBytes(bmp)?.let { bytes -> file.outputStream().use { it.write(bytes) } }
    }
}
