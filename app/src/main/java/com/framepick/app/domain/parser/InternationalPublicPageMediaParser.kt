package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.InternationalMediaUrlCanonicalizer
import com.framepick.app.util.PlatformRecognizer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Conservative official-page fallback for public Instagram and Facebook posts. */
class InternationalPublicPageMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean =
        PlatformRecognizer.recognize(url)?.displayName in SUPPORTED_PLATFORMS

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info(
            category = "INTERNATIONAL_PAGE_PARSE",
            event = "parse_started",
            details = mapOf("url" to url),
        )
        runCatching {
            val canonicalUrl = InternationalMediaUrlCanonicalizer.canonicalize(url)
            val platform = PlatformRecognizer.recognize(canonicalUrl)?.displayName.orEmpty()
            if (platform == "YouTube") {
                return@runCatching fetchYouTubePublicMetadata(url, canonicalUrl)
            }
            val response = client.newCall(
                Request.Builder()
                    .url(canonicalUrl)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Referer", platformReferer(platform))
                    .get()
                    .build(),
            ).execute()
            response.use {
                val finalUrl = it.request.url.toString()
                val html = it.body?.string().orEmpty()
                DiagnosticLogger.info(
                    category = "INTERNATIONAL_PAGE_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "platform" to platform,
                        "status" to it.code,
                        "finalUrl" to finalUrl,
                        "bodyLength" to html.length,
                    ),
                )
                if (it.code == 401 || it.code == 403 || it.code == 429 ||
                    (it.code == 400 && platform in META_PLATFORMS) || isLoginRedirect(finalUrl)
                ) {
                    throw accessRestricted(platform)
                }
                if (it.code == 404 || it.code == 410) {
                    throw MediaParseException(
                        MediaParseException.Reason.EXPIRED,
                        "链接已失效、帖子已删除或不再公开。",
                    )
                }
                if (!it.isSuccessful) throw IOException("$platform 公开页面返回状态 ${it.code}")
                InternationalPublicPageMediaExtractor.extract(url, finalUrl, html)
                    ?: throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "$platform 公开页面没有返回可读取的媒体资源。",
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
                category = "INTERNATIONAL_PAGE_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "platform" to parsed.platform,
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "INTERNATIONAL_PAGE_PARSE",
                event = "parse_failed",
                failure = failure,
            )
        }
    }

    private fun fetchYouTubePublicMetadata(sourceUrl: String, canonicalUrl: String): ParsedMedia {
        val endpoint = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", canonicalUrl)
            .addQueryParameter("format", "json")
            .build()
        client.newCall(
            Request.Builder()
                .url(endpoint)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            DiagnosticLogger.info(
                category = "INTERNATIONAL_PAGE_PARSE",
                event = "youtube_public_metadata_response",
                details = mapOf("status" to response.code, "bodyLength" to body.length),
            )
            if (response.code == 404 || response.code == 410) {
                throw MediaParseException(
                    MediaParseException.Reason.EXPIRED,
                    "YouTube 视频已删除、链接已失效或不再公开。",
                )
            }
            if (response.code == 401 || response.code == 403 || response.code == 429) {
                throw accessRestricted("YouTube")
            }
            if (!response.isSuccessful) throw IOException(
                "YouTube 公开元数据返回状态 ${response.code}",
            )
            return YouTubePublicMetadataExtractor.extract(sourceUrl, canonicalUrl, body)
                ?: throw MediaParseException(
                    MediaParseException.Reason.INVALID_RESPONSE,
                    "YouTube 返回了无法识别的公开元数据。",
                )
        }
    }

    private fun platformReferer(platform: String): String = when (platform) {
        "Instagram" -> "https://www.instagram.com/"
        "Facebook" -> "https://www.facebook.com/"
        "YouTube" -> "https://www.youtube.com/"
        else -> "https://www.google.com/"
    }

    private fun isLoginRedirect(url: String): Boolean = runCatching {
        val path = URI(url).path.orEmpty().lowercase(Locale.ROOT)
        path.startsWith("/accounts/login") || path.startsWith("/login") ||
            path.startsWith("/checkpoint")
    }.getOrDefault(false)

    private fun accessRestricted(platform: String) = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        when (platform) {
            "Instagram" ->
                "Instagram 没有向未登录访问公开此内容，或匿名访问频率受限。拾帧不导入 Cookie，请确认链接无需登录即可打开。"
            "Facebook" ->
                "Facebook 没有向未登录访问公开此内容，或该帖子存在地区、年龄或账号限制。拾帧不导入 Cookie。"
            "YouTube" ->
                "YouTube 当前限制了匿名访问。拾帧不导入 Cookie、账号凭据或播放令牌。"
            else -> ParserMessages.ACCESS_RESTRICTED
        },
    )

    private companion object {
        val SUPPORTED_PLATFORMS = setOf("Instagram", "Facebook", "YouTube")
        val META_PLATFORMS = setOf("Instagram", "Facebook")
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "Chrome/124.0.0.0 Safari/537.36"
    }
}

internal object YouTubePublicMetadataExtractor {
    private val mapper = ObjectMapper()

    fun extract(sourceUrl: String, canonicalUrl: String, json: String): ParsedMedia? {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val thumbnailUrl = root.path("thumbnail_url").asText().trim()
            .takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: return null
        val width = root.path("thumbnail_width").asInt().takeIf { it > 0 }
        val height = root.path("thumbnail_height").asInt().takeIf { it > 0 }
        val videoId = canonicalUrl.toHttpUrl().queryParameter("v").orEmpty()
        val imageFormat = thumbnailUrl.substringBefore('?').substringAfterLast('.', "jpg")
            .lowercase(Locale.ROOT).takeIf { it in setOf("jpg", "jpeg", "png", "webp") }
            ?: "jpg"
        val item = MediaItem(
            id = "youtube-$videoId-cover",
            type = MediaType.COVER,
            mediaUrl = thumbnailUrl,
            format = imageFormat,
            width = width,
            height = height,
            fileSize = null,
            qualityLabel = "公开视频封面 · 视频流暂不可用",
            previewUrl = thumbnailUrl,
            downloadStrategy = DownloadStrategy.DIRECT,
            downloadSourceUrl = canonicalUrl,
            isRecommended = true,
            sourceWatermark = SourceWatermark.UNKNOWN,
            watermarkNote = "YouTube 当前拦截了匿名视频流；这里只显示官方公开元数据和封面，不代表视频已解析。",
        )
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "YouTube",
            title = root.path("title").asText().trim().ifBlank { null },
            author = root.path("author_name").asText().trim().ifBlank { null },
            thumbnailUrl = thumbnailUrl,
            items = listOf(item),
        )
    }
}

internal object InternationalPublicPageMediaExtractor {
    private val mapper = ObjectMapper()

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        val platform = PlatformRecognizer.recognize(finalUrl)?.displayName
            ?.takeIf { it == "Instagram" || it == "Facebook" }
            ?: return null
        val document = Jsoup.parse(html, finalUrl)
        if (platform == "Instagram") {
            InstagramCurrentPostExtractor.extract(sourceUrl, finalUrl, document)?.let { return it }
        }
        return extractOpenGraph(sourceUrl, finalUrl, platform, document)
    }

    private fun extractOpenGraph(
        sourceUrl: String,
        finalUrl: String,
        platform: String,
        document: Document,
    ): ParsedMedia? {
        val title = document.firstMeta("meta[property=og:title]", "meta[name=twitter:title]")
            ?: document.title().ifBlank { null }
        val author = document.firstMeta(
            "meta[name=author]",
            "meta[property=article:author]",
            "meta[property=og:site_name]",
        )
        val videoUrls = document.metaUrls(
            "meta[property=og:video:secure_url]",
            "meta[property=og:video:url]",
            "meta[property=og:video]",
            "meta[name=twitter:player:stream]",
        )
        val imageUrls = document.metaUrls(
            "meta[property=og:image:secure_url]",
            "meta[property=og:image]",
            "meta[name=twitter:image]",
        )
        val path = runCatching { URI(finalUrl).path.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        val openGraphType = document.firstMeta("meta[property=og:type]").orEmpty()
        val expectsVideo = openGraphType.contains("video", ignoreCase = true) ||
            path.contains("/reel/") || path.contains("/videos/") || path.contains("/watch/") ||
            path.endsWith("/watch")
        if (videoUrls.isEmpty() && expectsVideo) return null

        val videoWidth = document.firstMeta("meta[property=og:video:width]")?.toIntOrNull()
        val videoHeight = document.firstMeta("meta[property=og:video:height]")?.toIntOrNull()
        val items = buildList {
            videoUrls.forEachIndexed { index, mediaUrl ->
                add(
                    mediaItem(
                        sourceUrl = finalUrl,
                        mediaUrl = mediaUrl,
                        type = MediaType.VIDEO,
                        width = videoWidth,
                        height = videoHeight,
                        index = index,
                        recommended = index == 0,
                    ),
                )
            }
            imageUrls.forEachIndexed { index, mediaUrl ->
                add(
                    mediaItem(
                        sourceUrl = finalUrl,
                        mediaUrl = mediaUrl,
                        type = if (videoUrls.isEmpty()) MediaType.IMAGE else MediaType.COVER,
                        width = document.firstMeta("meta[property=og:image:width]")?.toIntOrNull(),
                        height = document.firstMeta("meta[property=og:image:height]")?.toIntOrNull(),
                        index = videoUrls.size + index,
                        recommended = videoUrls.isEmpty() && index == 0,
                    ),
                )
            }
        }.distinctBy(MediaItem::mediaUrl)
        if (items.isEmpty()) return null
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = platform,
            title = title,
            author = author,
            thumbnailUrl = imageUrls.firstOrNull(),
            items = items,
        )
    }

    internal fun mediaItem(
        sourceUrl: String,
        mediaUrl: String,
        type: MediaType,
        width: Int?,
        height: Int?,
        index: Int,
        recommended: Boolean,
        previewUrl: String? = mediaUrl,
    ): MediaItem {
        val defaultFormat = if (type == MediaType.VIDEO) "mp4" else "jpg"
        val format = runCatching { URI(mediaUrl).path.substringAfterLast('.', "") }
            .getOrNull()?.lowercase(Locale.ROOT)?.takeIf { it.length in 2..5 }
            ?: defaultFormat
        val resolution = width?.let { w -> height?.let { h -> "${w}×$h" } }
        return MediaItem(
            id = UUID.nameUUIDFromBytes("$sourceUrl:$index:$mediaUrl".toByteArray()).toString(),
            type = type,
            mediaUrl = mediaUrl,
            format = format,
            width = width,
            height = height,
            fileSize = null,
            qualityLabel = listOfNotNull(resolution, "平台公开页面源").joinToString(" · "),
            previewUrl = previewUrl,
            downloadSourceUrl = sourceUrl,
            isRecommended = recommended,
            hasAudio = null,
            sourceWatermark = SourceWatermark.UNKNOWN,
            watermarkNote = "直接使用平台公开页面返回的媒体地址；拾帧不修改画面，也不保证平台未做转码。",
        )
    }

    private fun Document.firstMeta(vararg selectors: String): String? = selectors.asSequence()
        .mapNotNull { selector -> selectFirst(selector)?.attr("content")?.trim()?.ifBlank { null } }
        .firstOrNull()

    private fun Document.metaUrls(vararg selectors: String): List<String> = selectors.asSequence()
        .flatMap { selector -> select(selector).asSequence() }
        .mapNotNull { element ->
            element.absUrl("content").ifBlank { element.attr("content") }
                .trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        .distinct()
        .toList()

    internal object InstagramCurrentPostExtractor {
        fun extract(sourceUrl: String, finalUrl: String, document: Document): ParsedMedia? {
            val shortcode = instagramShortcode(finalUrl) ?: return null
            val mediaId = instagramMediaId(shortcode)
            val current = document.select("script").asSequence()
                .map { it.data().ifBlank { it.html() }.trim() }
                .filter { script ->
                    script.contains(shortcode) ||
                        (mediaId != null && script.contains(mediaId)) ||
                        script.contains("\"if_not_gated_logged_out\"")
                }
                .mapNotNull(::parseScriptJson)
                .flatMap { root ->
                    (
                        root.findValues("if_not_gated_logged_out") +
                            root.findParents("code") + root.findParents("shortcode")
                        ).asSequence()
                }
                .firstOrNull { node ->
                    node.path("code").asText() == shortcode ||
                        node.path("shortcode").asText() == shortcode ||
                        mediaId != null && node.path("pk").asText().substringBefore('_') == mediaId
                }
                ?: return null

            val mediaNodes = when {
                current.path("carousel_media").isArray -> current.path("carousel_media").toList()
                current.path("edge_sidecar_to_children").path("edges").isArray ->
                    current.path("edge_sidecar_to_children").path("edges")
                        .mapNotNull { it.path("node").takeIf(JsonNode::isObject) }
                else -> listOf(current)
            }
            val items = mediaNodes.mapIndexedNotNull { index, node ->
                node.toMediaItem(finalUrl, index)
            }.distinctBy(MediaItem::mediaUrl)
            if (items.isEmpty()) return null

            val caption = current.path("caption").path("text").asText().trim().ifBlank {
                current.path("edge_media_to_caption").path("edges").path(0).path("node")
                    .path("text").asText().trim()
            }.ifBlank { null }
            val author = current.path("user").path("full_name").asText().trim().ifBlank {
                current.path("user").path("username").asText().trim()
            }.ifBlank { null }
            return ParsedMedia(
                sourceUrl = sourceUrl,
                platform = "Instagram",
                title = caption ?: document.firstMeta("meta[property=og:title]")
                    ?: document.title().ifBlank { null },
                author = author,
                thumbnailUrl = items.firstNotNullOfOrNull(MediaItem::previewUrl),
                items = items,
            )
        }

        private fun JsonNode.toMediaItem(sourceUrl: String, index: Int): MediaItem? {
            val videoVersion = path("video_versions").asSequence()
                .filter { it.path("url").asText().startsWith("http") }
                .maxByOrNull { it.path("width").asInt() * it.path("height").asInt() }
            val imageVersion = path("image_versions2").path("candidates").asSequence()
                .filter { it.path("url").asText().startsWith("http") }
                .maxByOrNull { it.path("width").asInt() * it.path("height").asInt() }
            val videoUrl = videoVersion?.path("url")?.asText()
                ?.takeIf { it.startsWith("http") }
                ?: path("video_url").asText().takeIf { it.startsWith("http") }
            val imageUrl = imageVersion?.path("url")?.asText()
                ?.takeIf { it.startsWith("http") }
                ?: path("display_url").asText().takeIf { it.startsWith("http") }
            val mediaUrl = videoUrl ?: imageUrl ?: return null
            val dimensions = if (videoUrl != null) videoVersion else imageVersion
            val width = dimensions?.path("width")?.asInt()?.takeIf { it > 0 }
                ?: path("dimensions").path("width").asInt().takeIf { it > 0 }
            val height = dimensions?.path("height")?.asInt()?.takeIf { it > 0 }
                ?: path("dimensions").path("height").asInt().takeIf { it > 0 }
            return mediaItem(
                sourceUrl = sourceUrl,
                mediaUrl = mediaUrl,
                type = if (videoUrl != null) MediaType.VIDEO else MediaType.IMAGE,
                width = width,
                height = height,
                index = index,
                recommended = index == 0,
                previewUrl = imageUrl ?: mediaUrl,
            )
        }

        private fun instagramShortcode(url: String): String? = runCatching {
            val segments = URI(url).path.orEmpty().split('/').filter(String::isNotBlank)
            val routeIndex = segments.indexOfFirst { it in setOf("p", "reel", "reels", "tv") }
            segments.getOrNull(routeIndex + 1)
        }.getOrNull()

        private fun instagramMediaId(shortcode: String): String? = runCatching {
            shortcode.fold(BigInteger.ZERO) { value, character ->
                val digit = INSTAGRAM_CODE_ALPHABET.indexOf(character)
                require(digit >= 0)
                value.multiply(BigInteger.valueOf(64)).add(BigInteger.valueOf(digit.toLong()))
            }.toString()
        }.getOrNull()

        private fun parseScriptJson(script: String): JsonNode? {
            runCatching { mapper.readTree(script) }.getOrNull()?.let { return it }
            val objectText = script.firstJsonObject() ?: return null
            return runCatching { mapper.readTree(objectText) }.getOrNull()
        }

        private fun String.firstJsonObject(): String? {
            val start = indexOf('{')
            if (start < 0) return null
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start until length) {
                val character = this[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        character == '"' -> quoted = false
                    }
                    continue
                }
                when (character) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return substring(start, index + 1)
                    }
                }
            }
            return null
        }

        private const val INSTAGRAM_CODE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
