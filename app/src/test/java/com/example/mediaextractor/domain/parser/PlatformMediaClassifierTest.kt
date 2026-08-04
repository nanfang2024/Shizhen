package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMediaClassifierTest {
    @Test
    fun recognizesTwitterTweetVideoAsAnimatedGifOnlyOnTwitter() {
        val url = "https://video.twimg.com/tweet_video/example.mp4"

        assertTrue(PlatformMediaClassifier.isTwitterAnimatedGif("X / Twitter", listOf(url)))
        assertFalse(PlatformMediaClassifier.isTwitterAnimatedGif("通用网页", listOf(url)))
        assertFalse(
            PlatformMediaClassifier.isTwitterAnimatedGif(
                "X / Twitter",
                listOf("https://video.twimg.com/ext_tw_video/example.mp4"),
            ),
        )
    }
}
