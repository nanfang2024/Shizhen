package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KuaishouStateExtractorTest {
    @Test
    fun extractsCurrentPhotoManifestWithoutDependingOnObfuscatedRootKey() {
        val result = KuaishouStateExtractor.extract(
            sourceUrl = "https://v.kuaishou.com/example",
            finalUrl = "https://v.m.chenzhongtech.com/fw/photo/abc",
            html = """
                <script>window.INIT_STATE = {
                  "random-obfuscated-key": {
                    "photo": {
                      "photoId": "photo-1",
                      "singlePicture": false,
                      "caption": "公开快手作品",
                      "userName": "测试作者",
                      "width": 1080,
                      "height": 1920,
                      "coverUrls": [{"url": "http://p1.yximgs.com/cover.jpg"}],
                      "manifest": {"adaptationSet": [{"representation": [
                        {
                          "url": "https://video.ndcimgs.com/720.mp4",
                          "width": 720, "height": 1280, "avgBitrate": 500,
                          "fileSize": 1000, "quality": "高清", "mute": false,
                          "videoCodec": "h264"
                        },
                        {
                          "url": "https://video.ndcimgs.com/1080.mp4",
                          "width": 1080, "height": 1920, "avgBitrate": 900,
                          "fileSize": 2000, "quality": "超清", "mute": false,
                          "videoCodec": "h265"
                        }
                      ]}]}
                    }
                  }
                };</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("快手", result.platform)
        assertEquals("公开快手作品", result.title)
        assertEquals("测试作者", result.author)
        assertEquals(3, result.items.size)
        assertEquals(MediaType.VIDEO, result.items[0].type)
        assertEquals(1080, result.items[0].width)
        assertEquals("https://video.ndcimgs.com/1080.mp4", result.items[0].mediaUrl)
        assertTrue(result.items[0].hasAudio == true)
        assertEquals(SourceWatermark.PUBLIC_CLEAN, result.items[0].sourceWatermark)
        assertTrue(result.items[0].watermarkNote!!.contains("网页播放器同源"))
        assertEquals(MediaType.COVER, result.items.last().type)
        assertEquals(SourceWatermark.UNKNOWN, result.items.last().sourceWatermark)
        assertTrue(result.items.last().mediaUrl.startsWith("https://"))
    }

    @Test
    fun picturePostDoesNotTurnCoverIntoVideo() {
        val result = KuaishouStateExtractor.extract(
            sourceUrl = "https://v.kuaishou.com/picture",
            finalUrl = "https://v.m.chenzhongtech.com/fw/photo/picture",
            html = """
                <script>window.INIT_STATE = {"state": {"photo": {
                  "photoId": "picture-1",
                  "singlePicture": true,
                  "width": 1440,
                  "height": 1920,
                  "coverUrls": [
                    {"url": "http://p1.yximgs.com/picture.jpg"},
                    {"url": "http://p2.yximgs.com/picture.jpg"}
                  ]
                }}};</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.type == MediaType.IMAGE })
        assertFalse(result.items.any { it.type == MediaType.VIDEO })
        assertTrue(result.items.all { it.mediaUrl.startsWith("https://") })
    }
}
