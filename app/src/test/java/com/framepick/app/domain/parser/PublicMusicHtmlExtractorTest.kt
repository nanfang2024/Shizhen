package com.framepick.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMusicHtmlExtractorTest {
    @Test
    fun extractsOnlyStructuredPublicAudioCandidates() {
        val result = PublicMusicHtmlExtractor.extract(
            finalUrl = "https://www.kugou.com/song/example.html",
            html = """
                <html><head>
                  <meta property="og:title" content="公开歌曲" />
                  <meta property="og:image" content="/cover.jpg" />
                  <meta property="og:audio" content="https://audio.kugou.com/public.mp3" />
                </head><body>
                  <script>window.data={"avatar":"https://img.kugou.com/avatar.jpg","playUrl":"https:\u002F\u002Faudio.kugou.com\u002Fsecond.m4a"};</script>
                </body></html>
            """.trimIndent(),
        )

        assertEquals("公开歌曲", result.title)
        assertEquals("https://www.kugou.com/cover.jpg", result.coverUrl)
        assertEquals(2, result.audioCandidates.size)
        assertTrue(result.audioCandidates.none { it.url.contains("avatar") })
        assertTrue(result.audioCandidates.all {
            it.completeness == MusicAudioCompleteness.UNKNOWN
        })
    }

    @Test
    fun marksExplicitPreviewUrlAsPreview() {
        val result = PublicMusicHtmlExtractor.extract(
            finalUrl = "https://www.kuwo.cn/play_detail/example",
            html = """
                <audio src="https://audio.kuwo.cn/song/preview/example.mp3"></audio>
            """.trimIndent(),
        )

        assertEquals(MusicAudioCompleteness.PREVIEW, result.audioCandidates.single().completeness)
    }
}
