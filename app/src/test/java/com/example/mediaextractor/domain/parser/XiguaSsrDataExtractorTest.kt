package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiguaSsrDataExtractorTest {
    @Test
    fun decryptsCryptoJsReversedPublicPlaybackUrl() {
        assertEquals(TEST_MEDIA_URL, OpenSslCryptoJsAes.decryptReversed(TEST_ENCRYPTED_URL))
    }

    @Test
    fun extractsOnlyTheCurrentXiguaWorkFromSsrState() {
        val html = """
            <html><head></head><body>
              <script>
                window._SSR_DATA = {
                  "detail": {
                    "videoData": {
                      "result": {
                        "group_type": "xigua_video",
                        "gid": "7651243563103112484",
                        "title": "测试西瓜作品",
                        "media_user": {"screen_name": "测试作者"},
                        "cover_image_url": "https://media.example/cover.jpg",
                        "url": "$TEST_ENCRYPTED_URL"
                      }
                    },
                    "related": [{"url": "https://media.example/unrelated.mp4"}]
                  }
                };
              </script>
            </body></html>
        """.trimIndent()

        val parsed = XiguaSsrDataExtractor.extract(
            sourceUrl = "https://v.douyin.com/N0oHncGQg4Y/",
            finalUrl = "https://www.iesdouyin.com/xg/video/7651243563103112484/",
            html = html,
        )

        assertNotNull(parsed)
        assertEquals("西瓜视频", parsed?.platform)
        assertEquals("测试西瓜作品", parsed?.title)
        assertEquals("测试作者", parsed?.author)
        assertEquals(TEST_MEDIA_URL, parsed?.items?.first()?.mediaUrl)
        assertEquals(MediaType.VIDEO, parsed?.items?.first()?.type)
        assertEquals(2, parsed?.items?.size)
        assertTrue(parsed?.items?.none { it.mediaUrl.contains("unrelated") } == true)
    }

    @Test
    fun ignoresSamePayloadWhenFinalPageIsNormalDouyin() {
        val html = """
            <script>window._SSR_DATA = {
              "videoData":{"result":{"group_type":"xigua_video","url":"$TEST_ENCRYPTED_URL"}}
            };</script>
        """.trimIndent()

        assertNull(
            XiguaSsrDataExtractor.extract(
                "https://v.douyin.com/example/",
                "https://www.douyin.com/video/123",
                html,
            ),
        )
    }

    @Test
    fun buildsCanonicalFallbacksWithoutUnstableShareParameters() {
        assertEquals(
            listOf(
                "https://www.iesdouyin.com/xg/video/7651243563103112484/?app=video_article",
                "https://m.ixigua.com/xg/video/7651243563103112484/?app=video_article",
                "https://m.ixigua.com/video/7651243563103112484",
                "https://m.ixigua.com/dx/7651243563103112484",
            ),
            XiguaFallbackUrls.candidates(
                "https://www.iesdouyin.com/xg/video/7651243563103112484/" +
                    "?timestamp=1785409089&utm_source=copy_link",
            ),
        )
    }

    @Test
    fun doesNotBuildXiguaFallbackForNormalDouyinPage() {
        assertTrue(
            XiguaFallbackUrls.candidates("https://www.iesdouyin.com/share/video/123/")
                .isEmpty(),
        )
    }

    private companion object {
        const val TEST_MEDIA_URL =
            "https://media.example/video.mp4?mime_type=video_mp4"
        const val TEST_ENCRYPTED_URL =
            "=gala6k8j/sA4hiagQA3rqb7KxtdBYX/jpCXx/cZLkoau8uOTHCvTkqEXvzTwz7VWmivSCtu/4hUDtJUHvyiQnEO3YTN0MjMx81XkVGdsF2U"
    }
}
