package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Test

class PublicSourceClassifierTest {
    @Test
    fun recognizesTikTokDirectPlaybackAsPublicOriginal() {
        assertEquals(
            SourceWatermark.PUBLIC_ORIGINAL,
            PublicSourceClassifier.classify(
                platform = "TikTok",
                formatId = "play_addr_h264",
                formatNote = "Direct video",
                url = "https://example.com/video.mp4",
            ),
        )
        assertEquals(
            SourceWatermark.PUBLIC_ORIGINAL,
            PublicSourceClassifier.classify(
                platform = "TikTok",
                formatId = "h264_1080p_4200000",
                formatNote = null,
                url = "https://example.com/video.mp4",
            ),
        )
    }

    @Test
    fun recognizesExtractorWatermarkFlag() {
        assertEquals(
            SourceWatermark.WATERMARKED,
            PublicSourceClassifier.classify(
                platform = "抖音",
                formatId = "download_addr",
                formatNote = "Download video, watermarked",
                url = "https://example.com/video.mp4",
            ),
        )
    }

    @Test
    fun doesNotClaimUnknownFormatsAreClean() {
        assertEquals(
            SourceWatermark.UNKNOWN,
            PublicSourceClassifier.classify(
                platform = "小红书",
                formatId = "h264-1080p",
                formatNote = null,
                url = "https://example.com/video.mp4",
            ),
        )
        assertEquals(
            SourceWatermark.UNKNOWN,
            PublicSourceClassifier.classify(
                platform = "YouTube",
                formatId = "137",
                formatNote = "1080p",
                url = "https://example.com/video.mp4",
            ),
        )
    }
}
