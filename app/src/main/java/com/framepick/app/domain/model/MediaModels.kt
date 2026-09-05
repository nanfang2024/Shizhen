package com.framepick.app.domain.model

data class ParsedMedia(
    val sourceUrl: String,
    val platform: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val items: List<MediaItem>,
)

data class MediaItem(
    val id: String,
    val type: MediaType,
    val mediaUrl: String,
    val format: String?,
    val width: Int?,
    val height: Int?,
    val fileSize: Long?,
    val qualityLabel: String?,
    val previewUrl: String? = mediaUrl,
    val downloadStrategy: DownloadStrategy = DownloadStrategy.DIRECT,
    val downloadSourceUrl: String = mediaUrl,
    val formatSelector: String? = null,
    val isRecommended: Boolean = false,
    val hasAudio: Boolean? = null,
    val codecSummary: String? = null,
    val watermarkNote: String? = null,
    val sourceWatermark: SourceWatermark = SourceWatermark.UNKNOWN,
    val allowWatermarkedDownload: Boolean = false,
    val previewAsVideo: Boolean = false,
    val isPreviewOnly: Boolean = false,
    val backupUrl: String? = null,
)

enum class DownloadStrategy {
    DIRECT,
    YT_DLP,
    DIRECT_AUDIO,
    YT_DLP_AUDIO,
}

enum class SourceWatermark {
    PUBLIC_ORIGINAL,
    PUBLIC_CLEAN,
    WATERMARKED,
    UNKNOWN,
}

enum class MediaType {
    VIDEO,
    IMAGE,
    GIF,
    COVER,
    AUDIO,
    UNKNOWN,
}
