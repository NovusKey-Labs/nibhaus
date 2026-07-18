package com.nibhaus

import com.google.common.truth.Truth.assertThat
import com.nibhaus.audio.RecordingCapture
import com.nibhaus.audio.RecordingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RecordingControllerTest {
    @Test fun `immediate start stop waits for insert before duration update`() {
        val dao = FakeRecordingDao()
        val insertEntered = CountDownLatch(1)
        val allowInsert = CountDownLatch(1)
        val events = mutableListOf<String>()
        val blockingDao = object : com.nibhaus.data.RecordingDao by dao {
            override suspend fun insert(recording: com.nibhaus.data.RecordingEntity) {
                insertEntered.countDown()
                allowInsert.await()
                dao.insert(recording)
                synchronized(events) { events += "insert" }
            }
            override suspend fun setDuration(id: String, durationMs: Long) {
                synchronized(events) { events += "duration" }
                dao.setDuration(id, durationMs)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("recording-test").toFile()
        try {
            val controller = RecordingController(
                null, blockingDao, scope, now = { 10L }, recordingsDir = dir,
                captureFactory = { successfulCapture() },
            )
            controller.start("p1", "3.27.603.1")
            assertThat(insertEntered.await(2, TimeUnit.SECONDS)).isTrue()
            controller.stop()
            assertThat(events).isEmpty()
            allowInsert.countDown()
            waitUntil { synchronized(events) { events.size == 2 } }
            assertThat(events).containsExactly("insert", "duration").inOrder()
            assertThat(dao.rows.values.single().durationMs).isEqualTo(0L)
            assertThat(controller.state.value).isEqualTo(RecordingController.State.Idle)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test fun `capture start failure creates neither row nor recording state`() {
        val dao = FakeRecordingDao()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("recording-failure-test").toFile()
        try {
            val controller = RecordingController(
                null, dao, scope, recordingsDir = dir,
                captureFactory = { object : RecordingCapture {
                    override fun start(file: File) { file.writeText("partial"); error("mic unavailable") }
                    override fun stop() = Unit
                    override fun release() = Unit
                } },
            )
            controller.start("p1", "3.27.603.1")
            runBlocking { kotlinx.coroutines.delay(50) }
            assertThat(dao.rows).isEmpty()
            assertThat(controller.state.value).isEqualTo(RecordingController.State.Idle)
            assertThat(dir.listFiles().orEmpty().toList()).isEmpty()
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    private fun successfulCapture() = object : RecordingCapture {
        override fun start(file: File) { file.writeText("audio") }
        override fun stop() = Unit
        override fun release() = Unit
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }
}
