package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStreamMapperTest {

    private fun video(
        itag: String,
        height: Int,
        codec: String? = "avc1.640028",
        container: String = "mp4",
        bitrate: Int = 4_000_000,
        fps: Int = 30,
    ) = YouTubeStreamCandidate(
        itag = itag,
        url = "https://cache.example.com/videoplayback?itag=$itag",
        container = container,
        codec = codec,
        height = height,
        fps = fps,
        bitrate = bitrate,
        contentLength = 100_000_000L,
    )

    private fun audio(
        itag: String = "140",
        container: String = "m4a",
        codec: String? = "mp4a.40.2",
        bitrate: Int = 129_000,
    ) = YouTubeStreamCandidate(
        itag = itag,
        url = "https://cache.example.com/videoplayback?itag=$itag",
        container = container,
        codec = codec,
        bitrate = bitrate,
        contentLength = 4_000_000L,
    )

    @Test
    fun outputContainerKeepsMp4Family() {
        assertEquals("mp4", YouTubeStreamMapper.outputContainer("mp4"))
        assertEquals("mp4", YouTubeStreamMapper.outputContainer("M4V"))
        assertEquals("mp4", YouTubeStreamMapper.outputContainer("mov"))
    }

    @Test
    fun outputContainerPutsWebmFamilyIntoMkv() {
        assertEquals("mkv", YouTubeStreamMapper.outputContainer("webm"))
        assertEquals("mkv", YouTubeStreamMapper.outputContainer("MKV"))
    }

    @Test
    fun outputContainerHandlesUnknownAndBlank() {
        assertEquals("mp4", YouTubeStreamMapper.outputContainer(""))
        assertEquals("ts", YouTubeStreamMapper.outputContainer("ts"))
    }

    @Test
    fun codecLabelMapsCommonCodecProfiles() {
        assertEquals("H.264", YouTubeStreamMapper.codecLabel("avc1.640028"))
        assertEquals("H.264", YouTubeStreamMapper.codecLabel("h264"))
        assertEquals("VP9", YouTubeStreamMapper.codecLabel("vp09.00.10.08"))
        assertEquals("AV1", YouTubeStreamMapper.codecLabel("av01.0.05M.08"))
        assertEquals("AAC", YouTubeStreamMapper.codecLabel("mp4a.40.2"))
        assertEquals("Opus", YouTubeStreamMapper.codecLabel("opus"))
    }

    @Test
    fun codecLabelReturnsNullForBlankCodec() {
        assertNull(YouTubeStreamMapper.codecLabel(null))
        assertNull(YouTubeStreamMapper.codecLabel(""))
    }

    @Test
    fun selectBestAudioPrefersM4aOverHigherBitrateOpus() {
        val opus = audio(itag = "251", container = "webm", codec = "opus", bitrate = 160_000)
        val m4a = audio(itag = "140", container = "m4a", codec = "mp4a.40.2", bitrate = 129_000)
        assertEquals("140", YouTubeStreamMapper.selectBestAudio(listOf(opus, m4a))?.itag)
    }

    @Test
    fun selectBestAudioBreaksSameContainerTiesByBitrate() {
        val low = audio(itag = "139", bitrate = 70_000)
        val high = audio(itag = "140", bitrate = 129_000)
        assertEquals("140", YouTubeStreamMapper.selectBestAudio(listOf(low, high))?.itag)
    }

    @Test
    fun selectBestAudioSkipsBlankUrls() {
        val blank = audio(itag = "140").copy(url = "")
        val usable = audio(itag = "139", bitrate = 70_000)
        assertEquals("139", YouTubeStreamMapper.selectBestAudio(listOf(blank, usable))?.itag)
    }

    @Test
    fun selectBestAudioReturnsNullWithoutCandidates() {
        assertNull(YouTubeStreamMapper.selectBestAudio(emptyList()))
    }

    @Test
    fun buildItemsPrefersSeparateStreamsForHigherResolutions() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "abc123",
            sourceUrl = "https://youtu.be/abc123",
            muxedCandidates = listOf(video(itag = "22", height = 720)),
            videoOnlyCandidates = listOf(
                video(itag = "137", height = 1080),
                video(itag = "313", height = 2160),
            ),
            audioCandidate = audio(),
        )
        assertEquals(listOf(2160, 1080, 720), items.map { it.height })
        assertEquals(
            listOf(DownloadStrategy.DIRECT_MERGED, DownloadStrategy.DIRECT_MERGED, DownloadStrategy.DIRECT),
            items.map { it.downloadStrategy },
        )
        assertEquals("https://cache.example.com/videoplayback?itag=140", items[0].companionMediaUrl)
        assertNull(items[2].companionMediaUrl)
        assertTrue(items[0].isRecommended)
        items.drop(1).forEach { item -> assertEquals(false, item.isRecommended) }
    }

    @Test
    fun buildItemsFallsBackToMuxedWhenSeparateVideoHasLowerRank() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v2",
            sourceUrl = "https://youtu.be/v2",
            muxedCandidates = listOf(video(itag = "22", height = 720, codec = "avc1.64001f")),
            videoOnlyCandidates = listOf(
                video(itag = "247", height = 720, codec = "vp9", container = "webm"),
            ),
            audioCandidate = audio(),
        )
        assertEquals(1, items.size)
        assertEquals(DownloadStrategy.DIRECT, items[0].downloadStrategy)
        assertEquals("mp4", items[0].format)
    }

    @Test
    fun buildItemsIgnoresVideoOnlyStreamsWhenAudioMissing() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v3",
            sourceUrl = "https://youtu.be/v3",
            muxedCandidates = listOf(video(itag = "22", height = 720)),
            videoOnlyCandidates = listOf(video(itag = "137", height = 1080)),
            audioCandidate = null,
        )
        assertEquals(1, items.size)
        assertEquals(720, items[0].height)
        assertEquals(DownloadStrategy.DIRECT, items[0].downloadStrategy)
    }

    @Test
    fun buildItemsCapsQualityOptionsToSix() {
        val heights = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v4",
            sourceUrl = "https://youtu.be/v4",
            muxedCandidates = emptyList(),
            videoOnlyCandidates = heights.map { video(itag = it.toString(), height = it) },
            audioCandidate = audio(),
        )
        assertEquals(YouTubeStreamMapper.MAX_QUALITY_OPTIONS, items.size)
        assertEquals(2160, items.first().height)
        assertEquals(360, items.last().height)
    }

    @Test
    fun buildItemsPutsWebmSeparateStreamsIntoMkvContainer() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v5",
            sourceUrl = "https://youtu.be/v5",
            muxedCandidates = emptyList(),
            videoOnlyCandidates = listOf(
                video(itag = "303", height = 1080, codec = "vp9", container = "webm"),
            ),
            audioCandidate = audio(),
        )
        assertEquals("mkv", items[0].format)
        assertEquals("yt-np-v5-1080", items[0].id)
        assertEquals(MediaType.VIDEO, items[0].type)
        assertEquals(true, items[0].hasAudio)
        assertTrue(items[0].codecSummary?.contains("VP9") == true)
        assertTrue(items[0].codecSummary?.contains("AAC") == true)
        assertEquals(104_000_000L, items[0].fileSize)
    }

    @Test
    fun buildItemsLabelsQualityWithCodecAndFps() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v6",
            sourceUrl = "https://youtu.be/v6",
            muxedCandidates = emptyList(),
            videoOnlyCandidates = listOf(
                video(itag = "137", height = 1080, codec = "avc1.640028", fps = 60),
            ),
            audioCandidate = audio(),
        )
        assertEquals("1080p · H.264 · 60fps · 本地无损封装", items[0].qualityLabel)
    }

    @Test
    fun buildItemsReturnsEmptyWithoutUsableStreams() {
        val items = YouTubeStreamMapper.buildItems(
            videoId = "v7",
            sourceUrl = "https://youtu.be/v7",
            muxedCandidates = emptyList(),
            videoOnlyCandidates = emptyList(),
            audioCandidate = audio(),
        )
        assertTrue(items.isEmpty())
    }
}
