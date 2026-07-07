package com.nibhaus

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Locks the memory-pressure threshold at which the VLM native context is released. Values are the
 *  documented ComponentCallbacks2.TRIM_MEMORY_* levels. */
class MemoryTrimTest {

    @Test fun `releases at running-critical and every background level`() {
        assertThat(shouldReleaseVlmMemory(15)).isTrue() // TRIM_MEMORY_RUNNING_CRITICAL
        assertThat(shouldReleaseVlmMemory(20)).isTrue() // TRIM_MEMORY_UI_HIDDEN
        assertThat(shouldReleaseVlmMemory(40)).isTrue() // TRIM_MEMORY_BACKGROUND
        assertThat(shouldReleaseVlmMemory(60)).isTrue() // TRIM_MEMORY_MODERATE
        assertThat(shouldReleaseVlmMemory(80)).isTrue() // TRIM_MEMORY_COMPLETE
    }

    @Test fun `keeps the context on mild running pressure`() {
        assertThat(shouldReleaseVlmMemory(5)).isFalse()  // TRIM_MEMORY_RUNNING_MODERATE
        assertThat(shouldReleaseVlmMemory(10)).isFalse() // TRIM_MEMORY_RUNNING_LOW
        assertThat(shouldReleaseVlmMemory(0)).isFalse()
    }
}
