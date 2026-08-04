package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMusicAudioResponsePolicyTest {
    @Test
    fun `accepts Kugou MP3 returned as octet stream when ID3 signature matches`() {
        assertTrue(
            PublicMusicAudioResponsePolicy.accepts(
                mime = "application/octet-stream",
                url = "https://sharefs.kugou.com/public/preview.mp3",
                sample = "ID3\u0003\u0000\u0000".toByteArray(),
            ),
        )
    }

    @Test
    fun `accepts MP3 frame sync when generic binary MIME is missing`() {
        assertTrue(
            PublicMusicAudioResponsePolicy.accepts(
                mime = null,
                url = "https://sharefs.tx.kugou.com/public/preview.mp3",
                sample = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64),
            ),
        )
    }

    @Test
    fun `rejects HTML disguised as octet stream MP3`() {
        assertFalse(
            PublicMusicAudioResponsePolicy.accepts(
                mime = "application/octet-stream",
                url = "https://sharefs.kugou.com/public/preview.mp3",
                sample = "<html>expired".toByteArray(),
            ),
        )
    }

    @Test
    fun `rejects non-audio MIME even when URL ends with MP3`() {
        assertFalse(
            PublicMusicAudioResponsePolicy.accepts(
                mime = "text/html",
                url = "https://sharefs.kugou.com/public/preview.mp3",
                sample = "ID3".toByteArray(),
            ),
        )
    }
}
