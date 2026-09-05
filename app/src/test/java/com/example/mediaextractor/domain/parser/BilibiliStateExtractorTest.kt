package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.DownloadStrategy
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture structure source: publicly documented Bilibili web shapes
 * (`__INITIAL_STATE__` on video/opus pages, anonymous `playurl` responses).
 * The sandbox network exit is blocked by Bilibili risk control, so real page
 * capture was not possible (Phase 1 design decision D5).
 */
class BilibiliStateExtractorTest {
    @Test
    fun extractsVideoMetaFromInitialStateWithTrailingScriptCode() {
        val meta = BilibiliStateExtractor.extractVideoMeta(
            sourceUrl = "https://www.bilibili.com/video/BV1Test123/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__={
                  "videoData": {
                    "bvid": "BV1Test123",
                    "aid": 111,
                    "cid": 222,
                    "title": "公开测试视频",
                    "pic": "https://i0.hdslb.com/bfs/archive/cover.jpg",
                    "owner": {"name": "测试UP主", "mid": 333},
                    "duration": 620,
                    "pages": [{"cid": 222, "page": 1, "part": "P1"}]
                  },
                  "loginInfo": {}
                };(function(){var s;(s=document.currentScript)||0;s.parentNode.removeChild(s);}());</script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(meta)
        assertEquals("BV1Test123", meta.bvid)
        assertEquals(222L, meta.cid)
        assertEquals("公开测试视频", meta.title)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", meta.picUrl)
        assertEquals("测试UP主", meta.ownerName)
        assertEquals(620L, meta.durationSeconds)
    }

    @Test
    fun returnsNullWhenPageHasNoReadableVideoData() {
        val meta = BilibiliStateExtractor.extractVideoMeta(
            sourceUrl = "https://www.bilibili.com/video/BV1None/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__={"loginInfo": {}, "tags": []};</script>
                </body></html>
            """.trimIndent(),
        )
        assertNull(meta)

        val noState = BilibiliStateExtractor.extractVideoMeta(
            sourceUrl = "https://www.bilibili.com/video/BV1None/",
            html = "<html><body>风控页</body></html>",
        )
        assertNull(noState)
    }

    @Test
    fun buildsDirectItemsFromAnonymousPlayUrlResponse() {
        val items = BilibiliStateExtractor.buildQualityItems(
            videoPageUrl = "https://www.bilibili.com/video/BV1Test123/",
            playUrlJson = """
                {
                  "code": 0,
                  "message": "0",
                  "data": {
                    "quality": 32,
                    "format": "mp4",
                    "timelength": 620000,
                    "accept_quality": [16, 32, 64],
                    "accept_format": "mp4,hdmp4,mp4",
                    "durl": [
                      {
                        "order": 1,
                        "length": 620000,
                        "size": 40960000,
                        "url": "https://upos-sz-mirror08c.bilivideo.com/upgcxcode/xx/32.mp4?e=ut8enr4",
                        "backup_url": ["https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/xx/32.mp4"]
                      }
                    ]
                  }
                }
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        val video = items[0]
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals("https://upos-sz-mirror08c.bilivideo.com/upgcxcode/xx/32.mp4?e=ut8enr4", video.mediaUrl)
        assertEquals("mp4", video.format)
        assertEquals(480, video.height)
        assertEquals(40960000L, video.fileSize)
        assertTrue(video.qualityLabel!!.contains("480P"))
        assertEquals(DownloadStrategy.DIRECT, video.downloadStrategy)
        assertEquals("https://www.bilibili.com/video/BV1Test123/", video.downloadSourceUrl)
        assertTrue(video.isRecommended)
        assertTrue(video.hasAudio == true)
        assertEquals(SourceWatermark.PUBLIC_CLEAN, video.sourceWatermark)
        assertTrue(video.watermarkNote!!.contains("匿名公开"))
    }

    @Test
    fun returnsEmptyItemsForFailedOrEmptyPlayUrlResponse() {
        val failed = BilibiliStateExtractor.buildQualityItems(
            videoPageUrl = "https://www.bilibili.com/video/BV1Test123/",
            playUrlJson = """{"code": -404, "message": "啥都木有", "ttl": 1}""",
        )
        assertTrue(failed.isEmpty())

        val noDurl = BilibiliStateExtractor.buildQualityItems(
            videoPageUrl = "https://www.bilibili.com/video/BV1Test123/",
            playUrlJson = """{"code": 0, "data": {"quality": 32}}""",
        )
        assertTrue(noDurl.isEmpty())

        val broken = BilibiliStateExtractor.buildQualityItems(
            videoPageUrl = "https://www.bilibili.com/video/BV1Test123/",
            playUrlJson = "not json",
        )
        assertTrue(broken.isEmpty())
    }

    @Test
    fun extractsOpusImagesFromDrawItem() {
        val images = BilibiliStateExtractor.extractOpusImages(
            sourceUrl = "https://www.bilibili.com/opus/999/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__={
                  "item": {
                    "id_str": "999",
                    "modules": {
                      "module_dynamic": {
                        "drawItem": [
                          {"pic": {"src": "https://i0.hdslb.com/bfs/new_dyn/1.jpg"}},
                          {"pic": {"src": "https://i0.hdslb.com/bfs/new_dyn/2.jpg"}}
                        ]
                      }
                    }
                  }
                };</script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(images)
        assertEquals(2, images.size)
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/1.jpg", images[0])
        assertEquals("https://i0.hdslb.com/bfs/new_dyn/2.jpg", images[1])
    }

    @Test
    fun returnsNullForOpusPageWithoutReadableImages() {
        val images = BilibiliStateExtractor.extractOpusImages(
            sourceUrl = "https://www.bilibili.com/opus/111/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__={"item": {"modules": {"module_dynamic": {}}}};</script>
                </body></html>
            """.trimIndent(),
        )
        assertNull(images)
    }

    @Test
    fun readsEffectiveQualityFromPlayUrlResponse() {
        assertEquals(
            32,
            BilibiliStateExtractor.playUrlQuality("""{"code": 0, "data": {"quality": 32}}"""),
        )
        assertNull(BilibiliStateExtractor.playUrlQuality("""{"code": -404, "data": {"quality": 32}}"""))
        assertNull(BilibiliStateExtractor.playUrlQuality("not json"))
    }

    @Test
    fun urlDetectorMatchesVideoOpusBangumiAndShortDomainOnly() {
        assertTrue(BilibiliUrlDetector.isVideoPage("https://www.bilibili.com/video/BV1GJ411x7h7/"))
        assertTrue(BilibiliUrlDetector.isVideoPage("https://m.bilibili.com/video/BV1Test/"))
        assertTrue(BilibiliUrlDetector.isVideoPage("https://www.bilibili.com/opus/999"))
        assertTrue(BilibiliUrlDetector.isVideoPage("https://www.bilibili.com/bangumi/play/ep123"))
        assertTrue(BilibiliUrlDetector.isVideoPage("https://b23.tv/abc123"))

        // Non-media paths on the same domain must not be claimed.
        assertEquals(false, BilibiliUrlDetector.isVideoPage("https://www.bilibili.com/read/cv123"))
        assertEquals(false, BilibiliUrlDetector.isVideoPage("https://space.bilibili.com/123"))
        assertEquals(false, BilibiliUrlDetector.isVideoPage("https://www.example.com/video/x"))
    }
}
