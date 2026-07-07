package com.nibhaus.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.nibhaus.data.RecordingDao
import com.nibhaus.data.RecordingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Voice notes tied to a page. The pen has no microphone and the NeoLAB SDK has no audio API, so the
 * recording is captured phone-side (MediaRecorder, AAC/m4a) into app-private storage — nothing
 * leaves the device. The recording's [RecordingEntity.startedAt] is stamped on the SAME wall clock
 * as stroke timestamps, which is the hook that lets the UI line audio up with the ink later.
 *
 * Note: one recorder + one player, single active each. Upgrade path for true background capture
 * (keep recording after leaving the screen) is to move this into the foreground service.
 */
class RecordingController(
    private val context: Context,
    private val dao: RecordingDao,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        /** Capturing audio for [pageId]; [startedAt] aligns with stroke timestamps. */
        data class Recording(val pageId: String, val startedAt: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The recording currently loaded in the player (playing OR paused), or null when stopped. */
    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    /** True while audio is actively playing (false when paused or stopped). */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Playback head of the active recording, in ms from its start (0 when nothing is playing). */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var inProgress: RecordingEntity? = null
    private var player: MediaPlayer? = null
    private var positionJob: Job? = null

    val isRecording: Boolean get() = recorder != null

    /** Begin a recording bound to [pageId] and its stable Ncode [addressKey]. No-op if one is running. */
    fun start(pageId: String, addressKey: String) {
        if (recorder != null) return
        stopPlayback()
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "$id.m4a")
        val rec = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
        }
        try {
            rec.prepare()
            rec.start()
        } catch (e: Exception) {
            Log.w(TAG, "record start failed: ${e.message}")
            runCatching { rec.release() }
            file.delete()
            return
        }
        recorder = rec
        val entity = RecordingEntity(id, pageId, file.absolutePath, now(), 0L, addressKey = addressKey)
        inProgress = entity
        scope.launch { dao.insert(entity) }
        _state.value = State.Recording(pageId, entity.startedAt)
    }

    /** Stop the active recording and persist its final duration. No-op if not recording. */
    fun stop() {
        val rec = recorder ?: return
        val entity = inProgress
        recorder = null
        inProgress = null
        // stop() throws if it was started but no valid data was captured — clean up either way.
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        _state.value = State.Idle
        if (entity != null) {
            if (ok) {
                val dur = (now() - entity.startedAt).coerceAtLeast(0L)
                scope.launch { dao.setDuration(entity.id, dur) }
            } else {
                File(entity.path).delete()
                scope.launch { dao.delete(entity.id) }
            }
        }
    }

    /** Play a stored recording from [startMs] (stops any current playback first). */
    fun play(recording: RecordingEntity, startMs: Long = 0L) {
        stopPlayback()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(recording.path)
            mp.prepare()
            if (startMs > 0) mp.seekTo(startMs.toInt())
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
            runCatching { mp.release() }
            return
        }
        mp.setOnCompletionListener { stopPlayback() }
        player = mp
        _playingId.value = recording.id
        _positionMs.value = startMs
        _isPlaying.value = true
        startTicker()
    }

    /** Pause playback in place — keeps the player and position (resume continues from here). */
    fun pause() {
        val mp = player ?: return
        positionJob?.cancel(); positionJob = null
        runCatching { mp.pause() }
        _positionMs.value = runCatching { mp.currentPosition.toLong() }.getOrDefault(_positionMs.value)
        _isPlaying.value = false
    }

    /** Resume a paused recording from where it left off. */
    fun resume() {
        val mp = player ?: return
        runCatching { mp.start() }
        _isPlaying.value = true
        startTicker()
    }

    /** Seek the active (playing or paused) recording to [ms] from its start. */
    fun seekTo(ms: Long) {
        val mp = player ?: return
        runCatching { mp.seekTo(ms.coerceAtLeast(0).toInt()) }
        _positionMs.value = ms
    }

    fun stopPlayback() {
        positionJob?.cancel(); positionJob = null
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        _playingId.value = null
        _isPlaying.value = false
        _positionMs.value = 0L
    }

    private fun startTicker() {
        positionJob?.cancel()
        val mp = player ?: return
        positionJob = scope.launch {
            while (true) {
                _positionMs.value = runCatching { mp.currentPosition.toLong() }.getOrDefault(_positionMs.value)
                delay(100)
            }
        }
    }

    /** Delete a recording: stop it if playing, remove the audio file, then the row. */
    fun delete(recording: RecordingEntity) {
        if (_playingId.value == recording.id) stopPlayback()
        runCatching { File(recording.path).delete() }
        scope.launch { dao.delete(recording.id) }
    }

    /** Set a user-facing label for a recording. */
    fun rename(id: String, title: String) {
        scope.launch { dao.setTitle(id, title.trim()) }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

    private companion object { const val TAG = "NibhausAudio" }
}
