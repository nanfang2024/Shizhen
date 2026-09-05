package com.framepick.app.util

import java.net.URI

internal object DiagnosticSanitizer {
    private val urlPattern = Regex("[A-Za-z][A-Za-z0-9+.-]*://[^\\s<>\\\"']+")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val sensitiveAssignment = Regex(
        "(?i)\\b(cookie|authorization|token|access_token|refresh_token|signature|sig|password|session|api[_-]?key)" +
            "\\s*[:=]\\s*[^\\s,;]+",
    )

    fun sanitize(value: String): String {
        val urlsRemoved = urlPattern.replace(value) { match -> sanitizeUrl(match.value) }
        return sensitiveAssignment.replace(
            bearerPattern.replace(urlsRemoved, "Bearer <redacted>"),
        ) { match -> "${match.groupValues[1]}=<redacted>" }
    }

    fun sanitizeUrl(value: String): String {
        val trailing = value.takeLastWhile { it in ".,;!?，。；！？”’)]}" }
        val core = value.dropLast(trailing.length)
        val safe = runCatching {
            val uri = URI(core)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> buildString {
                    append(uri.scheme.lowercase())
                    append("://")
                    append(uri.host ?: "<unknown-host>")
                    append(uri.rawPath.orEmpty().take(MAX_PATH_LENGTH))
                    if (!uri.rawQuery.isNullOrBlank()) append("?<redacted>")
                    if (!uri.rawFragment.isNullOrBlank()) append("#<redacted>")
                }
                "content" -> "content://${uri.host ?: "provider"}/<redacted>"
                "file" -> "file://<redacted>"
                else -> "${uri.scheme ?: "uri"}://<redacted>"
            }
        }.getOrElse { "<invalid-url>" }
        return safe + trailing
    }

    private const val MAX_PATH_LENGTH = 240
}
