package com.framepick.app.domain.parser

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeNewPipeMediaParserTest {
    private val parser = YouTubeNewPipeMediaParser(OkHttpClient())

    @Test
    fun canHandleMatchesYouTubeHosts() {
        assertTrue(parser.canHandle("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://youtu.be/dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertTrue(parser.canHandle("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun canHandleRejectsLookalikeAndOtherHosts() {
        assertFalse(parser.canHandle("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(parser.canHandle("https://youtube.com.evil.example/watch?v=x"))
        assertFalse(parser.canHandle("https://www.bilibili.com/video/BV1xx"))
        assertFalse(parser.canHandle("https://v.douyin.com/abc123/"))
    }
}
