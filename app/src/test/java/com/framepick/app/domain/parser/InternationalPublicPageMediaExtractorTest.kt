package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalPublicPageMediaExtractorTest {
    @Test
    fun extractsInstagramSjsProductByNumericMediaIdWithoutShortcodeField() {
        val html = """
            <html><body><script type="application/json" data-sjs>
            {"require":[{"__bbox":{"result":{"data":{"xig_polaris_media":{
              "if_not_gated_logged_out":{"pk":"3955626177297141544",
                "caption":{"text":"新版公开图片"},
                "user":{"username":"public_creator"},
                "image_versions2":{"candidates":[
                  {"url":"https://cdn.example/preview.jpg","width":640,"height":800},
                  {"url":"https://cdn.example/best.jpg","width":1440,"height":1800}
                ]}}
            }}}}}]}</script></body></html>
        """.trimIndent()

        val parsed = InternationalPublicPageMediaExtractor.extract(
            "https://www.instagram.com/p/DblNQzqCE8o/",
            "https://www.instagram.com/p/DblNQzqCE8o/",
            html,
        )

        assertEquals("新版公开图片", parsed?.title)
        assertEquals("public_creator", parsed?.author)
        assertEquals(MediaType.IMAGE, parsed?.items?.single()?.type)
        assertEquals("https://cdn.example/best.jpg", parsed?.items?.single()?.mediaUrl)
    }

    @Test
    fun extractsOnlyCurrentInstagramCarouselFromStructuredState() {
        val html = """
            <html><head><meta property="og:title" content="回退标题"></head><body>
            <script type="application/json">
            {"payload":{"code":"CURRENT123","caption":{"text":"当前作品"},
             "user":{"username":"creator"},"carousel_media":[
              {"pk":"1","image_versions2":{"candidates":[
                {"url":"https://cdn.example/small.jpg","width":320,"height":240},
                {"url":"https://cdn.example/original.jpg","width":1440,"height":1080}]}},
              {"pk":"2","video_versions":[
                {"url":"https://cdn.example/video.mp4","width":1080,"height":1920}],
               "image_versions2":{"candidates":[
                {"url":"https://cdn.example/video-cover.jpg","width":1080,"height":1920}]}}
             ]},"related":{"code":"OTHER999","video_url":"https://cdn.example/unrelated.mp4"}}
            </script></body></html>
        """.trimIndent()

        val parsed = InternationalPublicPageMediaExtractor.extract(
            "https://www.instagram.com/p/CURRENT123/",
            "https://www.instagram.com/p/CURRENT123/",
            html,
        )

        assertNotNull(parsed)
        assertEquals("Instagram", parsed?.platform)
        assertEquals("当前作品", parsed?.title)
        assertEquals("creator", parsed?.author)
        assertEquals(2, parsed?.items?.size)
        assertEquals(MediaType.IMAGE, parsed?.items?.get(0)?.type)
        assertEquals("https://cdn.example/original.jpg", parsed?.items?.get(0)?.mediaUrl)
        assertEquals(MediaType.VIDEO, parsed?.items?.get(1)?.type)
        assertTrue(parsed?.items?.none { it.mediaUrl.contains("unrelated") } == true)
    }

    @Test
    fun treatsInstagramPhotoOpenGraphAsDownloadableImage() {
        val parsed = InternationalPublicPageMediaExtractor.extract(
            "https://www.instagram.com/p/PHOTO123/",
            "https://www.instagram.com/p/PHOTO123/",
            """<meta property="og:title" content="公开照片">
                <meta property="og:image" content="https://cdn.example/photo.jpg">""",
        )

        assertEquals(MediaType.IMAGE, parsed?.items?.single()?.type)
        assertEquals("公开照片", parsed?.title)
    }

    @Test
    fun extractsFacebookOpenGraphVideoAndCover() {
        val parsed = InternationalPublicPageMediaExtractor.extract(
            "https://www.facebook.com/reel/123",
            "https://www.facebook.com/reel/123",
            """<meta property="og:title" content="公开短片">
                <meta property="og:video" content="https://video.example/public.mp4">
                <meta property="og:image" content="https://image.example/cover.jpg">
                <meta property="og:video:width" content="1080">
                <meta property="og:video:height" content="1920">""",
        )

        assertEquals("Facebook", parsed?.platform)
        assertEquals(listOf(MediaType.VIDEO, MediaType.COVER), parsed?.items?.map { it.type })
        assertEquals(1080, parsed?.items?.first()?.width)
        assertEquals(1920, parsed?.items?.first()?.height)
    }

    @Test
    fun doesNotMisrepresentReelThumbnailAsTheVideo() {
        assertNull(
            InternationalPublicPageMediaExtractor.extract(
                "https://www.facebook.com/reel/123",
                "https://www.facebook.com/reel/123",
                """<meta property="og:image" content="https://image.example/cover.jpg">""",
            ),
        )
    }

    @Test
    fun mapsYouTubeOEmbedToAnExplicitCoverOnlyResult() {
        val parsed = YouTubePublicMetadataExtractor.extract(
            sourceUrl = "https://youtu.be/5_mn9oF5V4U",
            canonicalUrl = "https://www.youtube.com/watch?v=5_mn9oF5V4U",
            json = """{
                "title":"公开测试视频",
                "author_name":"测试作者",
                "thumbnail_url":"https://i.ytimg.com/vi/5_mn9oF5V4U/hqdefault.jpg",
                "thumbnail_width":480,
                "thumbnail_height":360
            }""",
        )

        assertEquals("YouTube", parsed?.platform)
        assertEquals("公开测试视频", parsed?.title)
        assertEquals("测试作者", parsed?.author)
        assertEquals(MediaType.COVER, parsed?.items?.single()?.type)
        assertTrue(parsed?.items?.single()?.qualityLabel?.contains("视频流暂不可用") == true)
    }

    @Test
    fun retriesOnlyTheSpecificInstagramImageOnlyFailure() {
        assertTrue(
            YtDlpInternationalFallbackPolicy.shouldRetryInstagramAsImage(
                "https://www.instagram.com/p/DblNQzqCE8o/",
                "ERROR: There is no video in this post",
            ),
        )
        assertTrue(
            !YtDlpInternationalFallbackPolicy.shouldRetryInstagramAsImage(
                "https://www.youtube.com/watch?v=5_mn9oF5V4U",
                "ERROR: There is no video in this post",
            ),
        )
        assertTrue(
            !YtDlpInternationalFallbackPolicy.shouldRetryInstagramAsImage(
                "https://www.instagram.com/p/DblNQzqCE8o/",
                "ERROR: Login required",
            ),
        )
    }
}
