package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadOptionEnricherTest {
    @Test
    fun `direct video with verified audio gets local M4A option`() {
        val parsed = parsedWith(
            video(downloadStrategy = DownloadStrategy.DIRECT, hasAudio = true),
        )

        val result = AudioDownloadOptionEnricher.addAudioOption(parsed)
        val audio = result.items.single { it.type == MediaType.AUDIO }

        assertEquals(2, result.items.size)
        assertEquals(DownloadStrategy.DIRECT_AUDIO, audio.downloadStrategy)
        assertEquals("m4a", audio.format)
        assertNull(audio.formatSelector)
        assertFalse(audio.isRecommended)
    }

    @Test
    fun `yt-dlp video gets best public audio selector`() {
        val parsed = parsedWith(
            video(downloadStrategy = DownloadStrategy.YT_DLP, hasAudio = true),
        )

        val audio = AudioDownloadOptionEnricher.addAudioOption(parsed)
            .items.single { it.type == MediaType.AUDIO }

        assertEquals(DownloadStrategy.YT_DLP_AUDIO, audio.downloadStrategy)
        assertEquals("bestaudio[ext=m4a]/bestaudio/best", audio.formatSelector)
        assertTrue(audio.qualityLabel.orEmpty().contains("M4A"))
    }

    @Test
    fun `muted video does not show invalid audio option`() {
        val parsed = parsedWith(
            video(downloadStrategy = DownloadStrategy.DIRECT, hasAudio = false),
        )

        val result = AudioDownloadOptionEnricher.addAudioOption(parsed)

        assertEquals(parsed, result)
        assertTrue(result.items.none { it.type == MediaType.AUDIO })
    }

    @Test
    fun `existing audio item is not duplicated`() {
        val video = video(downloadStrategy = DownloadStrategy.YT_DLP, hasAudio = true)
        val audio = video.copy(
            id = "existing-audio",
            type = MediaType.AUDIO,
            format = "mp3",
        )
        val parsed = parsedWith(video, audio)

        val result = AudioDownloadOptionEnricher.addAudioOption(parsed)

        assertEquals(parsed, result)
        assertEquals(1, result.items.count { it.type == MediaType.AUDIO })
    }

    private fun parsedWith(vararg items: MediaItem) = ParsedMedia(
        sourceUrl = "https://example.com/post/1",
        platform = "示例平台",
        title = "测试媒体",
        author = null,
        thumbnailUrl = null,
        items = items.toList(),
    )

    private fun video(
        downloadStrategy: DownloadStrategy,
        hasAudio: Boolean,
    ) = MediaItem(
        id = "video-1",
        type = MediaType.VIDEO,
        mediaUrl = "https://cdn.example.com/video.mp4",
        format = "mp4",
        width = 1920,
        height = 1080,
        fileSize = null,
        qualityLabel = "1080p",
        downloadStrategy = downloadStrategy,
        downloadSourceUrl = "https://example.com/post/1",
        isRecommended = true,
        hasAudio = hasAudio,
    )
}
