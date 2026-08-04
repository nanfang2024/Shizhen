package com.example.mediaextractor.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun keepsTweetPathButRemovesQueryAndFragment() {
        val result = DiagnosticSanitizer.sanitize(
            "https://x.com/example/status/123?token=secret#details",
        )

        assertTrue(result.contains("x.com/example/status/123"))
        assertTrue(result.contains("?<redacted>"))
        assertTrue(result.contains("#<redacted>"))
        assertFalse(result.contains("secret"))
    }

    @Test
    fun removesCredentialsAndContentUriDetails() {
        val result = DiagnosticSanitizer.sanitize(
            "Authorization=Bearer-secret Cookie=session-id content://provider/private/document/42",
        )

        assertTrue(result.contains("Authorization=<redacted>"))
        assertTrue(result.contains("Cookie=<redacted>"))
        assertTrue(result.contains("content://provider/<redacted>"))
        assertFalse(result.contains("session-id"))
        assertFalse(result.contains("document/42"))
    }
}
