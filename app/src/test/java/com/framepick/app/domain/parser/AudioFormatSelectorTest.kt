package com.framepick.app.domain.parser

import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFormatSelectorTest {
    @Test
    fun sortsPublicAudioByBitrateAndRejectsNonAudioOrUnknownExtensions() {
        val type = ObjectMapper().typeFactory.constructCollectionType(
            ArrayList::class.java,
            VideoFormat::class.java,
        )
        val formats: List<VideoFormat> = ObjectMapper().readValue(
            """
                [
                  {"format_id":"48aac","url":"https://dl.stream.qqmusic.qq.com/48.m4a","ext":"m4a","vcodec":"none","acodec":"aac","abr":48},
                  {"format_id":"128mp3","url":"https://dl.stream.qqmusic.qq.com/128.mp3","ext":"mp3","vcodec":"none","acodec":"mp3","abr":128},
                  {"format_id":"video","url":"https://example.com/video.mp4","ext":"mp4","vcodec":"h264","acodec":"aac","abr":128},
                  {"format_id":"text","url":"https://example.com/file.bin","ext":"bin","vcodec":"none","acodec":"unknown","abr":320}
                ]
            """.trimIndent(),
            type,
        )

        assertEquals(listOf("128mp3", "48aac"), AudioFormatSelector.select(formats).map { it.formatId })
    }
}
