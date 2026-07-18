package com.nibhaus.export

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportEndpointTest {
    @Test fun `normalizes and surfaces exact origin`() {
        val endpoint = ExportEndpoint.parse(" https://NAS.Example.COM:8443/ ")
        assertThat(endpoint.origin).isEqualTo("https://nas.example.com:8443")
        assertThat(endpoint.resolve("_index").toString()).isEqualTo("https://nas.example.com:8443/_index")
    }

    @Test fun `rejects malicious endpoint components`() {
        listOf(
            "https://user:pass@nas.example.com",
            "https://nas.example.com/#fragment",
            "https://nas.example.com/?redirect=https://evil.example",
            "https://nas.example.com/export",
            "https://nas.example.com\n.evil.example",
        ).forEach { value -> assertThrows(value, IllegalArgumentException::class.java) { ExportEndpoint.parse(value) } }
    }

    @Test fun `rejects malformed hosts and ports`() {
        listOf(
            "https://",
            "https://nas.example.com:0",
            "https://nas.example.com:65536",
            "https://nas.example.com:notaport",
        ).forEach { value -> assertThrows(value, IllegalArgumentException::class.java) { ExportEndpoint.parse(value) } }
    }

    @Test fun `cleartext requires explicit opt in`() {
        assertThrows(IllegalArgumentException::class.java) { ExportEndpoint.parse("http://100.64.0.1:8090") }
        assertThat(ExportEndpoint.parse("http://100.64.0.1:8090", allowCleartext = true).origin)
            .isEqualTo("http://100.64.0.1:8090")
    }
}
