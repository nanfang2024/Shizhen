package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramPlaylistJsonExtractorTest {
    @Test
    fun keepsUsableCarouselVideosWhenOtherChildrenHaveNoFormats() {
        val parsed = InstagramPlaylistJsonExtractor.extract(
            "https://www.instagram.com/p/parent/",
            """
                {
                  "_type": "playlist",
                  "title": "公开轮播",
                  "uploader": "creator",
                  "entries": [
                    {"id":"broken","thumbnail":"https://cdn.example/broken.jpg"},
                    {
                      "id":"video-one",
                      "webpage_url":"https://www.instagram.com/p/video-one/",
                      "thumbnail":"https://cdn.example/one.jpg",
                      "formats":[
                        {"format_id":"dash-1080","url":"https://cdn.example/one-1080.mp4","ext":"mp4","width":1080,"height":1440,"vcodec":"h264","acodec":"none","tbr":2200},
                        {"format_id":"http-720","url":"https://cdn.example/one-720.mp4","ext":"mp4","width":720,"height":960,"vcodec":"h264","acodec":"aac","tbr":1200}
                      ]
                    },
                    {
                      "id":"video-two",
                      "webpage_url":"https://www.instagram.com/p/video-two/",
                      "thumbnail":"https://cdn.example/two.jpg",
                      "formats":[
                        {"format_id":"dash-1080","url":"https://cdn.example/two.mp4","ext":"mp4","width":1080,"height":1440,"vcodec":"h264","acodec":"none","tbr":2000}
                      ]
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("公开轮播", parsed?.title)
        assertEquals(2, parsed?.items?.size)
        assertEquals(MediaType.VIDEO, parsed?.items?.first()?.type)
        assertEquals("https://cdn.example/one-720.mp4", parsed?.items?.first()?.mediaUrl)
        assertEquals(DownloadStrategy.DIRECT, parsed?.items?.first()?.downloadStrategy)
        assertNull(parsed?.items?.first()?.formatSelector)
        assertTrue(parsed?.items?.first()?.isRecommended == true)
        assertFalse(parsed?.items?.last()?.isRecommended == true)
        assertFalse(parsed?.items?.last()?.hasAudio == true)
    }

    @Test
    fun returnsNullWhenPlaylistHasNoUsableVideoChildren() {
        assertNull(
            InstagramPlaylistJsonExtractor.extract(
                "https://www.instagram.com/p/images/",
                """{"_type":"playlist","entries":[{"id":"image","formats":[]}]}""",
            ),
        )
    }

    @Test
    fun carouselRetryOnlyAppliesToInstagramNoFormatsFailure() {
        assertTrue(
            YtDlpInternationalFallbackPolicy.shouldParseInstagramPlaylist(
                "https://www.instagram.com/p/example/",
                "ERROR: [Instagram] child: No video formats found!",
            ),
        )
        assertFalse(
            YtDlpInternationalFallbackPolicy.shouldParseInstagramPlaylist(
                "https://www.youtube.com/watch?v=example",
                "No video formats found!",
            ),
        )
    }
}
