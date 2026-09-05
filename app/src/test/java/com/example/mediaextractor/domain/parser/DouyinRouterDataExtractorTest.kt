package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinRouterDataExtractorTest {
    @Test
    fun extractsAllPublicImagesAndIgnoresWatermarkedDownloadList() {
        val result = DouyinRouterDataExtractor.extract(
            sourceUrl = "https://v.douyin.com/example/",
            finalUrl = "https://www.iesdouyin.com/share/video/123/",
            html = """
                <html><body><script>
                window._ROUTER_DATA = {
                  "loaderData": {
                    "video_page": {
                      "videoInfoRes": {
                        "item_list": [{
                          "aweme_id": "123",
                          "desc": "壁纸图集",
                          "author": {"nickname": "公开作者"},
                          "music": {"play_url": {"url_list": ["https://cdn.example/music.mp3"]}},
                          "images": [
                            {
                              "uri": "first",
                              "url_list": ["https://image.example/first~2559x1524-q80.webp?sig=ok"],
                              "download_url_list": ["https://image.example/first-water.webp?sig=water"],
                              "width": 2559,
                              "height": 1524
                            },
                            {
                              "uri": "second",
                              "url_list": ["https://image.example/second~2559x1525-q80.jpeg?sig=ok"],
                              "download_url_list": ["https://image.example/second-water.jpeg?sig=water"],
                              "width": 2559,
                              "height": 1525
                            }
                          ]
                        }]
                      }
                    }
                  }
                };
                </script></body></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("壁纸图集", result.title)
        assertEquals("公开作者", result.author)
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.type == MediaType.IMAGE })
        assertTrue(result.items.all { it.sourceWatermark == SourceWatermark.PUBLIC_ORIGINAL })
        assertTrue(result.items.all { it.width == 2559 })
        assertTrue(result.items.all {
            it.downloadSourceUrl == "https://www.iesdouyin.com/share/video/123/"
        })
        assertFalse(result.items.any { it.mediaUrl.contains("-water") })
        assertFalse(result.items.any { it.type == MediaType.AUDIO })
    }

    @Test
    fun convertsPublicPlaywmEndpointToVerifiedClean720pVideoSource() {
        val result = DouyinRouterDataExtractor.extract(
            sourceUrl = "https://v.douyin.com/video-example/",
            finalUrl = "https://www.iesdouyin.com/share/video/456/",
            html = """
                <script>window._ROUTER_DATA = {
                  "loaderData": {
                    "video_page": {
                      "videoInfoRes": {
                        "item_list": [{
                          "aweme_id": "456",
                          "desc": "公开视频",
                          "author": {"nickname": "测试作者"},
                          "images": [],
                          "video": {
                            "width": 3840,
                            "height": 2160,
                            "duration": 60100,
                            "play_addr": {
                              "uri": "video-id",
                              "url_list": [
                                "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=video-id&ratio=1080p&watermark=1"
                              ]
                            },
                            "cover": {
                              "width": 720,
                              "height": 405,
                              "url_list": ["https://image.example/cover.webp"]
                            }
                          }
                        }]
                      }
                    }
                  }
                };</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("公开视频", result.title)
        assertEquals("测试作者", result.author)
        assertEquals(2, result.items.size)
        val video = result.items.first()
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals(SourceWatermark.PUBLIC_CLEAN, video.sourceWatermark)
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        assertEquals("mp4", video.format)
        assertTrue(video.isRecommended)
        assertTrue(video.hasAudio == true)
        assertTrue(video.mediaUrl.contains("/aweme/v1/play/"))
        assertTrue(video.mediaUrl.contains("ratio=720p"))
        assertFalse(video.mediaUrl.contains("playwm"))
        assertFalse(video.mediaUrl.contains("watermark="))
        assertEquals("https://www.iesdouyin.com/share/video/456/", video.downloadSourceUrl)
        assertEquals(MediaType.COVER, result.items.last().type)
        assertEquals(
            "https://www.iesdouyin.com/share/video/456/",
            result.items.last().downloadSourceUrl,
        )
    }

    @Test
    fun returnsWatermarkedItemsWhenOnlyPlaywmEndpointsSurviveRewrite() {
        val result = DouyinRouterDataExtractor.extract(
            sourceUrl = "https://v.douyin.com/watermarked-example/",
            finalUrl = "https://www.iesdouyin.com/share/video/789/",
            html = """
                <script>window._ROUTER_DATA = {
                  "loaderData": {
                    "video_page": {
                      "videoInfoRes": {
                        "item_list": [{
                          "aweme_id": "789",
                          "desc": "仅带水印视频",
                          "author": {"nickname": "测试作者"},
                          "images": [],
                          "video": {
                            "width": 1080,
                            "height": 1920,
                            "duration": 30100,
                            "play_addr": {
                              "uri": "video-id",
                              "url_list": [
                                "https://aweme.snssdk.com/aweme/v1/playwm/?video_id=video-id&ratio=1080p&watermark=1&from=playwm_share"
                              ]
                            },
                            "cover": {
                              "width": 720,
                              "height": 1280,
                              "url_list": ["https://image.example/cover.webp"]
                            }
                          }
                        }]
                      }
                    }
                  }
                };</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        val videos = result.items.filter { it.type == MediaType.VIDEO }
        assertEquals(1, videos.size)
        val video = videos.first()
        assertEquals(SourceWatermark.WATERMARKED, video.sourceWatermark)
        assertTrue(video.mediaUrl.contains("playwm"))
        assertTrue(video.qualityLabel!!.contains("带水印"))
        assertEquals("公开页面仅提供带水印播放源；已禁用下载，可预览确认。", video.watermarkNote)
        assertFalse(video.allowWatermarkedDownload)
        assertEquals(MediaType.COVER, result.items.last().type)
    }

    @Test
    fun doesNotTreatPhotoPostBackgroundMusicAsVideo() {
        val result = DouyinRouterDataExtractor.extract(
            sourceUrl = "https://v.douyin.com/example/",
            finalUrl = "https://www.iesdouyin.com/share/video/123/",
            html = """
                <script>window._ROUTER_DATA = {
                  "item_list": [{
                    "video": {
                      "play_addr": {
                        "url_list": ["https://music.example/background.mp3"]
                      },
                      "width": 2559,
                      "height": 1524,
                      "duration": 0
                    }
                  }]
                };</script>
            """.trimIndent(),
        )

        assertNull(result)
    }
}
