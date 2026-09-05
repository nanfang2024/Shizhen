package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
import com.example.mediaextractor.domain.model.WatermarkPolicy
import com.example.mediaextractor.util.DiagnosticLogger
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Reads public structured state from Douyin links, including Xigua links using its short domain. */
class DouyinStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT)
        BYTE_DANCE_HOSTS.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("DOUYIN_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            val page = requestPage(url, BROWSER_USER_AGENT, "http_response")
            if (page.code == 401 || page.code == 403) throw MediaParseException(
                MediaParseException.Reason.ACCESS_RESTRICTED,
                ParserMessages.ACCESS_RESTRICTED,
            )
            val route = if (XiguaUrlDetector.isXigua(page.finalUrl)) "xigua" else "douyin"
            DiagnosticLogger.info(
                category = "DOUYIN_STRUCTURED_PARSE",
                event = "redirect_platform_classified",
                details = mapOf("route" to route, "finalUrl" to page.finalUrl),
            )
            if (route == "xigua") {
                page.takeIf(HttpPage::isSuccessful)
                    ?.let { XiguaSsrDataExtractor.extract(url, it.finalUrl, it.html) }
                    ?: parseXiguaFallback(url, page.finalUrl)
                    ?: throw when {
                        page.code == 401 || page.code == 403 -> MediaParseException(
                            MediaParseException.Reason.ACCESS_RESTRICTED,
                            ParserMessages.ACCESS_RESTRICTED,
                        )
                        !page.isSuccessful -> IOException("西瓜视频公开页面返回状态 ${page.code}")
                        else -> MediaParseException(
                            MediaParseException.Reason.NO_MEDIA,
                            "西瓜视频公开页面没有返回可读取的媒体资源。",
                        )
                    }
            } else {
                if (!page.isSuccessful) throw IOException("抖音公开页面返回状态 ${page.code}")
                DouyinRouterDataExtractor.extract(url, page.finalUrl, page.html)
                    ?: throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "抖音公开页面没有返回可读取的媒体资源。",
                    )
            }
        }.recoverCatching { failure ->
            throw when (failure) {
                is MediaParseException -> failure
                is IOException -> MediaParseException(
                    MediaParseException.Reason.NETWORK,
                    ParserMessages.NETWORK_UNAVAILABLE,
                    failure,
                )
                else -> MediaParseException(
                    MediaParseException.Reason.INVALID_RESPONSE,
                    ParserMessages.NO_MEDIA,
                    failure,
                )
            }
        }.onSuccess { parsed ->
            DiagnosticLogger.info(
                category = "DOUYIN_STRUCTURED_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                    "dimensions" to parsed.items.joinToString { "${it.width}x${it.height}" },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "DOUYIN_STRUCTURED_PARSE",
                event = "parse_failed_falling_back",
                failure = failure,
            )
        }
    }

    private fun requestPage(url: String, userAgent: String, event: String): HttpPage {
        val targetHost = url.toHttpUrlOrNull()?.host.orEmpty()
        val referer = if (targetHost == "ixigua.com" || targetHost.endsWith(".ixigua.com")) {
            "https://www.ixigua.com/"
        } else {
            "https://www.iesdouyin.com/"
        }
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", referer)
                .header("Cache-Control", "no-cache")
                .get()
                .build(),
        ).execute()
        return response.use {
            val page = HttpPage(
                code = it.code,
                contentType = it.body?.contentType()?.toString(),
                finalUrl = it.request.url.toString(),
                html = it.body?.string().orEmpty(),
            )
            DiagnosticLogger.info(
                category = "DOUYIN_STRUCTURED_PARSE",
                event = event,
                details = mapOf(
                    "status" to page.code,
                    "contentType" to page.contentType,
                    "finalUrl" to page.finalUrl,
                    "bodyLength" to page.html.length,
                ),
            )
            page
        }
    }

    private fun parseXiguaFallback(sourceUrl: String, failedFinalUrl: String): ParsedMedia? {
        val candidates = XiguaFallbackUrls.candidates(failedFinalUrl)
        for (candidate in candidates) {
            for (userAgent in listOf(BROWSER_USER_AGENT, DESKTOP_USER_AGENT)) {
                val page = runCatching {
                    requestPage(candidate, userAgent, "xigua_fallback_response")
                }.onFailure { failure ->
                    DiagnosticLogger.warning(
                        category = "DOUYIN_STRUCTURED_PARSE",
                        event = "xigua_fallback_request_failed",
                        details = mapOf("candidate" to candidate),
                        failure = failure,
                    )
                }.getOrNull() ?: continue
                if (!page.isSuccessful) continue
                XiguaSsrDataExtractor.extract(sourceUrl, page.finalUrl, page.html)?.let { parsed ->
                    DiagnosticLogger.info(
                        category = "DOUYIN_STRUCTURED_PARSE",
                        event = "xigua_fallback_succeeded",
                        details = mapOf("candidate" to candidate, "items" to parsed.items.size),
                    )
                    return parsed
                }
            }
        }
        DiagnosticLogger.warning(
            category = "DOUYIN_STRUCTURED_PARSE",
            event = "xigua_fallback_exhausted",
            details = mapOf(
                "videoId" to XiguaFallbackUrls.videoId(failedFinalUrl),
                "officialCandidates" to candidates.size,
            ),
        )
        return null
    }

    private data class HttpPage(
        val code: Int,
        val contentType: String?,
        val finalUrl: String,
        val html: String,
    ) {
        val isSuccessful: Boolean get() = code in 200..299
    }

    private companion object {
        val BYTE_DANCE_HOSTS = setOf("douyin.com", "iesdouyin.com", "ixigua.com", "xigua.com")
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}

internal object DouyinRouterDataExtractor {
    private val mapper = ObjectMapper()
    private const val ROUTER_MARKER = "window._ROUTER_DATA ="

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        val document = Jsoup.parse(html, finalUrl)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains(ROUTER_MARKER) }
            ?: return null
        val json = script.substringAfter(ROUTER_MARKER).trim().removeSuffix(";")
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val entries = root.findValues("item_list").asSequence()
            .flatMap { node -> node.elements().asSequence() }
            .toList()
        val item = entries.firstOrNull { node ->
            node.path("images").isArray && node.path("images").size() > 0
        } ?: entries.firstOrNull { it.hasPublicVideo() }
            ?: return null
        val awemeId = item.path("aweme_id").asText().ifBlank { sourceUrl.hashCode().toString() }
        val title = item.path("desc").asText().trim().ifBlank { null }
        val author = item.path("author").path("nickname").asText().trim().ifBlank { null }
        val imageItems = item.path("images").mapIndexedNotNull { index, image ->
            image.toImageItem(awemeId, index, finalUrl)
        }
        val videoItems = if (imageItems.isEmpty()) {
            item.toVideoItems(awemeId, finalUrl)
        } else {
            emptyList()
        }
        val items = WatermarkPolicy.withRecommendation(imageItems.ifEmpty { videoItems })
        if (items.isEmpty()) return null
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "抖音",
            title = title,
            author = author,
            thumbnailUrl = item.path("video").path("cover").firstPublicUrl()
                ?: items.first().previewUrl,
            items = items,
        )
    }

    private fun JsonNode.toImageItem(awemeId: String, index: Int, pageUrl: String): MediaItem? {
        // url_list is the public display source. download_url_list is intentionally ignored:
        // Douyin currently labels those paths with "-water" for this kind of image post.
        val mediaUrl = path("url_list").asSequence()
            .map(JsonNode::asText)
            .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val width = path("width").asInt().takeIf { it > 0 }
        val height = path("height").asInt().takeIf { it > 0 }
        val format = runCatching { URI(mediaUrl).path.substringAfterLast('.', "") }
            .getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.length in 2..5 }
            ?: "webp"
        return MediaItem(
            id = UUID.nameUUIDFromBytes("$awemeId:$index:$mediaUrl".toByteArray()).toString(),
            type = MediaType.IMAGE,
            mediaUrl = mediaUrl,
            format = format,
            width = width,
            height = height,
            fileSize = null,
            qualityLabel = listOfNotNull(
                width?.let { w -> height?.let { h -> "${w}×$h" } },
                "页面最高公开尺寸",
            ).joinToString(" · "),
            previewUrl = mediaUrl,
            downloadSourceUrl = pageUrl,
            isRecommended = index == 0,
            sourceWatermark = SourceWatermark.PUBLIC_ORIGINAL,
            watermarkNote =
                "使用页面公开图片源；已排除 download_url_list 中明确带 water 标记的下载地址。",
        )
    }

    private fun JsonNode.hasPublicVideo(): Boolean {
        val video = path("video")
        if (!video.isObject || video.path("duration").asLong() <= 0L) return false
        return video.path("play_addr").firstPublicUrl()?.isLikelyVideoUrl() == true ||
            video.path("bit_rate").asSequence().any { rate ->
                rate.path("play_addr").firstPublicUrl()?.isLikelyVideoUrl() == true
            }
    }

    private fun JsonNode.toVideoItems(awemeId: String, pageUrl: String): List<MediaItem> {
        val video = path("video")
        val sourceWidth = video.path("width").asInt().takeIf { it > 0 }
        val sourceHeight = video.path("height").asInt().takeIf { it > 0 }
        val candidates = buildList {
            video.path("bit_rate").asSequence().forEachIndexed { index, rate ->
                rate.path("play_addr").toCleanVideoCandidate(
                    label = rate.path("gear_name").asText().trim().ifBlank { "清晰度 ${index + 1}" },
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    bitrate = rate.path("bit_rate").asLong().takeIf { it > 0 },
                )?.let(::add)
            }
            video.path("play_addr").toCleanVideoCandidate(
                label = "720p",
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                bitrate = null,
            )?.let(::add)
        }.distinctBy(VideoCandidate::url)
            .sortedWith(
                compareByDescending<VideoCandidate> { (it.width ?: 0) * (it.height ?: 0) }
                    .thenByDescending { it.bitrate ?: 0L },
            )

        val cleanCandidates = candidates.filterNot(VideoCandidate::watermarked)
        val watermarkedCandidates = candidates.filter(VideoCandidate::watermarked)
        val videos = when {
            cleanCandidates.isNotEmpty() -> cleanCandidates.mapIndexed { index, candidate ->
                cleanVideoItem(awemeId, pageUrl, candidate, index, sourceWidth, sourceHeight)
            }
            watermarkedCandidates.isNotEmpty() -> watermarkedCandidates.mapIndexed { index, candidate ->
                watermarkedVideoItem(awemeId, pageUrl, candidate, index)
            }
            else -> emptyList()
        }
        if (videos.isEmpty()) return emptyList()

        val cover = video.path("cover").toCoverItem(awemeId, pageUrl)
        return videos + listOfNotNull(cover)
    }

    private fun cleanVideoItem(
        awemeId: String,
        pageUrl: String,
        candidate: VideoCandidate,
        index: Int,
        sourceWidth: Int?,
        sourceHeight: Int?,
    ): MediaItem {
        val resolution = candidate.width?.let { width ->
            candidate.height?.let { height -> "${width}×$height" }
        }
        val sourceResolution = sourceWidth?.let { width ->
            sourceHeight?.let { height -> "${width}×$height" }
        }
        return MediaItem(
            id = UUID.nameUUIDFromBytes("$awemeId:video:${candidate.url}".toByteArray()).toString(),
            type = MediaType.VIDEO,
            mediaUrl = candidate.url,
            format = "mp4",
            width = candidate.width,
            height = candidate.height,
            fileSize = candidate.fileSize,
            qualityLabel = listOfNotNull(resolution, candidate.label, "公开无水印播放源")
                .distinct().joinToString(" · "),
            previewUrl = candidate.url,
            downloadSourceUrl = pageUrl,
            isRecommended = index == 0,
            hasAudio = true,
            sourceWatermark = SourceWatermark.PUBLIC_CLEAN,
            watermarkNote = buildString {
                append("使用平台公开 /play/ 播放源，已排除页面返回的 /playwm/ 带水印源。")
                if (sourceResolution != null && sourceResolution != resolution) {
                    append(" 页面标称作品尺寸为 $sourceResolution，但公开文件只验证到 ${resolution ?: candidate.label}，不会虚报为原画。")
                }
            },
        )
    }

    /** Degraded result when the public page only offers watermarked playback. */
    private fun watermarkedVideoItem(
        awemeId: String,
        pageUrl: String,
        candidate: VideoCandidate,
        index: Int,
    ): MediaItem {
        val resolution = candidate.width?.let { width ->
            candidate.height?.let { height -> "${width}×$height" }
        }
        return MediaItem(
            id = UUID.nameUUIDFromBytes("$awemeId:video:${candidate.url}".toByteArray()).toString(),
            type = MediaType.VIDEO,
            mediaUrl = candidate.url,
            format = "mp4",
            width = candidate.width,
            height = candidate.height,
            fileSize = candidate.fileSize,
            qualityLabel = listOfNotNull(resolution, candidate.label, "带水印")
                .distinct().joinToString(" · "),
            previewUrl = candidate.url,
            downloadSourceUrl = pageUrl,
            isRecommended = index == 0,
            hasAudio = true,
            sourceWatermark = SourceWatermark.WATERMARKED,
            watermarkNote = "公开页面仅提供带水印播放源；已禁用下载，可预览确认。",
        )
    }

    private fun JsonNode.toCleanVideoCandidate(
        label: String,
        sourceWidth: Int?,
        sourceHeight: Int?,
        bitrate: Long?,
    ): VideoCandidate? {
        val rawUrl = firstPublicUrl()?.takeIf { it.isLikelyVideoUrl() } ?: return null
        val parsed = rawUrl.toHttpUrlOrNull() ?: return null
        val encodedPath = parsed.encodedPath
        val wasWatermarkedEndpoint = encodedPath.contains("/playwm/", ignoreCase = true) ||
            encodedPath.endsWith("/playwm", ignoreCase = true)
        val isCleanEndpoint = encodedPath.contains("/play/", ignoreCase = true) ||
            encodedPath.endsWith("/play", ignoreCase = true)
        if (!wasWatermarkedEndpoint && !isCleanEndpoint) return null

        val addressWidth = path("width").asInt().takeIf { it > 0 }
        val addressHeight = path("height").asInt().takeIf { it > 0 }
        val cleanUrl = parsed.newBuilder().apply {
            if (wasWatermarkedEndpoint) {
                encodedPath(
                    encodedPath.replace("/playwm/", "/play/", ignoreCase = true)
                        .replace(Regex("/playwm$", RegexOption.IGNORE_CASE), "/play"),
                )
                setQueryParameter("ratio", "720p")
                removeAllQueryParameters("watermark")
            }
        }.build().toString()
        if (cleanUrl.contains("playwm", ignoreCase = true)) {
            // The rewrite could not fully drop the watermarked marker; keep the
            // raw endpoint as a degraded candidate instead of silently dropping
            // it. The WATERMARKED label keeps downloads blocked in the UI.
            return VideoCandidate(
                url = rawUrl,
                label = label,
                width = addressWidth,
                height = addressHeight,
                fileSize = path("data_size").asLong().takeIf { it > 0 },
                bitrate = bitrate,
                watermarked = true,
            )
        }

        val (width, height) = if (wasWatermarkedEndpoint) {
            fitPublic720(sourceWidth, sourceHeight)
        } else {
            addressWidth to addressHeight
        }
        return VideoCandidate(
            url = cleanUrl,
            label = label,
            width = width,
            height = height,
            fileSize = if (wasWatermarkedEndpoint) null else path("data_size").asLong().takeIf { it > 0 },
            bitrate = bitrate,
        )
    }

    private fun JsonNode.toCoverItem(awemeId: String, pageUrl: String): MediaItem? {
        val url = firstPublicUrl() ?: return null
        val width = path("width").asInt().takeIf { it > 0 }
        val height = path("height").asInt().takeIf { it > 0 }
        val format = runCatching { URI(url).path.substringAfterLast('.', "") }
            .getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.length in 2..5 } ?: "webp"
        return MediaItem(
            id = UUID.nameUUIDFromBytes("$awemeId:cover:$url".toByteArray()).toString(),
            type = MediaType.COVER,
            mediaUrl = url,
            format = format,
            width = width,
            height = height,
            fileSize = null,
            qualityLabel = listOfNotNull(
                width?.let { w -> height?.let { h -> "${w}×$h" } },
                "页面封面",
            ).joinToString(" · "),
            previewUrl = url,
            downloadSourceUrl = pageUrl,
            sourceWatermark = SourceWatermark.UNKNOWN,
            watermarkNote = "这是页面公开封面缩略图，不是视频画面原始文件。",
        )
    }

    private fun JsonNode.firstPublicUrl(): String? = path("url_list").asSequence()
        .map(JsonNode::asText)
        .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }

    private fun String.isLikelyVideoUrl(): Boolean {
        val path = runCatching { URI(this).path.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        return !AUDIO_EXTENSIONS.any(path::endsWith)
    }

    private fun fitPublic720(width: Int?, height: Int?): Pair<Int?, Int?> {
        if (width == null || height == null || width <= 0 || height <= 0) return null to null
        val shortEdge = minOf(width, height)
        if (shortEdge <= PUBLIC_SHORT_EDGE) return width to height
        val scale = PUBLIC_SHORT_EDGE.toDouble() / shortEdge
        return even(width * scale) to even(height * scale)
    }

    private fun even(value: Double): Int = ((value.toInt().coerceAtLeast(2)) / 2) * 2

    private data class VideoCandidate(
        val url: String,
        val label: String,
        val width: Int?,
        val height: Int?,
        val fileSize: Long?,
        val bitrate: Long?,
        val watermarked: Boolean = false,
    )

    private const val PUBLIC_SHORT_EDGE = 720
    private val AUDIO_EXTENSIONS = setOf(".mp3", ".m4a", ".aac", ".ogg", ".wav", ".opus")
}
