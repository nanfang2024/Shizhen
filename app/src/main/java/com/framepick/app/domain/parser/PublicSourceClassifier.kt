package com.framepick.app.domain.parser

import com.framepick.app.domain.model.SourceWatermark
import java.util.Locale

/** 只依据提取器公开返回的格式元数据判断，不通过修改 URL 猜测或绕过平台。 */
object PublicSourceClassifier {
    fun classify(
        platform: String,
        formatId: String?,
        formatNote: String?,
        url: String?,
    ): SourceWatermark {
        if (platform !in WATERMARK_AWARE_PLATFORMS) return SourceWatermark.UNKNOWN

        val id = formatId.orEmpty().lowercase(Locale.ROOT)
        val note = formatNote.orEmpty().lowercase(Locale.ROOT)
        val address = url.orEmpty().lowercase(Locale.ROOT)
        val metadata = "$id $note $address"
        val explicitlyOriginal = id.startsWith("play_addr") ||
            note.contains("direct video") || note.contains("playback video") ||
            TIKTOK_PLAYBACK_FORMAT.matches(id) ||
            listOf("no_watermark", "no-watermark", "nowatermark", "nowm")
                .any(metadata::contains)
        val explicitlyWatermarked = note.contains("watermarked") ||
            note.contains("with watermark") ||
            (!explicitlyOriginal && listOf("watermark=1", "watermarked=1")
                .any(metadata::contains))

        return when {
            explicitlyWatermarked -> SourceWatermark.WATERMARKED
            explicitlyOriginal -> SourceWatermark.PUBLIC_ORIGINAL
            else -> SourceWatermark.UNKNOWN
        }
    }

    fun isWatermarkAware(platform: String): Boolean = platform in WATERMARK_AWARE_PLATFORMS

    private val WATERMARK_AWARE_PLATFORMS = setOf("抖音", "TikTok", "小红书")
    private val TIKTOK_PLAYBACK_FORMAT =
        Regex("(?:h26[45]|bytevc1)_\\d+p(?:_\\d+)?", RegexOption.IGNORE_CASE)
}
