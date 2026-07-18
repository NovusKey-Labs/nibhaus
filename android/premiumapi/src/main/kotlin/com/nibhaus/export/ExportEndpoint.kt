package com.nibhaus.export

import java.net.IDN
import java.net.URI
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
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid export endpoint", e)
            }
            val scheme = parsed.scheme?.lowercase()
                ?: throw IllegalArgumentException("Export endpoint must include a scheme")
            require(scheme == "https" || (scheme == "http" && allowCleartext)) {
                "Export endpoint must use HTTPS"
            }
            require(parsed.rawUserInfo == null) { "Export endpoint must not contain userinfo" }
            require(parsed.rawFragment == null) { "Export endpoint must not contain a fragment" }
            require(parsed.rawQuery == null) { "Export endpoint must not contain a query" }
            require(parsed.rawPath.isNullOrEmpty() || parsed.rawPath == "/") {
                "Export endpoint must be an origin without a path"
            }
            val host = parsed.host?.removeSurrounding("[", "]")
                ?: throw IllegalArgumentException("Export endpoint must have a valid host")
            val normalizedHost = if (host.contains(':')) host.lowercase() else IDN.toASCII(host).lowercase()
            require(normalizedHost.isNotEmpty()) { "Export endpoint must have a valid host" }
            val port = parsed.port
            require(port == -1 || port in 1..65535) { "Export endpoint port must be between 1 and 65535" }
            val authorityHost = if (normalizedHost.contains(':')) "[$normalizedHost]" else normalizedHost
            val origin = "$scheme://$authorityHost${if (port == -1) "" else ":$port"}"
            return ExportEndpoint(origin, URI("$origin/"))
        }
    }
}
