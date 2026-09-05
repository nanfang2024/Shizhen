package com.framepick.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QqMusicPageExtractorTest {
    @Test
    fun extractsPublicSongMetadataCoverAndAudio() {
        val result = QqMusicPageExtractor.extract(
            finalUrl = "https://i2.y.qq.com/n3/other/pages/playsong/index.html?songmid=004Ti8rT003TaZ",
            html = """
                <script>window.__ssrFirstPageData__="{\"song\":{\"mid\":\"004Ti8rT003TaZ\",\"title\":\"测试歌曲\",\"singerName\":\"歌手甲/歌手乙\",\"img\":\"https://y.qq.com/music/photo_new/cover.jpg\",\"playUrl\":\"http:\\u002F\\u002Faqqmusic.tc.qq.com\\u002Fpublic.m4a?token=test\"},\"template\":\"normal\"}"</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("测试歌曲", result.title)
        assertEquals("歌手甲/歌手乙", result.author)
        assertEquals("https://y.qq.com/music/photo_new/cover.jpg", result.coverUrl)
        assertEquals(1, result.audioCandidates.size)
        assertTrue(result.audioCandidates.single().url.startsWith("https://aqqmusic.tc.qq.com/"))
        assertEquals(MusicAudioCompleteness.FULL, result.audioCandidates.single().completeness)
    }

    @Test
    fun rejectsPageWithoutSsrSongState() {
        assertNull(QqMusicPageExtractor.extract("https://y.qq.com/", "<html></html>"))
    }

    @Test
    fun labelsPaidSongPublicUrlAsPreview() {
        val result = QqMusicPageExtractor.extract(
            finalUrl = "https://i2.y.qq.com/n3/other/pages/playsong/index.html?songmid=002WcwVL2ypOuf",
            html = """
                <script>window.__ssrFirstPageData__="{\"song\":{\"mid\":\"002WcwVL2ypOuf\",\"title\":\"付费歌曲\",\"pay\":{\"pay_play\":1},\"playUrl\":\"https:\\u002F\\u002Faqqmusic.tc.qq.com\\u002Fpreview.mp3\"}}"</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("QQ 音乐页面公开试听", result.audioCandidates.single().label)
        assertEquals(MusicAudioCompleteness.PREVIEW, result.audioCandidates.single().completeness)
    }
}
