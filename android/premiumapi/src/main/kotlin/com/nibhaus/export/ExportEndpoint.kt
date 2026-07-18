package com.nibhaus.export

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** A validated NAS export origin. Keep credentials and request paths out of configuration. */
class ExportEndpoint private constructor(
    val origin: String,
    val uri: URI,
) {
    fun resolve(path: String): URL = uri.resolve(path.removePrefix("/")).toURL()

    companion object {
        fun parse(value: String, allowCleartext: Boolean = false): ExportEndpoint {
            require(value.none { it.isISOControl() }) { "Endpoint contains control characters" }
            val parsed = try {
                URI(value.trim())
            } catch (e: URISyntaxException) {
                throw IllegalArgumentException("Invalid export endpoint", e)
            }
            val scheme = requireNotNull(parsed.scheme?.lowercase()) {
                "Export endpoint must include a scheme"
            }
            require(scheme == "https" || (scheme == "http" && allowCleartext)) {
                "Export endpoint must use HTTPS"
            }
            require(parsed.rawUserInfo == null) { "Export endpoint must not contain userinfo" }
            require(parsed.rawFragment == null) { "Export endpoint must not contain a fragment" }
            require(parsed.rawQuery == null) { "Export endpoint must not contain a query" }
            require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") {
                "Export endpoint must be an origin without a path"
            }
            val host = requireNotNull(parsed.host?.removeSurrounding("[", "]")) {
                "Export endpoint must have a valid host"
            }
            val normalizedHost = if (host.contains(':')) host.lowercase() else IDN.toASCII(host).lowercase()
            require(normalizedHost.isNotEmpty()) { "Export endpoint must have a valid host" }
            val port = parsed.port
            require(port == NO_PORT || port in 1..MAX_PORT) {
                "Export endpoint port must be between 1 and $MAX_PORT"
            }
            val authorityHost = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
            val origin = "$scheme://$authorityHost${if (port == NO_PORT) "" else ":$port"}"
            return ExportEndpoint(origin, URI("$origin/"))
        }

        private const val NO_PORT = -1
        private const val MAX_PORT = 65_535
    }
}
