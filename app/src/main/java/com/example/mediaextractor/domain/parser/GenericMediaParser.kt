package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.util.DiagnosticLogger
import com.example.mediaextractor.util.PlatformRecognizer
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class GenericMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        // X/Twitter 页面包含大量头像、推荐帖和导航图片。该平台只交给定向解析器，
        // 避免在单条评论解析失败时退化成整页图片扫描。
        host != "doubao.com" && !host.endsWith(".doubao.com") &&
            host != "x.com" && !host.endsWith(".x.com") &&
            host != "twitter.com" && !host.endsWith(".twitter.com") && host != "t.co"
    }.getOrDefault(false)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("GENERIC_PARSE", "parse_started", mapOf("url" to url))
        runCatching { parseBlocking(url) }.recoverCatching { failure ->
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
        }
    }

    private fun parseBlocking(url: String): ParsedMedia {
        val response = client.newCall(
            Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36",
                )
                .get()
                .build(),
        ).execute()

        response.use {
            DiagnosticLogger.info(
                category = "GENERIC_PARSE",
                event = "http_response",
                details = mapOf(
                    "status" to it.code,
                    "contentType" to it.body?.contentType(),
                    "contentLength" to it.body?.contentLength(),
                    "finalUrl" to it.request.url,
                ),
            )
            if (it.code == 401 || it.code == 403) throw accessRestricted()
            if (it.code == 404 || it.code == 410) {
                throw MediaParseException(
                    MediaParseException.Reason.EXPIRED,
                    "链接已失效或资源已被删除。",
                )
            }
            if (!it.isSuccessful) {
                throw MediaParseException(
                    MediaParseException.Reason.INVALID_RESPONSE,
                    "服务器返回异常状态（${it.code}），请稍后重试。",
                )
            }

            val finalUrl = it.request.url.toString()
            val contentType = it.body?.contentType()?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val directType = mediaType(contentType, finalUrl)
            if (contentType.startsWith("video/") || contentType.startsWith("image/") ||
                contentType.startsWith("audio/")
            ) {
                val item = mediaItem(
                    mediaUrl = finalUrl,
                    type = directType,
                    format = formatOf(contentType, finalUrl),
                    fileSize = it.body?.contentLength()?.takeIf { size -> size >= 0 },
                    isRecommended = true,
                )
                return ParsedMedia(
                    sourceUrl = url,
                    platform = platformName(finalUrl),
                    title = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { null },
                    author = null,
                    thumbnailUrl = if (directType == MediaType.IMAGE || directType == MediaType.GIF) {
                        finalUrl
                    } else {
                        null
                    },
                    items = listOf(item),
                )
            }

            if (!contentType.contains("html") && !contentType.startsWith("text/")) {
                throw noMedia()
            }
            val html = it.body?.string().orEmpty()
            val document = Jsoup.parse(html, finalUrl)
            if (looksRestricted(document, finalUrl)) throw accessRestricted()
            return parseDocument(url, finalUrl, document)
        }
    }

    private fun parseDocument(sourceUrl: String, finalUrl: String, document: Document): ParsedMedia {
        val title = document.meta("meta[property=og:title]", "content")
            ?: document.meta("meta[name=twitter:title]", "content")
            ?: document.title().ifBlank { null }
        val author = document.meta("meta[name=author]", "content")
            ?: document.meta("meta[property=article:author]", "content")
        val thumbnail = document.meta("meta[property=og:image]", "content")
            ?: document.meta("meta[name=twitter:image]", "content")

        val candidates = mutableListOf<Pair<String, MediaType>>()
        listOf(
            "meta[property=og:video]",
            "meta[property=og:video:url]",
            "meta[property=og:video:secure_url]",
            "meta[name=twitter:player:stream]",
        ).forEach { selector ->
            document.select(selector).forEach { element ->
                element.absUrl("content").ifBlank { element.attr("content") }
                    .takeIf(String::isNotBlank)
                    ?.let { candidates += it to MediaType.VIDEO }
            }
        }
        listOf(
            "meta[property=og:audio]",
            "meta[property=og:audio:url]",
            "meta[property=og:audio:secure_url]",
        ).forEach { selector ->
            document.select(selector).forEach { element ->
                element.absUrl("content").ifBlank { element.attr("content") }
                    .takeIf(String::isNotBlank)
                    ?.let { candidates += it to MediaType.AUDIO }
            }
        }
        document.select("video[src], video source[src], audio[src], audio source[src]").forEach { element ->
            val mediaUrl = element.absUrl("src")
            if (mediaUrl.isNotBlank()) {
                val tagType = when (element.tagName()) {
                    "audio" -> MediaType.AUDIO
                    "source" -> if (element.parent()?.tagName() == "audio") MediaType.AUDIO else MediaType.VIDEO
                    else -> MediaType.VIDEO
                }
                candidates += mediaUrl to tagType
            }
        }

        val hasStructuredPlayback = candidates.any { (_, type) ->
            type == MediaType.VIDEO || type == MediaType.AUDIO
        }
        if (!hasStructuredPlayback) {
            document.select("article img, main img, [role=main] img, figure img").forEach { element ->
                val mediaUrl = element.bestImageUrl(document)
                if (mediaUrl != null && !element.looksLikePageChrome(mediaUrl)) {
                    val type = if (mediaType("", mediaUrl) == MediaType.GIF) {
                        MediaType.GIF
                    } else {
                        MediaType.IMAGE
                    }
                    candidates += mediaUrl to type
                }
            }
        }
        thumbnail?.let { candidates += document.resolveUrl(it) to MediaType.COVER }

        val items = candidates
            .filter { (candidate, _) -> candidate.startsWith("http://") || candidate.startsWith("https://") }
            .distinctBy { it.first }
            .take(12)
            .mapIndexed { index, (mediaUrl, preferredType) ->
                mediaItem(
                    mediaUrl = mediaUrl,
                    type = if (preferredType == MediaType.IMAGE) mediaType("", mediaUrl) else preferredType,
                    format = formatOf("", mediaUrl),
                    isRecommended = index == 0 && preferredType != MediaType.COVER,
                )
            }

        DiagnosticLogger.info(
            category = "GENERIC_PARSE",
            event = "document_candidates",
            details = mapOf(
                "rawCount" to candidates.size,
                "selectedCount" to items.size,
                "structuredPlayback" to hasStructuredPlayback,
                "selected" to items.joinToString { "${it.type}:${it.mediaUrl}" },
            ),
        )

        if (items.isNotEmpty() && items.all { it.type == MediaType.AUDIO } &&
            platformName(finalUrl) in VISUAL_PLATFORMS
        ) {
            DiagnosticLogger.warning(
                category = "GENERIC_PARSE",
                event = "audio_only_fallback_rejected_for_visual_platform",
                details = mapOf("platform" to platformName(finalUrl), "count" to items.size),
            )
            throw noMedia()
        }

        if (items.isEmpty()) throw noMedia()
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = platformName(finalUrl),
            title = title,
            author = author,
            thumbnailUrl = thumbnail?.let { document.resolveUrl(it) },
            items = items,
        )
    }

    private fun Document.meta(selector: String, attribute: String): String? =
        selectFirst(selector)?.attr(attribute)?.trim()?.ifBlank { null }

    private fun Document.resolveUrl(value: String): String =
        runCatching { URI(baseUri()).resolve(value).toString() }.getOrDefault(value)

    private fun Element.bestImageUrl(document: Document): String? {
        val srcSetCandidate = attr("srcset")
            .split(',')
            .map { it.trim().substringBefore(' ') }
            .lastOrNull(String::isNotBlank)
        val rawUrl = sequenceOf(
            attr("data-original"),
            attr("data-src"),
            srcSetCandidate,
            attr("src"),
        ).filterNotNull().firstOrNull(String::isNotBlank) ?: return null
        return document.resolveUrl(rawUrl).takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
    }

    private fun Element.looksLikePageChrome(mediaUrl: String): Boolean {
        val width = attr("width").toIntOrNull()
            ?: attr("data-width").toIntOrNull()
        val height = attr("height").toIntOrNull()
            ?: attr("data-height").toIntOrNull()
        // 不能用常见的 160px 阈值：评论区表情包本身经常只有 80–150px。
        if ((width != null && width in 1..47) || (height != null && height in 1..47)) return true

        val description = listOf(
            mediaUrl,
            className(),
            id(),
            attr("alt"),
            attr("role"),
        ).joinToString(" ").lowercase(Locale.ROOT)
        return PAGE_CHROME_MARKERS.any(description::contains)
    }

    private fun looksRestricted(document: Document, finalUrl: String): Boolean {
        val lowered = "${document.title()} ${document.body().text().take(1_500)} $finalUrl"
            .lowercase(Locale.ROOT)
        val hasPasswordForm = document.selectFirst("input[type=password]") != null
        return hasPasswordForm || listOf(
            "sign in to continue",
            "login required",
            "please log in",
            "需要登录",
            "请先登录",
            "访问受限",
        ).any(lowered::contains)
    }

    private fun platformName(url: String): String =
        PlatformRecognizer.recognize(url)?.displayName
            ?: runCatching { URI(url).host }.getOrNull()
            ?: "通用网页"

    private fun mediaItem(
        mediaUrl: String,
        type: MediaType,
        format: String?,
        fileSize: Long? = null,
        isRecommended: Boolean = false,
    ) = MediaItem(
        id = UUID.nameUUIDFromBytes(mediaUrl.toByteArray()).toString(),
        type = type,
        mediaUrl = mediaUrl,
        format = format,
        width = null,
        height = null,
        fileSize = fileSize,
        qualityLabel = null,
        previewUrl = mediaUrl,
        isRecommended = isRecommended,
    )

    private fun mediaType(contentType: String, url: String): MediaType {
        val extension = formatOf(contentType, url)
        return when {
            contentType.startsWith("video/") -> MediaType.VIDEO
            contentType.startsWith("audio/") -> MediaType.AUDIO
            extension == "gif" -> MediaType.GIF
            contentType.startsWith("image/") -> MediaType.IMAGE
            extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
            extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
            else -> MediaType.UNKNOWN
        }
    }

    private fun formatOf(contentType: String, url: String): String? {
        val subtype = contentType.substringAfter('/', "").substringBefore(';').trim()
        if (subtype.isNotBlank()) return subtype.substringAfterLast('+')
        return url.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.length in 2..5 }
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private fun noMedia() = MediaParseException(
        MediaParseException.Reason.NO_MEDIA,
        ParserMessages.NO_MEDIA,
    )

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m3u8")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav")
        val VISUAL_PLATFORMS = setOf(
            "抖音", "TikTok", "哔哩哔哩", "微博", "小红书", "快手", "西瓜视频",
            "AcFun", "优酷", "爱奇艺", "芒果 TV", "腾讯视频", "好看视频",
            "Instagram", "Facebook", "YouTube",
            "豆包",
        )
        val PAGE_CHROME_MARKERS = setOf(
            "avatar", "profile", "icon", "logo", "sprite", "badge", "tracking", "pixel",
        )
    }
}
