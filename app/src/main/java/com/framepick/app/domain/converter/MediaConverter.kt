package com.framepick.app.domain.converter

import android.net.Uri

sealed interface ConversionRequest {
    val inputUri: Uri
}

data class VideoToGifRequest(
    override val inputUri: Uri,
    val outputUri: Uri,
    val startSeconds: Double,
    val endSeconds: Double,
    val fps: Int,
    val outputWidth: Int?,
    val loop: Boolean,
) : ConversionRequest

data class GifToMp4Request(
    override val inputUri: Uri,
    val outputUri: Uri,
) : ConversionRequest

enum class FrameFormat(val extension: String, val mimeType: String) {
    JPG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
}

data class ExtractFramesRequest(
    override val inputUri: Uri,
    val outputTreeUri: Uri,
    val intervalSeconds: Double,
    val format: FrameFormat,
) : ConversionRequest

data class ConversionProgress(
    val percent: Int,
    val message: String,
)

data class ConversionResult(
    val outputUri: Uri,
    val generatedFileCount: Int = 1,
)

interface MediaConverter {
    suspend fun convert(
        request: ConversionRequest,
        onProgress: (ConversionProgress) -> Unit,
    ): Result<ConversionResult>

    fun cancel()
}
