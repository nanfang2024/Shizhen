package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouMusicPageExtractorTest {
    @Test
    fun extractsCurrentSharePageMetadataAndPublicAudioUrls() {
        val result = KugouMusicPageExtractor.extract(
            """
                <html><script>
                var phpParam = {
                  "filename":"测试歌手 - 测试歌曲",
                  "song_info":{"data":{
                    "authors":[{"author_name":"歌手甲"},{"author_name":"歌手乙"}],
                    "songName":"测试歌曲",
                    "fileName":"歌手甲、歌手乙 - 测试歌曲",
                    "bitRate":128,
                    "pay_type":0,
                    "audition_status":0,
                    "fileSize":3561358,
                    "album_img":"http://imge.kugou.com/stdmusic/{size}/album.jpg",
                    "url":"https://sharefs.kugou.com/public/full.mp3",
                    "backup_url":["https://sharefs.tx.kugou.com/public/full.mp3"]
                  }},
                  "imgurl":"http://imge.kugou.com/stdmusic/400/album.jpg"
                };
                </script></html>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("测试歌曲", result.title)
        assertEquals("歌手甲 / 歌手乙", result.author)
        assertEquals("https://imge.kugou.com/stdmusic/400/album.jpg", result.coverUrl)
        assertEquals(2, result.audioCandidates.size)
        assertTrue(result.audioCandidates.all { it.label == "酷狗公开完整音频 · 128 kbps" })
        assertTrue(result.audioCandidates.all {
            it.completeness == MusicAudioCompleteness.FULL
        })
    }

    @Test
    fun labelsPaidPageAudioAsPublicPreview() {
        val result = KugouMusicPageExtractor.extract(
            """
                <script>var phpParam = {"song_info":{"data":{
                  "songName":"受限歌曲",
                  "pay_type":3,
                  "audition_status":1,
                  "url":"https://sharefs.kugou.com/public/preview.mp3"
                }}};</script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("酷狗页面公开试听", result.audioCandidates.single().label)
        assertEquals(MusicAudioCompleteness.PREVIEW, result.audioCandidates.single().completeness)
    }

    @Test
    fun extractsNestedStateWhenStringsContainBracesAndAssignmentTerminators() {
        val result = KugouMusicPageExtractor.extract(
            """
                <script>
                  var phpParam = {"song_info":{"data":{
                    "songName":"带有 }; 和 { 字符的歌名",
                    "metadata":{"description":"转义引号 \" 后仍属于字符串"},
                    "audition_status":1,
                    "url":"https://sharefs.kugou.com/public/preview.mp3"
                  }}};
                  var unrelated = {"value":true};
                </script>
            """.trimIndent(),
        )

        requireNotNull(result)
        assertEquals("带有 }; 和 { 字符的歌名", result.title)
        assertEquals(MusicAudioCompleteness.PREVIEW, result.audioCandidates.single().completeness)
    }

    @Test
    fun rejectsPageWithoutKugouSongState() {
        assertNull(KugouMusicPageExtractor.extract("<html><title>酷狗音乐</title></html>"))
    }
}
