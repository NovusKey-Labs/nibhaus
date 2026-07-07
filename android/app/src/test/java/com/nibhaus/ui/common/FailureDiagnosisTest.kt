package com.nibhaus.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** #8: pure exception/context → user-facing failure message mapping. */
class FailureDiagnosisTest {

    // ---- networkReason ----

    @Test fun `unknown host reads as address not found`() {
        assertThat(FailureDiagnosis.networkReason(UnknownHostException("nas.local")))
            .isEqualTo("That address couldn't be found")
    }

    @Test fun `connect exception reads as refused`() {
        assertThat(FailureDiagnosis.networkReason(ConnectException("Connection refused")))
            .isEqualTo("It refused the connection")
    }

    @Test fun `socket timeout reads as took too long`() {
        assertThat(FailureDiagnosis.networkReason(SocketTimeoutException("connect timed out")))
            .isEqualTo("It took too long to respond")
    }

    @Test fun `a generic exception whose message mentions timing out still reads as took too long`() {
        assertThat(FailureDiagnosis.networkReason(RuntimeException("Read timed out")))
            .isEqualTo("It took too long to respond")
    }

    @Test fun `an HTTP error-code message reads as responded with an error`() {
        assertThat(FailureDiagnosis.networkReason(IllegalStateException("export PUT foo.svg -> HTTP 500")))
            .isEqualTo("It responded with an error")
        assertThat(FailureDiagnosis.networkReason(IllegalStateException("translation endpoint returned 503")))
            .isEqualTo("It responded with an error")
    }

    @Test fun `no exception at all reads as didn't respond`() {
        assertThat(FailureDiagnosis.networkReason(null)).isEqualTo("It didn't respond")
    }

    @Test fun `an unrecognized exception falls back to didn't respond`() {
        assertThat(FailureDiagnosis.networkReason(RuntimeException("boom"))).isEqualTo("It didn't respond")
    }

    // ---- hostUnreachable ----

    @Test fun `hostUnreachable names the host and suggests same network or VPN`() {
        val d = FailureDiagnosis.hostUnreachable("transcribe", "http://100.100.100.100:8090", UnknownHostException("100.100.100.100"))
        assertThat(d.message).contains("transcribe")
        assertThat(d.message).contains("http://100.100.100.100:8090")
        assertThat(d.message).contains("same network or VPN")
        assertThat(d.message).contains("That address couldn't be found")
        assertThat(d.canRetry).isTrue()
    }

    @Test fun `hostUnreachable with no exception still names the host with a generic reason`() {
        val d = FailureDiagnosis.hostUnreachable("translate", "http://gpu-box:8080")
        assertThat(d.message).contains("http://gpu-box:8080")
        assertThat(d.message).contains("It didn't respond")
    }

    // ---- noResult ----

    @Test fun `noResult never names a host and points at Settings`() {
        val d = FailureDiagnosis.noResult("transcribe")
        assertThat(d.message).contains("transcribe")
        assertThat(d.message).doesNotContain("http")
        assertThat(d.message).contains("Settings")
    }

    // ---- exportFailure ----

    @Test fun `exportFailure with no exception names both storage and renderer as suspects`() {
        val d = FailureDiagnosis.exportFailure()
        assertThat(d.message).contains("sync folder")
        assertThat(d.message).contains("rendered")
        assertThat(d.message).contains("stays queued")
        assertThat(d.canRetry).isFalse()
    }

    @Test fun `exportFailure with a real exception uses its classified reason instead`() {
        val d = FailureDiagnosis.exportFailure(ConnectException("refused"))
        assertThat(d.message).contains("it refused the connection")
        assertThat(d.canRetry).isFalse()
    }
}
