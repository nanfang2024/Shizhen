package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAudioItemPresentationTest {
    @Test
    fun `preview audio is clearly marked and is not recommended as full audio`() {
        val item = listOf(
            audio(MusicAudioCompleteness.PREVIEW, "QQ 音乐页面公开试听"),
        ).toMediaItems("QQ音乐").single()

        assertTrue(item.isPreviewOnly)
        assertTrue(item.qualityLabel.orEmpty().contains("仅试听片段"))
        assertTrue(item.watermarkNote.orEmpty().contains("不是完整歌曲"))
        assertFalse(item.isRecommended)
    }

    @Test
    fun `full public audio remains recommended and is not marked as preview`() {
        val item = listOf(
            audio(MusicAudioCompleteness.FULL, "公开完整音频"),
        ).toMediaItems("测试平台").single()

        assertFalse(item.isPreviewOnly)
        assertTrue(item.isRecommended)
        assertFalse(item.qualityLabel.orEmpty().contains("仅试听片段"))
    }

    private fun audio(completeness: MusicAudioCompleteness, label: String) =
        ResolvedMusicAudio(
            url = "https://audio.example/test.mp3",
            format = "mp3",
            fileSize = 960_887L,
            label = label,
            completeness = completeness,
        )
}
