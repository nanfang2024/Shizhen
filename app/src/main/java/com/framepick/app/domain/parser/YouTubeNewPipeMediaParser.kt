package com.framepick.app.domain.parser

import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.parser.YouTubeStreamMapper.MAX_QUALITY_OPTIONS
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.PublicUrlNormalizer
import java.io.IOException
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * YouTube 匿名解析通道：基于 NewPipeExtractor（与 NewPipe 客户端同源的抽取器）。
 *
 * 解析产出两类条目：
 * - 高分辨率分离流（1080p~4K）：视频轨 + 音频轨两条直链，下载阶段由 FFmpeg
 *   在设备本地 `-c copy` 无损封装，产物不带任何平台转码水印层；
 * - 低分辨率合流流（360p/720p）：单一 mp4 直链，直接下载。
 *
 * 本通道失败后由注册表自然回落到 yt-dlp 通道，两个通道互为兜底。
 */
class YouTubeNewPipeMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT)
        SUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info(
            category = "YT_NEWPIPE_PARSE",
            event = "parse_started",
            details = mapOf("url" to url),
        )
        val result = runCatching {
            NewPipe.init(NewPipeOkHttpDownloader(client))
            val normalized = PublicUrlNormalizer.upgradeKnownHttp(url)
            val info = StreamInfo.getInfo(ServiceList.YouTube, normalized)
            val muxed = info.videoStreams
                .filter { !it.isVideoOnly }
                .filterStream()
                .map(::videoCandidate)
            val videoOnly = info.videoOnlyStreams
                .filterStream()
                .map(::videoCandidate)
            val audioCandidates = info.audioStreams
                .filterStream()
                .map(::audioCandidate)
            val bestAudio = YouTubeStreamMapper.selectBestAudio(audioCandidates)
            DiagnosticLogger.info(
                category = "YT_NEWPIPE_PARSE",
                event = "stream_inventory",
                details = mapOf(
                    "videoId" to info.id,
                    "durationSeconds" to info.duration,
                    "muxed" to muxed.size,
                    "videoOnly" to videoOnly.size,
                    "audio" to audioCandidates.size,
                    "bestAudioItag" to bestAudio?.itag,
                    "muxedItags" to muxed.joinToString { it.itag },
                    "videoOnlyHeights" to videoOnly.map { it.height }.distinct().sortedDescending(),
                ),
            )
            val items = YouTubeStreamMapper.buildItems(
                videoId = info.id,
                sourceUrl = url,
                muxedCandidates = muxed,
                videoOnlyCandidates = videoOnly,
                audioCandidate = bestAudio,
            )
            if (items.isEmpty()) {
                throw MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    ParserMessages.NO_MEDIA,
                )
            }
            ParsedMedia(
                sourceUrl = url,
                platform = PLATFORM,
                title = info.name?.takeIf(String::isNotBlank),
                author = info.uploaderName?.takeIf(String::isNotBlank),
                thumbnailUrl = info.thumbnails
                    .maxByOrNull { it.width * it.height }
                    ?.url
                    ?.takeIf(String::isNotBlank),
                items = items,
            ).also { parsed ->
                DiagnosticLogger.info(
                    category = "YT_NEWPIPE_PARSE",
                    event = "parse_succeeded",
                    details = mapOf(
                        "items" to parsed.items.size,
                        "qualities" to parsed.items.joinToString { it.qualityLabel.orEmpty() },
                        "mergedItems" to parsed.items.count { it.companionMediaUrl != null },
                    ),
                )
            }
        }.recoverCatching { failure ->
            throw mapFailure(failure, url)
        }
        result.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "YT_NEWPIPE_PARSE",
                event = "parse_failed",
                failure = failure,
                details = mapOf("url" to url),
            )
        }
        result
    }

    private fun videoCandidate(stream: VideoStream): YouTubeStreamCandidate =
        YouTubeStreamCandidate(
            itag = stream.itag.takeIf { it > 0 }?.toString() ?: stream.id.orEmpty(),
            url = stream.url.orEmpty(),
            container = stream.format?.suffix ?: "mp4",
            codec = stream.codec,
            height = stream.height.takeIf { it > 0 } ?: 0,
            fps = stream.fps,
            bitrate = stream.bitrate,
            contentLength = stream.contentLengthOrZero(),
        )

    private fun audioCandidate(stream: AudioStream): YouTubeStreamCandidate =
        YouTubeStreamCandidate(
            itag = stream.itag.takeIf { it > 0 }?.toString() ?: stream.id.orEmpty(),
            url = stream.url.orEmpty(),
            container = stream.format?.suffix ?: "m4a",
            codec = stream.codec,
            bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate,
            contentLength = stream.contentLengthOrZero(),
        )

    private fun <T : org.schabi.newpipe.extractor.stream.Stream> List<T>.filterStream(): List<T> =
        filter { stream ->
            stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                !stream.url.isNullOrBlank()
        }

    private fun org.schabi.newpipe.extractor.stream.Stream.contentLengthOrZero(): Long =
        runCatching { itagItem?.contentLength ?: 0L }.getOrDefault(0L)

    private fun mapFailure(failure: Throwable, url: String): MediaParseException {
        if (failure is MediaParseException) return failure
        val text = failure.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            failure is IOException && failure !is ExtractionException ->
                MediaParseException(
                    MediaParseException.Reason.NETWORK,
                    ParserMessages.NETWORK_UNAVAILABLE,
                    failure,
                )
            ACCESS_RESTRICTED_MARKERS.any(text::contains) ->
                MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    "该视频需要登录、会员或存在年龄/地区限制，拾帧不支持提取。",
                    failure,
                )
            else ->
                MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    "YouTube 解析失败（可能被平台风控），拾帧会自动改用备用通道重试。",
                    failure,
                )
        }
    }

    companion object {
        const val PLATFORM = "YouTube"
        private val SUPPORTED_HOSTS = setOf("youtube.com", "youtube-nocookie.com", "youtu.be")
        private val ACCESS_RESTRICTED_MARKERS = listOf(
            "age-restricted", "sign in", "login", "private video",
            "members-only", "paid", "geo", "not available in your country", "blocked",
        )
    }
}
