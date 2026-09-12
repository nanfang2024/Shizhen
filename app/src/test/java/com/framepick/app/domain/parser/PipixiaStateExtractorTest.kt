package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * Fixture structure source: publicly documented Pipixia h5 share-page shape.
 * The sandbox network exit is blocked by Pipixia risk control, so real page
 * capture was not possible (Phase 1 design decision D5).
 */
class PipixiaStateExtractorTest {
    @Test
    fun extractsVideoWorkWithMediaCandidatesAndCover() {
        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/abc/",
            finalUrl = "https://h5.pipix.com/item/123456/",
            html = """
                <html><head><title>皮皮虾</title></head><body>
                <script>window.__INITIAL_STATE__ = {"item": {"data": {
                  "id": 123456,
                  "content": {"text": "公开皮皮虾视频作品"},
                  "author": {"user": {"name": "测试作者"}},
                  "medias": [
                    {"type": "video", "content_url": "https://v.pipix.com/720.mp4", "width": 720, "height": 1280},
                    {"type": "video", "content_url": "https://v.pipix.com/1080.mp4", "width": 1080, "height": 1920}
                  ],
                  "cover_image": {"url_list": ["https://img.pipix.com/cover.jpg"]}
                }}};</script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("皮皮虾", result.platform)
        assertEquals("公开皮皮虾视频作品", result.title)
        assertEquals("测试作者", result.author)
        // Two video candidates ordered by resolution plus the cover.
        assertEquals(3, result.items.size)
        assertEquals(MediaType.VIDEO, result.items[0].type)
        assertEquals("https://v.pipix.com/1080.mp4", result.items[0].mediaUrl)
        assertEquals(1080, result.items[0].width)
        assertEquals(1920, result.items[0].height)
        assertTrue(result.items[0].isRecommended)
        assertTrue(result.items[0].hasAudio == true)
        assertEquals(SourceWatermark.UNKNOWN, result.items[0].sourceWatermark)
        assertEquals(MediaType.VIDEO, result.items[1].type)
        assertEquals("https://v.pipix.com/720.mp4", result.items[1].mediaUrl)
        assertEquals(MediaType.COVER, result.items.last().type)
        assertEquals("https://img.pipix.com/cover.jpg", result.items.last().mediaUrl)
        assertEquals("https://img.pipix.com/cover.jpg", result.thumbnailUrl)
    }

    @Test
    fun extractsVideoWorkFromByteStyleUrlListsWhenMediasMissing() {
        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/def/",
            finalUrl = "https://h5.pipix.com/item/654321/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__ = {"item": {"data": {
                  "item_id": 654321,
                  "content": {"text": "字节风格地址列表"},
                  "author": {"name": "列表作者"},
                  "video": {
                    "video_high_url_list": [{"url": "https://v.pipix.com/high.mp4"}],
                    "video_fallback_url_list": [{"url": "https://v.pipix.com/fallback.mp4"}]
                  },
                  "cover_image": {"url": "https://img.pipix.com/cover2.jpg"}
                }}};</script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("字节风格地址列表", result.title)
        assertEquals("列表作者", result.author)
        val videos = result.items.filter { it.type == MediaType.VIDEO }
        assertEquals(2, videos.size)
        assertTrue(videos.any { it.mediaUrl == "https://v.pipix.com/high.mp4" })
        assertTrue(videos.any { it.mediaUrl == "https://v.pipix.com/fallback.mp4" })
        assertEquals(MediaType.COVER, result.items.last().type)
    }

    @Test
    fun extractsImageCollection() {
        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/img/",
            finalUrl = "https://h5.pipix.com/item/777/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__ = {"item": {"data": {
                  "id": 777,
                  "content": {"text": "皮皮虾公开图集"},
                  "author": {"user": {"name": "图集作者"}},
                  "images": [
                    {"image_url": {"url_list": ["https://img.pipix.com/a1.jpg"]}},
                    {"image_url": {"url_list": ["https://img.pipix.com/a2.jpg"]}}
                  ]
                }}};</script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("皮皮虾", result.platform)
        assertEquals("图集作者", result.author)
        assertEquals(2, result.items.size)
        result.items.forEach { assertEquals(MediaType.IMAGE, it.type) }
        assertEquals("https://img.pipix.com/a1.jpg", result.items[0].mediaUrl)
        assertEquals("https://img.pipix.com/a2.jpg", result.items[1].mediaUrl)
        assertTrue(result.items[0].isRecommended)
    }

    @Test
    fun returnsNullWhenStateHasNoReadableMedia() {
        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/none/",
            finalUrl = "https://h5.pipix.com/item/888/",
            html = """
                <html><body>
                <script>window.__INITIAL_STATE__ = {"item": {"data": {
                  "id": 888,
                  "content": {"text": "没有可读媒体"},
                  "author": {"user": {"name": "作者"}}
                }}};</script>
                </body></html>
            """.trimIndent(),
        )

        assertNull(result)
    }

    @Test
    fun returnsNullWhenPageHasNoStateOrIsNotAWorkPage() {
        val noState = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/x/",
            finalUrl = "https://h5.pipix.com/item/999/",
            html = "<html><body>普通页面没有状态数据</body></html>",
        )
        assertNull(noState)

        val notPipix = PipixiaStateExtractor.extract(
            sourceUrl = "https://example.com/s/x/",
            finalUrl = "https://example.com/item/999/",
            html = "<html><body>不是皮皮虾页面</body></html>",
        )
        assertNull(notPipix)
    }

    @Test
    fun extractsVideoWorkFromRenderDataHydrationBlob() {
        // Shape captured from a live share page (h5.pipix.com/s/xxx redirects
        // to /ppx/item/<id>): SSR hydration lives in <script id="RENDER_DATA">
        // as percent-encoded JSON with the work payload at ppxItemDetail.item.
        val itemJson = "{\"ppxItemDetail\":{\"item\":{" +
            "\"item_id\":7683889964181887267," +
            "\"content\":{\"text\":\"\"}," +
            "\"author\":{\"name\":\"皮皮视频.站\"}," +
            "\"video\":{" +
            "\"video_download\":{\"width\":640,\"height\":368,\"url_list\":[" +
            "{\"url\":\"https://v6-cdn-tos.ppxvod.com/a/video.mp4\",\"expires\":1789184992}," +
            "{\"url\":\"https://v26-cdn-tos.ppxvod.com/b/video.mp4\",\"expires\":1789184992}" +
            "]}," +
            "\"video_high\":null," +
            "\"cover_image\":{\"url_list\":[{\"url\":\"https://p3-ppx-sign.byteimg.com/cover.jpeg\"}]}" +
            "}," +
            "\"cover\":{\"url_list\":[{\"url\":\"https://p9-ppx-sign.byteimg.com/cover-q60.jpeg\"}]}," +
            "\"aha_image\":[{" +
            "\"width\":200,\"height\":200,\"url_list\":[{\"url\":\"https://p9-ppx.byteimg.com/sticker.jpeg\"}]" +
            "}]" +
            "}},\"seoTDK\":{\"title\":\"皮这一下很开心 - 皮皮虾\"}," +
            "\"ppxCellComment\":{\"cell_comments\":[]}}"
        // ByteDance SSR percent-encodes with encodeURIComponent semantics
        // (spaces become %20, a literal `+` never appears), so emulate that
        // instead of URLEncoder's form-encoding plus-for-space.
        val encoded = URLEncoder.encode(itemJson, "UTF-8").replace("+", "%20")
        val html = "<html><head><title>皮这一下很开心 - 皮皮虾</title></head><body>" +
            "<script id=\"RENDER_DATA\" type=\"application/json\">$encoded</script></body></html>"

        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/Y0HczQahyuU/",
            finalUrl = "https://h5.pipix.com/ppx/item/7683889964181887267",
            html = html,
        )

        requireNotNull(result)
        assertEquals("皮这一下很开心", result.title)
        assertEquals("皮皮视频.站", result.author)
        val videos = result.items.filter { it.type == MediaType.VIDEO }
        assertEquals(1, videos.size)
        assertEquals("https://v6-cdn-tos.ppxvod.com/a/video.mp4", videos[0].mediaUrl)
        assertEquals("https://v26-cdn-tos.ppxvod.com/b/video.mp4", videos[0].backupUrl)
        assertEquals(640, videos[0].width)
        assertEquals(368, videos[0].height)
        assertTrue(videos[0].isRecommended)
        // 200×200 stickers must not leak in; only the video plus its cover remain.
        assertEquals(2, result.items.size)
        assertEquals(MediaType.COVER, result.items[1].type)
        assertEquals("https://p3-ppx-sign.byteimg.com/cover.jpeg", result.items[1].mediaUrl)
        assertEquals("https://p3-ppx-sign.byteimg.com/cover.jpeg", result.thumbnailUrl)
    }

    @Test
    fun extractsImageWorkFromRenderDataAhaImages() {
        val itemJson = "{\"ppxItemDetail\":{\"item\":{" +
            "\"item_id\":111," +
            "\"author\":{\"name\":\"图集作者\"}," +
            "\"aha_image\":[" +
            "{\"width\":1080,\"height\":1440,\"url_list\":[{\"url\":\"https://p9-ppx.byteimg.com/photo1.jpeg\"}]}," +
            "{\"width\":200,\"height\":200,\"url_list\":[{\"url\":\"https://p9-ppx.byteimg.com/sticker.jpeg\"}]}," +
            "{\"width\":1080,\"height\":810,\"url_list\":[{\"url\":\"https://p9-ppx.byteimg.com/photo2.jpeg\"}]}" +
            "]}}}"
        val encoded = URLEncoder.encode(itemJson, "UTF-8")
        val html = "<html><body>" +
            "<script id=\"RENDER_DATA\" type=\"application/json\">$encoded</script></body></html>"

        val result = PipixiaStateExtractor.extract(
            sourceUrl = "https://h5.pipix.com/s/img2/",
            finalUrl = "https://h5.pipix.com/ppx/item/111",
            html = html,
        )

        requireNotNull(result)
        assertEquals("图集作者", result.author)
        // Tiny stickers are filtered; the two real photos remain.
        assertEquals(2, result.items.size)
        result.items.forEach { assertEquals(MediaType.IMAGE, it.type) }
        assertEquals("https://p9-ppx.byteimg.com/photo1.jpeg", result.items[0].mediaUrl)
        assertEquals("https://p9-ppx.byteimg.com/photo2.jpeg", result.items[1].mediaUrl)
        assertTrue(result.items[0].isRecommended)
    }
}
