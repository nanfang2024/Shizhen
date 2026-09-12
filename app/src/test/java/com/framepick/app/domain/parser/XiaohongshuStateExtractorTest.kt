package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuStateExtractorTest {
    @Test
    fun extractsOnlyCurrentNoteImagesFromNewHydrationShape() {
        val result = XiaohongshuStateExtractor.extract(
            sourceUrl = "http://xhslink.cn/o/example",
            finalUrl = "https://www.xiaohongshu.com/discovery/item/note123",
            html = """
                <script>window.__INITIAL_STATE__={
                  "noteData": {
                    "normalNotePreloadData": {"imagesList": [{
                      "urlSizeLarge": "http://sns-na-i6.xhscdn.com/current-file-1"
                    }]},
                    "data": {
                    "noteData": {
                      "noteId": "note123",
                      "type": "normal",
                      "title": "当前图文笔记",
                      "user": {"nickname": "当前作者"},
                      "imageList": [
                        {"fileId": "current-file-1", "url": "http://sns-webpic-qc.xhscdn.com/current-1.jpg!h5_1080jpg", "width": 1080, "height": 1440},
                        {"fileId": "notes_pre_post/current-file-2", "url": "http://sns-webpic-qc.xhscdn.com/current-2.webp!h5_1080jpg", "width": 1200, "height": 1600}
                      ],
                      "optional": undefined
                    },
                    "relatedNotes": [{
                      "title": "无关推荐",
                      "cover": {"url": "https://sns-webpic-qc.xhscdn.com/unrelated.jpg"}
                    }]
                  }} }
                };</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("小红书", result.platform)
        assertEquals("当前图文笔记", result.title)
        assertEquals("当前作者", result.author)
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.type == MediaType.IMAGE })
        assertEquals(
            listOf(
                "https://sns-na-i6.xhscdn.com/current-file-1",
                "https://sns-na-i6.xhscdn.com/notes_pre_post/current-file-2",
            ),
            result.items.map { it.mediaUrl },
        )
        assertTrue(result.items.all { it.format == null })
        assertTrue(result.items.all { it.sourceWatermark == SourceWatermark.PUBLIC_ORIGINAL })
        assertTrue(
            result.items.all {
                it.downloadSourceUrl == "https://www.xiaohongshu.com/discovery/item/note123"
            },
        )
        assertFalse(result.items.any { it.mediaUrl.contains("unrelated") })
    }

    @Test
    fun prefersPublicOriginVideoFromLegacyNoteShape() {
        val result = XiaohongshuStateExtractor.extract(
            sourceUrl = "https://www.xiaohongshu.com/explore/abc123",
            finalUrl = "https://www.xiaohongshu.com/explore/abc123",
            html = """
                <script>window.__INITIAL_STATE__={
                  "note": {"noteDetailMap": {"abc123": {"note": {
                    "noteId": "abc123",
                    "title": "公开视频笔记",
                    "user": {"nickname": "视频作者"},
                    "video": {
                      "consumer": {"originVideoKey": "spectrum/original-key"},
                      "media": {"stream": {"h264": [{
                        "masterUrl": "http://sns-video-bd.xhscdn.com/transcoded.mp4",
                        "width": 720, "height": 1280, "size": 1234,
                        "qualityType": "HD", "videoCodec": "h264"
                      }]}}
                    },
                    "imageList": [{
                      "url": "http://sns-webpic-qc.xhscdn.com/cover.jpg",
                      "width": 720, "height": 1280
                    }]
                  }}}}
                };</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals(3, result.items.size)
        val original = result.items.first()
        assertEquals(MediaType.VIDEO, original.type)
        assertEquals(SourceWatermark.PUBLIC_ORIGINAL, original.sourceWatermark)
        assertTrue(original.mediaUrl.endsWith("/spectrum/original-key"))
        assertTrue(original.isRecommended)
        assertEquals(MediaType.COVER, result.items.last().type)
        assertTrue(
            result.items.all { it.downloadSourceUrl == "https://www.xiaohongshu.com/explore/abc123" },
        )
    }
}
