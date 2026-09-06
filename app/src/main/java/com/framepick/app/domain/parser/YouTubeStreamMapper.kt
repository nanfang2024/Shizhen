package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.SourceWatermark

/**
 * 简化的流候选描述：把 NewPipeExtractor 的 VideoStream/AudioStream 摘成纯数据，
 * 让「选流 → 组装 MediaItem」的逻辑可以在 JVM 单元测试里完整覆盖。
 */
data class YouTubeStreamCandidate(
    val itag: String,
    val url: String,
    val container: String,
    val codec: String? = null,
    val height: Int = 0,
    val fps: Int = 0,
    val bitrate: Int = 0,
    val contentLength: Long = 0L,
)

/**
 * YouTube 流映射规则：
 * - 音频轨优先 m4a/AAC（mp4 兼容性最好），同级比平均码率；
 * - 视频轨优先 H.264（avc1）直链，其次 VP9；同 codec 比码率；
 * - 同一高度同时存在合流流与分离流时，选择分离流（码率更高、画质更好），
 *   下载阶段用 FFmpeg `-c copy` 本地无损封装；
 * - 分离流必须有可用音轨才会输出，否则回退合流流。
 */
internal object YouTubeStreamMapper {
    const val MAX_QUALITY_OPTIONS = 6

    fun selectBestAudio(candidates: List<YouTubeStreamCandidate>): YouTubeStreamCandidate? =
        candidates.asSequence()
            .filter { it.url.isNotBlank() }
            .sortedWith(
                compareByDescending<YouTubeStreamCandidate> { audioRank(it) }
                    .thenByDescending { it.bitrate }
                    .thenByDescending { it.contentLength },
            )
            .firstOrNull()

    fun buildItems(
        videoId: String,
        sourceUrl: String,
        muxedCandidates: List<YouTubeStreamCandidate>,
        videoOnlyCandidates: List<YouTubeStreamCandidate>,
        audioCandidate: YouTubeStreamCandidate?,
    ): List<MediaItem> {
        val audio = audioCandidate?.takeIf { it.url.isNotBlank() }
        val muxedByHeight = muxedCandidates
            .filter { it.height > 0 && it.url.isNotBlank() }
            .groupBy { it.height }
        val videoOnlyByHeight = if (audio == null) {
            emptyMap()
        } else {
            videoOnlyCandidates
                .filter { it.height > 0 && it.url.isNotBlank() }
                .groupBy { it.height }
        }
        val heights = (muxedByHeight.keys + videoOnlyByHeight.keys)
            .distinct()
            .sortedDescending()
            .take(MAX_QUALITY_OPTIONS)
        return heights.mapIndexed { index, height ->
            val videoOnly = videoOnlyByHeight[height]
                ?.maxWithOrNull(
                    compareBy<YouTubeStreamCandidate>({ videoRank(it) }, { it.bitrate }),
                )
            val muxed = muxedByHeight[height]
                ?.maxWithOrNull(
                    compareBy<YouTubeStreamCandidate>({ videoRank(it) }, { it.bitrate }),
                )
            if (videoOnly != null && audio != null &&
                (muxed == null || videoRank(videoOnly) >= videoRank(muxed))
            ) {
                mergedItem(videoId, sourceUrl, height, videoOnly, audio, index == 0)
            } else if (muxed != null) {
                muxedItem(videoId, sourceUrl, height, muxed, index == 0)
            } else {
                null
            }
        }.filterNotNull()
    }

    /** mp4/H.264 走 mp4 容器；VP9 等 webm 流封装进 mkv（`-c copy` 安全）。 */
    fun outputContainer(container: String): String = when (container.lowercase()) {
        "mp4", "m4v", "mov" -> "mp4"
        "webm", "mkv" -> "mkv"
        else -> container.ifBlank { "mp4" }
    }

    fun codecLabel(codec: String?): String? = when {
        codec.isNullOrBlank() -> null
        codec.startsWith("avc1", ignoreCase = true) ||
            codec.startsWith("h264", ignoreCase = true) -> "H.264"
        codec.startsWith("vp9", ignoreCase = true) ||
            codec.startsWith("vp09", ignoreCase = true) -> "VP9"
        codec.startsWith("av01", ignoreCase = true) -> "AV1"
        codec.startsWith("mp4a", ignoreCase = true) -> "AAC"
        codec.startsWith("opus", ignoreCase = true) -> "Opus"
        else -> codec.substringBefore('.').uppercase()
    }

    private fun videoRank(candidate: YouTubeStreamCandidate): Int = when {
        candidate.codec?.startsWith("avc1", ignoreCase = true) == true ||
            candidate.codec?.startsWith("h264", ignoreCase = true) == true -> 3
        candidate.codec?.startsWith("vp9", ignoreCase = true) == true ||
            candidate.codec?.startsWith("vp09", ignoreCase = true) == true -> 2
        else -> 1
    }

    /** m4a/AAC 兼容性最好；opus 保留给追求音质的用户，但排在 AAC 之后。 */
    private fun audioRank(candidate: YouTubeStreamCandidate): Int = when (candidate.container.lowercase()) {
        "m4a", "mp4", "aac" -> 3
        "opus", "webm", "webma" -> 2
        else -> 1
    }

    private fun mergedItem(
        videoId: String,
        sourceUrl: String,
        height: Int,
        video: YouTubeStreamCandidate,
        audio: YouTubeStreamCandidate,
        recommended: Boolean,
    ): MediaItem {
        val container = outputContainer(video.container)
        val audioCodec = codecLabel(audio.codec)
        val videoCodec = codecLabel(video.codec)
        return MediaItem(
            id = "yt-np-$videoId-$height",
            type = MediaType.VIDEO,
            mediaUrl = video.url,
            format = container,
            width = null,
            height = height,
            fileSize = combinedSize(video, audio),
            qualityLabel = qualityLabel(height, video, "本地无损封装"),
            previewUrl = video.url,
            downloadStrategy = DownloadStrategy.DIRECT_MERGED,
            downloadSourceUrl = sourceUrl,
            isRecommended = recommended,
            hasAudio = true,
            codecSummary = listOfNotNull(videoCodec, audioCodec).joinToString(" + ").ifBlank { null },
            watermarkNote = "使用 YouTube 公开播放流（视频 itag ${video.itag} / 音频 itag ${audio.itag}），" +
                "视频与音轨在设备本地无损封装，不重新编码。",
            sourceWatermark = SourceWatermark.PUBLIC_ORIGINAL,
            companionMediaUrl = audio.url,
        )
    }

    private fun muxedItem(
        videoId: String,
        sourceUrl: String,
        height: Int,
        stream: YouTubeStreamCandidate,
        recommended: Boolean,
    ): MediaItem = MediaItem(
        id = "yt-np-$videoId-$height",
        type = MediaType.VIDEO,
        mediaUrl = stream.url,
        format = outputContainer(stream.container),
        width = null,
        height = height,
        fileSize = stream.contentLength.takeIf { it > 0 },
        qualityLabel = qualityLabel(height, stream, "音视频合流直链"),
        previewUrl = stream.url,
        downloadStrategy = DownloadStrategy.DIRECT,
        downloadSourceUrl = sourceUrl,
        isRecommended = recommended,
        hasAudio = true,
        codecSummary = codecLabel(stream.codec)?.let { "$it · 合流" },
        watermarkNote = "使用 YouTube 公开合流播放流（itag ${stream.itag}）；更高清晰度由平台以分离流提供。",
        sourceWatermark = SourceWatermark.PUBLIC_ORIGINAL,
    )

    private fun combinedSize(video: YouTubeStreamCandidate, audio: YouTubeStreamCandidate): Long? {
        val videoSize = video.contentLength.takeIf { it > 0 } ?: return null
        val audioSize = audio.contentLength.takeIf { it > 0 } ?: return videoSize
        return videoSize + audioSize
    }

    private fun qualityLabel(height: Int, stream: YouTubeStreamCandidate, suffix: String): String =
        listOfNotNull(
            "${height}p",
            codecLabel(stream.codec),
            stream.fps.takeIf { it > 30 }?.let { "${it}fps" },
            suffix,
        ).joinToString(" · ")
}
