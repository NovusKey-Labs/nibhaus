package com.nibhaus.share

import android.content.Context
import com.nibhaus.data.Point
import com.nibhaus.data.StrokeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Orchestrates a page's Handwriting Replay → animated GIF: render frames ([ReplayGifRenderer]),
 * encode them ([GifEncoder]), write the bytes to the same private cache dir [PageShare] already
 * shares from, and hand back a ready-to-share [File]. Runs off the main thread (Dispatchers.Default)
 * — rendering ~48 bitmap frames and LZW-encoding them isn't free.
 */
object ReplayGif {

    const val MIME = "image/gif"

    /** Target total playback time for the exported clip, regardless of how long the real replay
     *  took — [GifEncoder]'s per-frame delay is derived from this and the frame count. */
    private const val TARGET_DURATION_MS = 3_000L

    /** Renders + encodes [strokes]' replay to a cache file (null if there's nothing to render or the
     *  export fails). Safe to call from any thread; suspends on [Dispatchers.Default]. */
    suspend fun renderToCache(
        context: Context,
        strokes: List<StrokeEntity>,
        pointsOf: (StrokeEntity) -> List<Point>,
        baseName: String = "replay",
        strokeScale: Float = 1f, // the Fine/Normal/Bold handwriting-size preset (#15b) — see PageRender.renderPage.
    ): File? = withContext(Dispatchers.Default) {
        runCatching {
            val frames = ReplayGifRenderer.renderFrames(strokes, pointsOf, strokeScale = strokeScale)
            if (frames.isEmpty()) return@runCatching null
            val delayCs = (TARGET_DURATION_MS / 10L / frames.size).coerceIn(2L, 30L).toInt()
            val width = frames[0].width
            val height = frames[0].height
            val pixelFrames = frames.map { bmp ->
                val px = IntArray(bmp.width * bmp.height)
                bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                px
            }
            frames.forEach { it.recycle() }
            val bytes = GifEncoder.encode(width, height, pixelFrames, delayCs)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "$baseName.gif")
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    /** Shares an already-rendered GIF [file] via the same FileProvider chooser [PageShare] uses. */
    fun share(context: Context, file: File) {
        val uri = PageShare.fileUri(context, file) ?: return
        PageShare.share(context, uri, MIME)
    }
}
