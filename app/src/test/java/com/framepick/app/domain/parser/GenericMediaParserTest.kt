package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenericMediaParserTest {
    private lateinit var server: MockWebServer
    private lateinit var parser: GenericMediaParser

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        parser = GenericMediaParser(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesDirectVideoFromRealResponseHeaders() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Content-Length", "4")
                .setBody("data"),
        )

        val result = parser.parse(server.url("/sample.mp4").toString()).getOrThrow()

        assertEquals(MediaType.VIDEO, result.items.single().type)
        assertEquals("mp4", result.items.single().format)
        assertEquals(4L, result.items.single().fileSize)
    }

    @Test
    fun parsesOpenGraphMediaWithoutHardcodedResults() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html><head>
                      <meta property="og:title" content="公开示例" />
                      <meta property="og:video" content="/media/video.mp4" />
                      <meta property="og:image" content="/media/cover.jpg" />
                      <meta name="author" content="测试作者" />
                    </head><body></body></html>
                    """.trimIndent(),
                ),
        )

        val result = parser.parse(server.url("/post").toString()).getOrThrow()

        assertEquals("公开示例", result.title)
        assertEquals("测试作者", result.author)
        assertTrue(result.items.any { it.type == MediaType.VIDEO })
        assertTrue(result.items.any { it.type == MediaType.COVER })
    }

    @Test
    fun mapsForbiddenResponseToAccessRestrictionMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))

        val failure = parser.parse(server.url("/private").toString()).exceptionOrNull()

        assertTrue(failure is MediaParseException)
        assertEquals(ParserMessages.ACCESS_RESTRICTED, failure?.message)
    }

    @Test
    fun structuredVideoDoesNotCollectUnrelatedPageImages() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html><head>
                      <meta property="og:video" content="/media/post.mp4" />
                      <meta property="og:image" content="/media/cover.jpg" />
                    </head><body>
                      <main><img src="/recommendation.jpg" /></main>
                      <img src="/avatar.jpg" class="avatar" />
                    </body></html>
                    """.trimIndent(),
                ),
        )

        val result = parser.parse(server.url("/post").toString()).getOrThrow()

        assertEquals(2, result.items.size)
        assertTrue(result.items.any { it.type == MediaType.VIDEO })
        assertTrue(result.items.any { it.type == MediaType.COVER })
    }

    @Test
    fun bodyImageFallbackIsScopedAndKeepsSmallGif() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    """
                    <html><body>
                      <header><img src="/unrelated.jpg" /></header>
                      <main><img src="/reaction.gif" width="120" height="120" /></main>
                    </body></html>
                    """.trimIndent(),
                ),
        )

        val result = parser.parse(server.url("/comment").toString()).getOrThrow()

        assertEquals(1, result.items.size)
        assertEquals(MediaType.GIF, result.items.single().type)
    }

    @Test
    fun xLinksNeverFallBackToWholePageScanning() {
        assertTrue(!parser.canHandle("https://x.com/example/status/123"))
        assertTrue(!parser.canHandle("https://twitter.com/example/status/123"))
    }

    @Test
    fun doubaoLinksStayWithTheDedicatedParser() {
        assertTrue(!parser.canHandle("https://www.doubao.com/thread/public"))
        assertTrue(!parser.canHandle("https://www.doubao.com/video-sharing?share_id=public"))
    }
}
