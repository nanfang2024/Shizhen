package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.domain.model.WatermarkPolicy
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.PublicUrlNormalizer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Pipixia parsing, two stages:
 * 1. ppxvod direct-link flow (verified approach): the h5 item page embeds
 *    percent-encoded ppxvod CDN URLs; `dr=6&dy_q` marks the watermark-free
 *    Douyin-API source, `lr=superb` marks the watermarked fallback.
 * 2. `__INITIAL_STATE__` flow (legacy fallback) for image posts and page drift.
 */
class PipixiaStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = PipixUrlDetector.isPipix(url)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("PIPPIX_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        val result = runCatching {
            parsePpxvodDirect(url)
        }.recoverCatching { directFailure ->
            DiagnosticLogger.warning(
                category = "PIPPIX_STRUCTURED_PARSE",
                event = "ppxvod_direct_failed_falling_back",
                failure = directFailure,
            )
            parseInitialState(url)
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
                category = "PIPPIX_STRUCTURED_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "PIPPIX_STRUCTURED_PARSE",
                event = "parse_failed_falling_back",
                failure = failure,
            )
        }
        result
    }

    /** Verified flow: h5 item page -> percent-encoded ppxvod direct links. */
    private fun parsePpxvodDirect(url: String): ParsedMedia {
        val rawItemId = PipixItemIdExtractor.fromUrl(url)
            ?: throw MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                "无法从链接中识别皮皮虾作品 ID。",
            )
        val itemId = expandShortLinkIfNeeded(rawItemId)
        val html = fetchDetailPage(itemId)
        return PipixiaDirectExtractor.extract(url, itemId, html)
            ?: throw MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                "皮皮虾作品页没有返回可读取的视频直链。",
            )
    }

    /** `/s/` short codes redirect to the numeric `/item/NNN` page. */
    private fun expandShortLinkIfNeeded(itemId: String): String {
        if (itemId.all(Char::isDigit)) return itemId
        client.newCall(
            Request.Builder()
                .url("https://h5.pipix.com/s/$itemId/")
                .header("User-Agent", IPHONE_USER_AGENT)
                .header("Referer", PIPIX_REFERER)
                .get()
                .build(),
        ).execute().use { response ->
            if (!response.isSuccessful) throw IOException("皮皮虾短链返回状态 ${response.code}")
            val finalUrl = response.request.url.toString()
            return Regex("/item/(\\d+)").find(finalUrl)?.groupValues?.getOrNull(1)
                ?: throw MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    "皮皮虾短链未能解析出作品 ID。",
                )
        }
    }

    private fun fetchDetailPage(itemId: String): String {
        val detailUrl = "https://h5.pipix.com/ppx/item/$itemId?app_id=1319&app=super"
        client.newCall(
            Request.Builder()
                .url(detailUrl)
                .header("User-Agent", IPHONE_USER_AGENT)
                .header("Referer", PIPIX_REFERER)
                .get()
                .build(),
        ).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw accessRestricted()
            if (!response.isSuccessful) throw IOException("皮皮虾作品页返回状态 ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    /** Legacy flow: structured `window.__INITIAL_STATE__` on the share page. */
    private fun parseInitialState(url: String): ParsedMedia {
        client.newCall(
            Request.Builder()
                .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw accessRestricted()
            if (!response.isSuccessful) throw IOException("皮皮虾公开页面返回状态 ${response.code}")
            val html = response.body?.string().orEmpty()
            if (looksRestricted(html)) throw accessRestricted()
            return PipixiaStateExtractor.extract(url, response.request.url.toString(), html)
                ?: throw MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    "皮皮虾公开页面没有返回可读取的媒体资源。",
                )
        }
    }

    private fun looksRestricted(html: String): Boolean {
        val text = Jsoup.parse(html).text().take(5_000)
        return listOf("请输入验证码", "登录后查看", "访问受限").any(text::contains)
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private companion object {
        const val IPHONE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        const val PIPIX_REFERER = "https://h5.pipix.com/"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

/**
 * Reads the public `__INITIAL_STATE__` embedded in a Pipixia share page.
 *
 * Structure source: publicly documented Pipixia h5 share-page shape (sandbox
 * capture was blocked by platform risk control; see Phase 1 design D5).
 */
internal object PipixiaStateExtractor {
    private val mapper = ObjectMapper()
    private val stateAssignment = Regex("window\\.__INITIAL_STATE__\\s*=")
    private const val MAX_SEARCH_DEPTH = 4

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        if (!PipixUrlDetector.isPipix(finalUrl)) return null
        val document = Jsoup.parse(html, finalUrl)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { stateAssignment.containsMatchIn(it) }
            ?: return null
        val assignment = stateAssignment.find(script) ?: return null
        val json = script.substring(assignment.range.last + 1).trim().removeSuffix(";")
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        // Pipixia wraps the work payload under `item.data` today, but the exact
        // intermediate key names are not part of a stable contract. Locate the
        // work node by structure, the same defense used for Kuaishou INIT_STATE.
        val item = locateWorkNode(root) ?: return null

        val itemId = item.path("id").asText().ifBlank {
            item.path("item_id").asText().ifBlank { sourceUrl.hashCode().toString() }
        }
        val title = item.path("content").path("text").asText().trim().ifBlank {
            item.path("content").path("title").asText().trim()
        }.ifBlank { null }
        val author = item.path("author").path("user").path("name").asText().trim().ifBlank {
            item.path("author").path("name").asText().trim()
        }.ifBlank { null }
        val coverUrl = item.publicCoverUrl()

        val videos = item.videoCandidates()
        val images = item.publicImageUrls()
        val videoItems = videos.mapIndexed { index, candidate ->
            MediaItem(
                id = stableId("pipix:$itemId:video:${candidate.url}"),
                type = MediaType.VIDEO,
                mediaUrl = candidate.url,
                format = "mp4",
                width = candidate.width,
                height = candidate.height,
                fileSize = null,
                qualityLabel = listOfNotNull(
                    candidate.width?.let { width ->
                        candidate.height?.let { height -> "${width}×$height" }
                    },
                    "页面公开播放源",
                ).distinct().joinToString(" · "),
                previewUrl = candidate.url,
                isRecommended = index == 0,
                hasAudio = true,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "使用皮皮虾公开页面播放源；页面未提供可验证的水印标记。",
            )
        }
        val imageItems = images.mapIndexed { index, imageUrl ->
            MediaItem(
                id = stableId("pipix:$itemId:image:$imageUrl"),
                type = MediaType.IMAGE,
                mediaUrl = imageUrl,
                format = imageUrl.imageFormat(),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "页面公开图片",
                previewUrl = imageUrl,
                isRecommended = videos.isEmpty() && index == 0,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "使用皮皮虾公开页面图片源；平台未提供可验证的水印标记。",
            )
        }
        val cover = coverUrl?.let { url ->
            MediaItem(
                id = stableId("pipix:$itemId:cover:$url"),
                type = MediaType.COVER,
                mediaUrl = url,
                format = url.imageFormat(),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "页面封面",
                previewUrl = url,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "这是页面公开封面，不是视频画面原始文件。",
            )
        }
        val items = videoItems + imageItems + listOfNotNull(cover)
        if (items.isEmpty()) return null
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "皮皮虾",
            title = title,
            author = author,
            thumbnailUrl = coverUrl ?: items.first().previewUrl,
            items = WatermarkPolicy.withRecommendation(items),
        )
    }

    /** Finds the work payload by structure instead of trusting wrapper key names. */
    private fun locateWorkNode(root: JsonNode): JsonNode? {
        val direct = sequenceOf(
            root.path("item").path("data"),
            root.path("item"),
        ).filter { it.isObject }
        direct.firstOrNull { it.looksLikeWork() }?.let { return it }
        return searchWorkNode(root, 0)
    }

    private fun searchWorkNode(node: JsonNode, depth: Int): JsonNode? {
        if (depth > MAX_SEARCH_DEPTH || !node.isObject) return null
        if (node.looksLikeWork()) return node
        for (child in node) {
            if (child.isObject) {
                searchWorkNode(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** A work node exposes a media list plus at least one descriptive field. */
    private fun JsonNode.looksLikeWork(): Boolean {
        val hasMedia = (path("medias").isArray && path("medias").size() > 0) ||
            (path("images").isArray && path("images").size() > 0) ||
            (path("video").isObject && path("video").hasPlayableUrl())
        val hasDescription = path("content").isObject ||
            path("author").isObject ||
            path("cover_image").isObject
        return hasMedia && hasDescription
    }

    private fun JsonNode.hasPlayableUrl(): Boolean = videoUrlLists().any() ||
        path("url_list").size() > 0

    private fun JsonNode.videoUrlLists(): Sequence<JsonNode> = sequenceOf(
        "video_high_url_list",
        "video_low_url_list",
        "video_fallback_url_list",
    ).asSequence().flatMap { path(it).asSequence() }

    private data class VideoCandidate(
        val url: String,
        val width: Int?,
        val height: Int?,
    )

    private fun JsonNode.videoCandidates(): List<VideoCandidate> {
        val fromMedias = path("medias").asSequence().mapNotNull { media ->
            val url = media.publicUrlFrom("content_url")
                ?: media.publicUrlFrom("url")
                ?: return@mapNotNull null
            VideoCandidate(
                url = url,
                width = media.positiveInt("width"),
                height = media.positiveInt("height"),
            )
        }
        val fromVideo = path("video").run {
            videoUrlLists().mapNotNull { entry ->
                val url = entry.publicUrlFrom("url")
                    ?: entry.publicUrlFrom("main_url")
                    ?: entry.asText().toPublicUrl()
                    ?: return@mapNotNull null
                VideoCandidate(url = url, width = null, height = null)
            } + path("url_list").asSequence().mapNotNull { entry ->
                val url = entry.publicUrlFrom("url")
                    ?: entry.asText().toPublicUrl()
                    ?: return@mapNotNull null
                VideoCandidate(url = url, width = null, height = null)
            }
        }
        return (fromMedias + fromVideo)
            .distinctBy(VideoCandidate::url)
            .sortedWith(
                compareByDescending<VideoCandidate> {
                    (it.width?.toLong() ?: 0L) * (it.height?.toLong() ?: 0L)
                },
            )
            .toList()
    }

    private fun JsonNode.publicImageUrls(): List<String> =
        path("images").asSequence().mapNotNull { image ->
            image.path("image_url").publicUrlFrom("url_list")
                ?: image.path("image_url").publicUrlFrom("url")
                ?: image.publicUrlFrom("url")
                ?: image.path("url").firstPublicUrl()
        }.distinct().toList()

    private fun JsonNode.publicCoverUrl(): String? =
        path("cover_image").publicUrlFrom("url_list")
            ?: path("cover_image").publicUrlFrom("url")
            ?: path("cover_image_url").asText().toPublicUrl()

    private fun JsonNode.publicUrlFrom(field: String): String? = when {
        path(field).isArray -> path(field).firstPublicUrl()
        else -> path(field).asText().toPublicUrl()
    }

    private fun JsonNode.firstPublicUrl(): String? = asSequence().mapNotNull { node ->
        node.publicUrlFrom("url") ?: node.asText().toPublicUrl()
    }.firstOrNull()

    private fun String?.toPublicUrl(): String? = this?.trim()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        ?.let(PublicUrlNormalizer::upgradeKnownHttp)

    private fun JsonNode.positiveInt(field: String): Int? =
        path(field).asInt().takeIf { it > 0 }

    private fun stableId(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun String.imageFormat(): String {
        val path = runCatching {
            java.net.URI(this).path.lowercase(Locale.ROOT)
        }.getOrNull().orEmpty()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            path.endsWith(".gif") -> "gif"
            path.endsWith(".jpeg") -> "jpeg"
            else -> "jpg"
        }
    }
}

/** Extracts the item id from share links; short codes are non-numeric. */
internal object PipixItemIdExtractor {
    private val patterns = listOf(
        Regex("h5\\.pipix\\.com/s/([A-Za-z0-9_-]+)"),
        Regex("h5\\.pipix\\.com/ppx/item/(\\d+)"),
        Regex("pipix\\.com/item/(\\d+)"),
        Regex("item/(\\d+)"),
    )

    fun fromUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isNotEmpty() && trimmed.all(Char::isDigit)) return trimmed
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(trimmed)?.groupValues?.getOrNull(1)
        }
    }
}

/**
 * Ranks ppxvod CDN candidates by watermark-free tier, then v26 nodes.
 * The item page embeds the URLs percent-encoded, so scanning runs on both the
 * raw and the decoded body.
 */
internal object PpxvodUrlScanner {
    data class Candidate(val url: String, val baseUrl: String, val tier: Tier)

    enum class Tier(
        val label: String,
        val watermark: SourceWatermark,
        val note: String,
    ) {
        CLEAN(
            "公开无水印直链",
            SourceWatermark.PUBLIC_ORIGINAL,
            "已选择页面标记的抖音 API 无水印直链（dr=6&dy_q），拾光无痕不做画面后处理。",
        ),
        PARTIAL(
            "部分去水印直链",
            SourceWatermark.PUBLIC_CLEAN,
            "已选择页面标记的 dr=6 直链；该链路通常无水印，但页面未带 dy_q 校验标记。",
        ),
        WATERMARKED(
            "平台水印源（兜底）",
            SourceWatermark.WATERMARKED,
            "页面只返回了带水印的 lr=superb 源，已如实标注。",
        ),
        UNKNOWN(
            "页面公开播放源",
            SourceWatermark.UNKNOWN,
            "使用皮皮虾公开页面播放源；页面未提供可验证的水印标记。",
        ),
    }

    private val urlPattern = Regex("""https?://[^\s"'\\<>]+ppxvod\.com[^\s"'\\<>]*""")

    fun scan(html: String): List<Candidate> {
        val variants = sequenceOf(
            html,
            html.replace("\\/", "/"),
            LenientPercentDecoder.decode(html),
        ).filter(String::isNotEmpty).distinct()
        return variants.flatMap { text -> urlPattern.findAll(text).map { it.value } }
            .map(::cleanup)
            .filter { it.startsWith("http") && "ppxvod.com" in it }
            .map { url ->
                Candidate(
                    url = url,
                    baseUrl = url.substringBefore('?'),
                    tier = tierOf(url),
                )
            }
            .sortedWith(
                compareBy<Candidate> { it.tier.ordinal }
                    .thenByDescending { it.baseUrl.contains("v26-cdn") },
            )
            .distinctBy { it.baseUrl }
            .toList()
    }

    private fun cleanup(url: String): String {
        val unescaped = url
            .replace("\\/", "/")
            .replace("\\\"", "")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
        return if ('%' in unescaped) LenientPercentDecoder.decode(unescaped) else unescaped
    }

    private fun tierOf(url: String): Tier = when {
        "dr=6" in url && "dy_q" in url -> Tier.CLEAN
        "dr=6" in url -> Tier.PARTIAL
        "lr=superb" in url -> Tier.WATERMARKED
        else -> Tier.UNKNOWN
    }
}

/**
 * Python `unquote`-style percent decoding: `%XY` is decoded only when both
 * following characters are hex digits, otherwise the `%` stays untouched, and
 * `+` is never treated as a space. `java.net.URLDecoder` throws on real h5
 * pages (CSS values like `rgba(...) -5.1%, rgba(...)`) and would also corrupt
 * signed URLs containing literal `+`, which silently emptied the decoded scan
 * variant on device.
 */
internal object LenientPercentDecoder {
    fun decode(text: String): String {
        val bytes = ByteArray(text.length)
        val decoded = StringBuilder(text.length)
        var buffered = 0
        var index = 0
        var inEscapeRun = false
        while (index < text.length) {
            val char = text[index]
            if (char == '%' && index + 2 < text.length &&
                text[index + 1].isHexDigit() && text[index + 2].isHexDigit()
            ) {
                bytes[buffered++] = text.substring(index + 1, index + 3).toInt(16).toByte()
                index += 3
                inEscapeRun = true
                continue
            }
            if (inEscapeRun) {
                decoded.append(String(bytes, 0, buffered, Charsets.UTF_8))
                buffered = 0
                inEscapeRun = false
            }
            decoded.append(char)
            index++
        }
        if (inEscapeRun) decoded.append(String(bytes, 0, buffered, Charsets.UTF_8))
        return decoded.toString()
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

/** Builds ParsedMedia from the ppxvod direct links found on the item page. */
internal object PipixiaDirectExtractor {
    private val metaDescription = Regex("""<meta\s+name="description"\s+content="([^"]*)"""")
    private val titleTag = Regex("""<title>([^<]*)</title>""")
    private val ogImage = Regex("""<meta\s+property="og:image"\s+content="([^"]*)"""")

    fun extract(sourceUrl: String, itemId: String, html: String): ParsedMedia? {
        val candidates = PpxvodUrlScanner.scan(html)
        if (candidates.isEmpty()) return null
        val chosen = candidates.first()
        val backup = candidates.firstOrNull { it.baseUrl != chosen.baseUrl }
        val cover = ogImage.find(html)?.groupValues?.getOrNull(1)
            ?.takeIf { it.startsWith("http") }
            ?.let(PublicUrlNormalizer::upgradeKnownHttp)
        val item = MediaItem(
            id = stableId("pipix-direct:$itemId:${chosen.url}"),
            type = MediaType.VIDEO,
            mediaUrl = chosen.url,
            backupUrl = backup?.url,
            format = "mp4",
            width = null,
            height = null,
            fileSize = null,
            qualityLabel = chosen.tier.label,
            previewUrl = chosen.url,
            isRecommended = true,
            hasAudio = true,
            sourceWatermark = chosen.tier.watermark,
            watermarkNote = chosen.tier.note,
        )
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "皮皮虾",
            title = extractTitle(html),
            author = null,
            thumbnailUrl = cover,
            items = WatermarkPolicy.withRecommendation(listOf(item)),
        ).also { parsed ->
            DiagnosticLogger.info(
                category = "PIPPIX_STRUCTURED_PARSE",
                event = "ppxvod_direct_candidates",
                details = mapOf(
                    "itemId" to itemId,
                    "tier" to chosen.tier.name,
                    "candidates" to candidates.size,
                    "backupPresent" to (backup != null),
                ),
            )
        }
    }

    private fun extractTitle(html: String): String? = sequenceOf(
        metaDescription.find(html)?.groupValues?.getOrNull(1),
        titleTag.find(html)?.groupValues?.getOrNull(1),
    ).filterNotNull()
        .map { it.trim() }
        .map { it.removeSuffix(" - 皮皮虾").trim() }
        .firstOrNull { it.isNotEmpty() && it != "皮皮虾" }
        ?.take(80)

    private fun stableId(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}

/** Matches Pipixia share and work pages without trusting sub-domain details. */
internal object PipixUrlDetector {
    fun isPipix(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT)
        host == "pipix.com" || host.endsWith(".pipix.com")
    }.getOrDefault(false)
}
