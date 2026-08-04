package com.example.mediaextractor.domain.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XStructuredMediaParserTest {
    private val parser = XStructuredMediaParser(OkHttpClient())

    @Test
    fun handlesOnlySingleStatusUrls() {
        assertTrue(parser.canHandle("https://x.com/example/status/123456789"))
        assertTrue(parser.canHandle("https://twitter.com/example/status/123456789?s=20"))
        assertFalse(parser.canHandle("https://x.com/home"))
        assertFalse(parser.canHandle("https://x.com/example"))
        assertFalse(parser.canHandle("https://example.com/example/status/123456789"))
    }
}
