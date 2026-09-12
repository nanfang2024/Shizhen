package com.framepick.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpBotCheckPolicyTest {
    @Test
    fun detectsYouTubeBotCheckWording() {
        assertTrue(
            YtDlpBotCheckPolicy.isBotCheck(
                url = "https://www.youtube.com/watch?v=abc123",
                message = "ERROR: [youtube] abc123: Sign in to confirm you're not a bot.",
            ),
        )
        assertTrue(
            YtDlpBotCheckPolicy.isBotCheck(
                url = "https://youtu.be/abc123",
                message = "Sign in to confirm that you're not a bot",
            ),
        )
    }

    @Test
    fun ignoresOtherPlatformsAndUnrelatedErrors() {
        val botMessage = "Sign in to confirm you're not a bot."
        assertFalse(YtDlpBotCheckPolicy.isBotCheck("https://vimeo.com/123", botMessage))
        assertFalse(
            YtDlpBotCheckPolicy.isBotCheck(
                url = "https://www.youtube.com/watch?v=abc123",
                message = "ERROR: Requested format is not available",
            ),
        )
        assertFalse(YtDlpBotCheckPolicy.isBotCheck("https://www.youtube.com/watch?v=abc123", null))
    }

    @Test
    fun offersNonWebPlayerClientRetries() {
        val clients = YtDlpBotCheckPolicy.playerClientRetries()
        assertTrue(clients.isNotEmpty())
        assertTrue(clients.none { it == "web" })
        assertEquals("tv", clients.first())
        assertTrue("mweb" in clients)
        assertTrue("web_safari" in clients)
        assertTrue("android_vr" in clients)
        assertTrue("ios" in clients && "android" in clients)
    }
}
