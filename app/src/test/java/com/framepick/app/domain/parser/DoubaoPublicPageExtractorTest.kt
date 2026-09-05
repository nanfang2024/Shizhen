package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoPublicPageExtractorTest {
    @Test
    fun extractsPublicOriginalImageAndVideoFieldsWithoutPixelProcessing() {
        val parsed = DoubaoPublicPageExtractor.extract(
            "https://www.doubao.com/share/example",
            """
                <html><head>
                  <meta property="og:title" content="豆包公开作品">
                  <script type="application/json">
                    {
                      "messages": [{
                        "image_ori_raw": {"url": "https://p3.byteimg.com/original-image.png"},
                        "image_thumb": "https://p3.byteimg.com/thumb-image.webp",
                        "video_url": "https://v3.bytecdn.cn/public-video.mp4"
                      }]
                    }
                  </script>
                </head></html>
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("豆包", parsed?.platform)
        assertEquals("豆包公开作品", parsed?.title)
        assertEquals(2, parsed?.items?.size)
        assertEquals(MediaType.IMAGE, parsed?.items?.first()?.type)
        assertEquals(SourceWatermark.PUBLIC_ORIGINAL, parsed?.items?.first()?.sourceWatermark)
        assertTrue(parsed?.items?.any { it.type == MediaType.VIDEO } == true)
        assertTrue(parsed?.items?.none { it.mediaUrl.contains("thumb-image") } == true)
    }

    @Test
    fun ignoresExplicitWatermarkUrlsAndParsesEscapedEmbeddedFields() {
        val parsed = DoubaoPublicPageExtractor.extract(
            "https://www.doubao.com/share/example",
            """
                <script>
                  window.state = {"image_ori":"https:\/\/p3.byteimg.com\/watermark\/bad.png",
                    "image_thumb_ori":"https:\/\/p3.byteimg.com\/clean.png"};
                </script>
            """.trimIndent(),
        )

        assertEquals(1, parsed?.items?.size)
        assertEquals("https://p3.byteimg.com/clean.png", parsed?.items?.single()?.mediaUrl)
        assertFalse(parsed?.items?.single()?.mediaUrl.orEmpty().contains("watermark"))
    }

    @Test
    fun reportsNoResultForShellWithoutPublicMedia() {
        assertNull(
            DoubaoPublicPageExtractor.extract(
                "https://www.doubao.com/chat/123",
                "<html><body>请登录后继续</body></html>",
            ),
        )
        assertTrue(
            DoubaoPublicPageExtractor.looksAccessRestricted(
                "https://www.doubao.com/chat/123",
                "<html><body>应用外壳</body></html>",
            ),
        )
    }

    @Test
    fun decodesThreadLoaderDataFromHtmlEscapedScriptAttribute() {
        val nested = """{"share_info":{"share_name":"公开线程作品","user":{"nick_name":"创作者"}},"messages":[{"image_ori":{"url":"https://p3.byteimg.com/picture~tplv-i_dld_wm_16.png"},"image_ori_raw":{"url":"https://p3.byteimg.com/picture~tplv-ppe_image_raw.png","width":2732,"height":1534},"image_thumb":{"url":"https://p3.byteimg.com/thumb.webp"}}]}"""
        val args = """["thread_(token)/page",[["loaderData",{"routerDataFnArgs":[${jsonQuote(nested)}]}]]]"""
        val escaped = args
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
        val parsed = DoubaoPublicPageExtractor.extract(
            "https://www.doubao.com/thread/public",
            """<html><script data-fn-args="$escaped">runWindowFn();</script></html>""",
        )

        assertNotNull(parsed)
        assertEquals("公开线程作品", parsed?.title)
        assertEquals("创作者", parsed?.author)
        assertEquals(1, parsed?.items?.size)
        assertEquals(
            "https://p3.byteimg.com/picture~tplv-ppe_image_raw.png",
            parsed?.items?.single()?.mediaUrl,
        )
        assertEquals(SourceWatermark.PUBLIC_ORIGINAL, parsed?.items?.single()?.sourceWatermark)
    }

    private fun jsonQuote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    @Test
    fun extractsVideoSharingParametersAndPublicApiVideoUrl() {
        val request = DoubaoVideoSharingRequest.fromUrl(
            "https://www.doubao.com/video-sharing?share_id=share123&creation_id=create456&video_id=video789",
        )
        assertEquals("share123", request?.shareId)
        assertEquals("create456", request?.creationId)
        assertEquals("video789", request?.videoId)

        val parsed = DoubaoPublicPageExtractor.extract(
            "https://www.doubao.com/video-sharing?share_id=share123",
            """<script type="application/json">{
              "code":0,
              "data":{"title":"公开视频","video":{"main_url":"https://v3.bytecdn.cn/generated/video.mp4"}}
            }</script>""",
        )
        assertEquals("公开视频", parsed?.title)
        assertEquals(MediaType.VIDEO, parsed?.items?.single()?.type)
        assertEquals("https://v3.bytecdn.cn/generated/video.mp4", parsed?.items?.single()?.mediaUrl)
    }

    @Test
    fun parsesVideoSharingApiPlayInfoAndKeepsWatermarkStatusHonest() {
        val parsed = DoubaoPublicVideoApiExtractor.extract(
            "https://www.doubao.com/video-sharing?share_id=share123&video_id=video789",
            """{
              "code": 0,
              "data": {
                "play_info": {
                  "main": "https://v11-default.example/video/path?a=0&lr=video_gen_watermark_dyn&mime_type=video_mp4",
                  "width": 720,
                  "height": 1280,
                  "definition": "720p",
                  "poster_url": "https://p3.example/poster.webp"
                },
                "user_info": {"nickname": "公开作者"},
                "prompt": "公开视频提示词"
              }
            }""".trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("豆包", parsed?.platform)
        assertEquals("公开视频提示词", parsed?.title)
        assertEquals("公开作者", parsed?.author)
        assertEquals("https://p3.example/poster.webp", parsed?.thumbnailUrl)
        assertEquals(MediaType.VIDEO, parsed?.items?.single()?.type)
        assertEquals(720, parsed?.items?.single()?.width)
        assertEquals(1280, parsed?.items?.single()?.height)
        assertEquals(SourceWatermark.WATERMARKED, parsed?.items?.single()?.sourceWatermark)
        assertTrue(parsed?.items?.single()?.allowWatermarkedDownload == true)
        assertTrue(parsed?.items?.single()?.qualityLabel.orEmpty().contains("含水印"))
    }

    @Test
    fun parsesPublicPlayInfoFallbackShape() {
        val parsed = DoubaoPublicVideoApiExtractor.extract(
            "https://www.doubao.com/video-sharing?video_id=video789",
            """{
              "data": {
                "original_media_info": {
                  "main_url": "https://v3.example/public-video?mime_type=video_mp4",
                  "meta": {"width": 1248, "height": 704, "definition": "720p"}
                },
                "poster_url": "https://p3.example/poster.png"
              }
            }""".trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(1248, parsed?.items?.single()?.width)
        assertEquals(704, parsed?.items?.single()?.height)
        assertEquals(SourceWatermark.UNKNOWN, parsed?.items?.single()?.sourceWatermark)
    }
}
