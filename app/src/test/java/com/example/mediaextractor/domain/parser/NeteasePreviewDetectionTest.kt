package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePreviewDetectionTest {
    @Test
    fun `estimates CBR MP3 duration from first frame and content length`() {
        val header128Kbps = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64)

        val seconds = Mp3DurationEstimator.estimateSeconds(header128Kbps, 960_000L)

        assertTrue(seconds != null && seconds in 59.9..60.1)
    }

    @Test
    fun `skips ID3 payload before reading MP3 frame bitrate`() {
        val sample = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            4, 0, 0, 0, 0, 0, 4,
            0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64,
            0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64,
        )

        val seconds = Mp3DurationEstimator.estimateSeconds(sample, 960_000L)

        assertTrue(seconds != null && seconds in 59.9..60.1)
    }

    @Test
    fun `marks a short public file as preview when page declares a much longer song`() {
        assertTrue(
            NeteasePreviewDetector.isPreview(
                pageDurationSeconds = 240,
                estimatedAudioSeconds = 60.0,
            ),
        )
    }

    @Test
    fun `does not mark matching or unknown duration as preview`() {
        assertFalse(NeteasePreviewDetector.isPreview(207, 206.8))
        assertFalse(NeteasePreviewDetector.isPreview(null, 60.0))
        assertFalse(NeteasePreviewDetector.isPreview(240, null))
    }

    @Test
    fun `preview classification reaches final media item presentation`() {
        val metadata = NeteaseSongMetadata(
            songId = "12345",
            title = "测试歌曲",
            author = "测试歌手",
            coverUrl = null,
            durationSeconds = 240,
        )
        val item = ResolvedNeteaseAudio(
            url = "https://m801.music.126.net/public-preview.mp3",
            fileSize = 960_000L,
            previewOnly = true,
        ).toMediaItem(
            metadata = metadata,
            outerUrl = "https://music.163.com/song/media/outer/url?id=12345.mp3",
        )

        assertTrue(item.isPreviewOnly)
        assertTrue(item.qualityLabel.orEmpty().contains("仅试听片段"))
        assertTrue(item.watermarkNote.orEmpty().contains("明显短于"))
        assertFalse(item.isRecommended)
    }
}
