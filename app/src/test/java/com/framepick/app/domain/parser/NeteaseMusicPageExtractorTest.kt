package com.framepick.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseMusicPageExtractorTest {
    @Test
    fun extractsPublicSongMetadataAndUpgradesCoverToHttps() {
        val result = NeteaseMusicPageExtractor.extract(
            finalUrl = "https://y.music.163.com/m/song?id=1365898499&share=1",
            html = """
                <html><head>
                  <meta name="description" content="歌曲名《失眠飞行》，由 接个吻，开一枪、沈以诚、薛明媛 演唱，收录于《失眠飞行》专辑中" />
                  <meta property="og:title" content="失眠飞行 - 接个吻，开一枪/沈以诚/薛明媛 - 单曲 - 网易云音乐" />
                  <meta property="og:image" content="http://p2.music.126.net/cover/album.jpg" />
                  <meta property="music:duration" content="207" />
                </head></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("1365898499", result.songId)
        assertEquals("失眠飞行", result.title)
        assertEquals("接个吻，开一枪、沈以诚、薛明媛", result.author)
        assertEquals("https://p2.music.126.net/cover/album.jpg", result.coverUrl)
        assertEquals(207, result.durationSeconds)
    }

    @Test
    fun rejectsPageWithoutSongId() {
        assertNull(
            NeteaseMusicPageExtractor.extract(
                finalUrl = "https://music.163.com/",
                html = "<meta property='og:title' content='首页 - 网易云音乐'>",
            ),
        )
    }

    @Test
    fun extractsDurationFromOfficialMobileReduxStateWhenMetaDurationIsMissing() {
        val result = NeteaseMusicPageExtractor.extract(
            finalUrl = "https://y.music.163.com/m/song?id=2060701318&share=1",
            html = """
                <html><head>
                  <meta name="description" content="歌曲名《Clouds》，由 李浩玮 演唱" />
                </head><body>
                  <script>
                    window.REDUX_STATE = {"Song":{
                      "name":"Clouds",
                      "id":2060701318,
                      "dt":139615,
                      "ar":[{"name":"李浩玮"}],
                      "al":{"picUrl":"http:\u002F\u002Fp1.music.126.net\u002Fcover.jpg"}
                    },"showFullSongs":false};
                  </script>
                </body></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals(140, result.durationSeconds)
        assertEquals("Clouds", result.title)
        assertEquals("李浩玮", result.author)
    }
}
