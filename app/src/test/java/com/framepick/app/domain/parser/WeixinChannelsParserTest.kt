package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeixinChannelsParserTest {
    private lateinit var server: MockWebServer
    private lateinit var parser: WeixinChannelsParser

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        parser = WeixinChannelsParser(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun recognizesChannelsLinksOnly() {
        assertTrue(parser.canHandle("https://weixin.qq.com/sph/AucSKJBKFq"))
        assertTrue(parser.canHandle("https://channels.weixin.qq.com/web/pages/feed?eid=x"))
        // WeChat article links share the weixin.qq.com host but are not channels.
        assertFalse(parser.canHandle("https://mp.weixin.qq.com/s/abcDEF"))
        assertFalse(parser.canHandle("https://weixin.qq.com/cgi-bin/read"))
        assertFalse(parser.canHandle("https://www.bilibili.com/video/BV1Xyt76VEnk"))
    }

    @Test
    fun failsWithExplicitExplanationOnClientOnlyShellPage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(301)
                .setHeader("Location", "/finder-preview/pages/sph?id=AucSKJBKFq"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(CLIENT_ONLY_SHELL_HTML))

        val result = parser.parse(server.url("/sph/AucSKJBKFq").toString())

        val failure = result.exceptionOrNull()
        assertTrue(failure is MediaParseException)
        val parseFailure = failure as MediaParseException
        assertEquals(MediaParseException.Reason.ACCESS_RESTRICTED, parseFailure.reason)
        assertTrue(parseFailure.message!!.contains("微信客户端"))
    }

    @Test
    fun picksUpPublicMediaUrlIfPageEverEmbedsOne() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                <html><head><title>视频号</title></head><body>
                <script>window.__data = {"media":{"play_url":"https:\/\/finder.video.qq.com\/251\/20304\/abc.mp4?dis_k=abc&dis_t=1"}};
                </script>
                </body></html>
                """.trimIndent(),
            ),
        )

        val result = parser.parse(server.url("/sph/AucSKJBKFq").toString())

        val parsed = result.getOrThrow()
        assertEquals("微信视频号", parsed.platform)
        val video = parsed.items.single()
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals(
            "https://finder.video.qq.com/251/20304/abc.mp4?dis_k=abc&dis_t=1",
            video.mediaUrl,
        )
    }

    @Test
    fun extractorIgnoresPlainPagesWithoutMedia() {
        assertNull(WeixinChannelsPageExtractor.findDirectMediaUrl(CLIENT_ONLY_SHELL_HTML))
        assertNull(
            WeixinChannelsPageExtractor.findDirectMediaUrl(
                "<html>finder-preview pages carry no finder.video.qq.com link</html>",
            ),
        )
    }

    private companion object {
        /** Live shell page captured from channels.weixin.qq.com/finder-preview (2026-09). */
        val CLIENT_ONLY_SHELL_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
              <head>
                <meta charset="utf-8" />
                <title>视频号</title>
                <script type="module" crossorigin src="//res.wx.qq.com/t/wx_fed/finder/web/finder-preview/res/assets/feed.408a968c.js"></script>
              </head>
              <body>
                <div id="app"></div>
                <script>
                  (function() {
                    window.WeixinJSBridge.invoke("openFinderFeed", {}, function() {});
                  })();
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}
