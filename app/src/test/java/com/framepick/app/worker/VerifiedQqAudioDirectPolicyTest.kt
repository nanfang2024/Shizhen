package com.framepick.app.worker

import com.framepick.app.domain.model.MediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedQqAudioDirectPolicyTest {
    @Test
    fun `accepts verified QQ MP3 CDN URL`() {
        assertTrue(
            VerifiedQqAudioDirectPolicy.canUse(
                mediaUrl = "https://dl.stream.qqmusic.qq.com/M5000042Qime1OFzgh.mp3?sign=test",
                sourceUrl = "https://y.qq.com/n/ryqq/songDetail/002FHVgG4btehE",
                mediaType = MediaType.AUDIO.name,
                format = "mp3",
            ),
        )
    }

    @Test
    fun `accepts verified QQ M4A CDN URL`() {
        assertTrue(
            VerifiedQqAudioDirectPolicy.canUse(
                mediaUrl = "https://dl.stream.qqmusic.qq.com/C4000042Qime1OFzgh.m4a?vkey=test",
                sourceUrl = "https://c.y.qq.com/base/fcgi-bin/u?__=test",
                mediaType = MediaType.AUDIO.name,
                format = "m4a",
            ),
        )
    }

    @Test
    fun `rejects non-audio items`() {
        assertFalse(
            VerifiedQqAudioDirectPolicy.canUse(
                mediaUrl = "https://dl.stream.qqmusic.qq.com/M5000042Qime1OFzgh.mp3",
                sourceUrl = "https://y.qq.com/n/ryqq/songDetail/002FHVgG4btehE",
                mediaType = MediaType.VIDEO.name,
                format = "mp3",
            ),
        )
    }

    @Test
    fun `rejects deceptive QQ CDN host`() {
        assertFalse(
            VerifiedQqAudioDirectPolicy.canUse(
                mediaUrl = "https://dl.stream.qqmusic.qq.com.evil.example/song.mp3",
                sourceUrl = "https://y.qq.com/n/ryqq/songDetail/002FHVgG4btehE",
                mediaType = MediaType.AUDIO.name,
                format = "mp3",
            ),
        )
    }

    @Test
    fun `rejects format and URL extension mismatch`() {
        assertFalse(
            VerifiedQqAudioDirectPolicy.canUse(
                mediaUrl = "https://dl.stream.qqmusic.qq.com/M5000042Qime1OFzgh.mp3",
                sourceUrl = "https://y.qq.com/n/ryqq/songDetail/002FHVgG4btehE",
                mediaType = MediaType.AUDIO.name,
                format = "m4a",
            ),
        )
    }
}
