package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark
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

    @Test
    fun extractsVideoMetaFromViewDetailAndWbiShapes() {
        val detail = BilibiliStateExtractor.extractVideoMetaFromViewJson(
            """
            {"code":0,"data":{"View":{
              "bvid":"BV1Xyt76VEnk","aid":1,"cid":41511813191,"title":"公开测试标题",
              "pic":"http://i0.hdslb.com/bfs/archive/cover.jpg",
              "owner":{"name":"测试UP主"},"duration":132,
              "pages":[{"cid":41511813191,"page":1,"part":"P1"}]
            }}}
            """.trimIndent(),
        )
        requireNotNull(detail)
        assertEquals("BV1Xyt76VEnk", detail.bvid)
        assertEquals(41511813191L, detail.cid)
        assertEquals("公开测试标题", detail.title)
        assertEquals("https://i0.hdslb.com/bfs/archive/cover.jpg", detail.picUrl)
        assertEquals("测试UP主", detail.ownerName)
        assertEquals(132L, detail.durationSeconds)

        val wbi = BilibiliStateExtractor.extractVideoMetaFromViewJson(
            """
            {"code":0,"data":{
              "bvid":"BV1Xyt76VEnk","cid":41511813191,"title":"直挂形状",
              "pic":"https://i0.hdslb.com/bfs/archive/cover.jpg","owner":{"name":"UP"},"duration":132
            }}
            """.trimIndent(),
        )
        requireNotNull(wbi)
        assertEquals(41511813191L, wbi.cid)
        assertEquals("直挂形状", wbi.title)

        val cidFromPages = BilibiliStateExtractor.extractVideoMetaFromViewJson(
            """
            {"code":0,"data":{"View":{"bvid":"BV1Xyt76VEnk","title":"无顶层cid",
              "pages":[{"cid":999,"page":1}]}}}
            """.trimIndent(),
        )
        assertEquals(999L, cidFromPages?.cid)

        assertNull(BilibiliStateExtractor.extractVideoMetaFromViewJson("""{"code":-412,"data":{}}"""))
        assertNull(BilibiliStateExtractor.extractVideoMetaFromViewJson("""{"code":0,"data":{"View":{"bvid":"BV1x"}}}"""))
        assertNull(BilibiliStateExtractor.extractVideoMetaFromViewJson("not json"))
    }

    @Test
    fun extractsMinimalMetaFromPagelist() {
        val meta = BilibiliStateExtractor.extractVideoMetaFromPagelist(
            bvid = "BV1Xyt76VEnk",
            json = """{"code":0,"data":[{"cid":41511813191,"page":1,"part":"分P标题","duration":132}]}""",
        )
        requireNotNull(meta)
        assertEquals("BV1Xyt76VEnk", meta.bvid)
        assertEquals(41511813191L, meta.cid)
        assertEquals("分P标题", meta.title)
        assertEquals(132L, meta.durationSeconds)
        assertNull(meta.picUrl)

        assertNull(BilibiliStateExtractor.extractVideoMetaFromPagelist("BV1x", """{"code":0,"data":[]}"""))
        assertNull(BilibiliStateExtractor.extractVideoMetaFromPagelist("BV1x", """{"code":-404,"data":[]}"""))
    }

    @Test
    fun buildQualityItemsKeepsBackupUrlAndLabelsSegments() {
        val pageUrl = "https://www.bilibili.com/video/BV1Xyt76VEnk"
        val single = BilibiliStateExtractor.buildQualityItems(
            pageUrl,
            """
            {"code":0,"data":{"quality":64,"durl":[
              {"url":"https://cn.bilivideo.com/a.mp4?e=1","backup_url":["https://mirror.bilivideo.com/a.mp4?e=1"],"size":124874790,"length":1373277}
            ]}}
            """.trimIndent(),
        )
        requireNotNull(single)
        assertEquals(1, single.size)
        assertEquals("https://mirror.bilivideo.com/a.mp4?e=1", single.first().backupUrl)
        assertEquals("720P 高清 · 匿名公开档位", single.first().qualityLabel)
        assertEquals(124874790L, single.first().fileSize)

        val multi = BilibiliStateExtractor.buildQualityItems(
            pageUrl,
            """
            {"code":0,"data":{"quality":64,"durl":[
              {"url":"https://cn.bilivideo.com/p1.mp4","size":1},
              {"url":"https://cn.bilivideo.com/p2.mp4","size":2}
            ]}}
            """.trimIndent(),
        )
        requireNotNull(multi)
        assertEquals(2, multi.size)
        assertEquals("720P 高清 · 匿名公开档位 · 第1/2段", multi[0].qualityLabel)
        assertEquals("720P 高清 · 匿名公开档位 · 第2/2段", multi[1].qualityLabel)
        assertEquals(pageUrl, multi[0].downloadSourceUrl)

        assertEquals(
            0,
            BilibiliStateExtractor.buildQualityItems(
                pageUrl,
                """{"code":-404,"data":{"durl":[]}}""",
            ).size,
        )
    }

    @Test
    fun buildQualityItemsPrefersOfficialCdnOverP2pEdge() {
        val pageUrl = "https://www.bilibili.com/video/BV1Xyt76VEnk"
        val items = BilibiliStateExtractor.buildQualityItems(
            pageUrl,
            """
            {"code":0,"data":{"quality":64,"durl":[
              {"url":"https://ize3261c.edge.mountaintoys.cn/upgcxcode/v.mp4?e=1",
               "backup_url":["https://upos-sz-mirrorhw.bilivideo.com/v.mp4?e=1",
                             "https://cn-gdfs-cc-02-21.bilivideo.com/v.mp4?e=1"]}
            ]}}
            """.trimIndent(),
        )
        requireNotNull(items)
        assertEquals(1, items.size)
        assertEquals("https://upos-sz-mirrorhw.bilivideo.com/v.mp4?e=1", items.first().mediaUrl)
        assertEquals("https://cn-gdfs-cc-02-21.bilivideo.com/v.mp4?e=1", items.first().backupUrl)

        val allOfficial = BilibiliStateExtractor.buildQualityItems(
            pageUrl,
            """
            {"code":0,"data":{"quality":64,"durl":[
              {"url":"https://cn-gdfs-cc-02-21.bilivideo.com/a.mp4?e=1",
               "backup_url":["https://upos-sz-mirrorhw.bilivideo.com/a.mp4?e=1"]}
            ]}}
            """.trimIndent(),
        )
        requireNotNull(allOfficial)
        assertEquals("https://cn-gdfs-cc-02-21.bilivideo.com/a.mp4?e=1", allOfficial.first().mediaUrl)
        assertEquals("https://upos-sz-mirrorhw.bilivideo.com/a.mp4?e=1", allOfficial.first().backupUrl)
    }

    @Test
    fun extractsBvidFromEveryKnownUrlForm() {
        assertEquals("BV1Xyt76VEnk", BilibiliUrlDetector.extractBvid("https://www.bilibili.com/video/BV1Xyt76VEnk/"))
        assertEquals("BV1Xyt76VEnk", BilibiliUrlDetector.extractBvid("https://www.bilibili.com/video/BV1Xyt76VEnk?p=2&spm_id_from=333"))
        assertEquals("BV1Xyt76VEnk", BilibiliUrlDetector.extractBvid("https://m.bilibili.com/video/BV1Xyt76VEnk"))
        assertNull(BilibiliUrlDetector.extractBvid("https://b23.tv/akvtBeX"))
        assertNull(BilibiliUrlDetector.extractBvid("https://www.bilibili.com/video/av123"))
    }
}
