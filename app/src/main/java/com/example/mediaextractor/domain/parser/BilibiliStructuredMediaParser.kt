package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.DownloadStrategy
import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
import com.example.mediaextractor.domain.model.WatermarkPolicy
import com.example.mediaextractor.util.DiagnosticLogger
import com.example.mediaextractor.util.PublicUrlNormalizer
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
 * Parses public Bilibili video and opus pages. Video streams come from the
 * anonymous `playurl` endpoint (`fnval=0`, merged mp4 `durl`), so downloads
 * stay DIRECT; yt-dlp remains the fallback when this parser fails.
 */
class BilibiliStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = BilibiliUrlDetector.isVideoPage(url)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("BILIBILI_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            val page = fetchPage(url)
            val meta = BilibiliStateExtractor.extractVideoMeta(url, page.html)
            if (meta != null) {
                buildVideoResult(url, meta)
            } else {
                val images = BilibiliStateExtractor.extractOpusImages(url, page.html)
                if (images.isNullOrEmpty()) {
                    throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "B站公开页面没有返回可读取的媒体资源。",
                    )
                }
                buildOpusResult(url, images, page.html)
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
                category = "BILIBILI_STRUCTURED_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "BILIBILI_STRUCTURED_PARSE",
                event = "parse_failed_falling_back",
                failure = failure,
            )
        }
    }

    private class PageResponse(val finalUrl: String, val html: String)

    private fun fetchPage(url: String): PageResponse {
        client.newCall(
            Request.Builder()
                .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .get()
                .build(),
        ).execute().use { response ->
            DiagnosticLogger.info(
                category = "BILIBILI_STRUCTURED_PARSE",
                event = "http_response",
                details = mapOf(
                    "status" to response.code,
                    "finalUrl" to response.request.url,
                ),
            )
            if (response.code == 401 || response.code == 403) throw accessRestricted()
            if (!response.isSuccessful) throw IOException("B站公开页面返回状态 ${response.code}")
            val html = response.body?.string().orEmpty()
            if (looksRestricted(html)) throw accessRestricted()
            return PageResponse(response.request.url.toString(), html)
        }
    }

    private fun buildVideoResult(sourceUrl: String, meta: BilibiliStateExtractor.BiliVideoMeta): ParsedMedia {
        val pageUrl = BilibiliUrlDetector.videoPageUrl(meta.bvid)
        val videos = anonymousQualityCandidates(meta, pageUrl)
        if (videos.isEmpty()) {
            // Anonymous playurl access failed; let the fallback chain try
            // yt-dlp instead of pretending a metadata-only result is success.
            throw MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                "B站匿名公开播放源暂不可用，请稍后重试。",
            )
        }
        val cover = meta.picUrl?.let { pic ->
            MediaItem(
                id = stableId("bilibili:${meta.bvid}:cover:$pic"),
                type = MediaType.COVER,
                mediaUrl = pic,
                format = "jpg",
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "页面封面",
                previewUrl = pic,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "这是页面公开封面，不是视频画面原始帧文件。",
            )
        }
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "哔哩哔哩",
            title = meta.title,
            author = meta.ownerName,
            thumbnailUrl = meta.picUrl,
            items = WatermarkPolicy.withRecommendation(videos + listOfNotNull(cover)),
        )
    }

    /** Probes anonymous quality tiers (720P/480P/360P) and keeps distinct results. */
    private fun anonymousQualityCandidates(
        meta: BilibiliStateExtractor.BiliVideoMeta,
        pageUrl: String,
    ): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val seenQualities = mutableSetOf<Int>()
        for (qn in ANONYMOUS_QUALITY_TIERS) {
            val json = fetchPlayUrlJson(meta.bvid, meta.cid, qn, pageUrl) ?: continue
            val built = BilibiliStateExtractor.buildQualityItems(pageUrl, json)
            if (built.isEmpty()) continue
            val quality = BilibiliStateExtractor.playUrlQuality(json) ?: continue
            if (!seenQualities.add(quality)) continue
            items += built
        }
        return items
            .distinctBy { it.qualityLabel }
            .sortedByDescending { it.height ?: 0 }
    }

    private fun fetchPlayUrlJson(bvid: String, cid: Long, qn: Int, pageUrl: String): String? =
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=0&fourm=1")
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Referer", pageUrl)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()
            }
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "BILIBILI_STRUCTURED_PARSE",
                event = "playurl_request_failed",
                details = mapOf("qn" to qn),
                failure = failure,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun buildOpusResult(sourceUrl: String, images: List<String>, html: String): ParsedMedia {
        val title = Jsoup.parse(html).title()
            .removeSuffix("_哔哩哔哩_bilibili")
            .removeSuffix(" - 哔哩哔哩")
            .trim()
            .takeIf { it.isNotBlank() }
        val imageItems = images.mapIndexed { index, imageUrl ->
            MediaItem(
                id = stableId("bilibili:opus:$imageUrl"),
                type = MediaType.IMAGE,
                mediaUrl = imageUrl,
                format = imageUrl.imageFormat(),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "页面公开图片",
                previewUrl = imageUrl,
                isRecommended = index == 0,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "使用B站公开动态图文页图片源；平台未提供可验证的水印标记。",
            )
        }
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "哔哩哔哩",
            title = title,
            author = null,
            thumbnailUrl = images.firstOrNull(),
            items = WatermarkPolicy.withRecommendation(imageItems),
        )
    }

    private fun looksRestricted(html: String): Boolean {
        val text = Jsoup.parse(html).text().take(5_000)
        return listOf("请输入验证码", "登录后查看", "访问受限", "大会员专享").any(text::contains)
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private fun stableId(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()

    private fun String.imageFormat(): String {
        val path = runCatching { java.net.URI(this).path.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            path.endsWith(".gif") -> "gif"
            path.endsWith(".jpeg") -> "jpeg"
            else -> "jpg"
        }
    }

    private companion object {
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** Anonymous tiers only; higher qualities require login and are not fetched. */
        val ANONYMOUS_QUALITY_TIERS = listOf(64, 32, 16)
    }
}

/**
 * Reads the public `__INITIAL_STATE__` embedded in a Bilibili video page and
 * the anonymous `playurl` JSON responses.
 *
 * Structure source: publicly documented Bilibili web shapes (sandbox capture
 * was blocked by platform risk control; see Phase 1 design D5).
 */
internal object BilibiliStateExtractor {
    private val mapper = ObjectMapper()
    private val stateAssignment = Regex("window\\.__INITIAL_STATE__\\s*=")

    internal data class BiliVideoMeta(
        val bvid: String,
        val cid: Long,
        val title: String?,
        val picUrl: String?,
        val ownerName: String?,
        val durationSeconds: Long?,
    )

    /** Parses `__INITIAL_STATE__.videoData` from a public video page. */
    fun extractVideoMeta(sourceUrl: String, html: String): BiliVideoMeta? {
        val root = readInitialState(html) ?: return null
        val videoData = root.path("videoData")
        if (!videoData.isObject) return null
        val bvid = videoData.path("bvid").asText().trim()
        val cid = videoData.path("cid").asLong()
        if (bvid.isBlank() || cid <= 0L) return null
        return BiliVideoMeta(
            bvid = bvid,
            cid = cid,
            title = videoData.path("title").asText().trim().ifBlank { null },
            picUrl = videoData.path("pic").asText().trim().toPublicUrl(),
            ownerName = videoData.path("owner").path("name").asText().trim().ifBlank { null },
            durationSeconds = videoData.path("duration").asLong().takeIf { it > 0L },
        )
    }

    /** Reads the image list of a public opus / dynamic picture page. */
    fun extractOpusImages(sourceUrl: String, html: String): List<String>? {
        val root = readInitialState(html) ?: return null
        val dynamic = root.path("item").path("modules").path("module_dynamic")
        val candidates = sequenceOf(
            dynamic.path("drawItem").asSequence()
                .mapNotNull { it.path("pic").path("src").asText().toPublicUrl() },
            dynamic.path("attach_pictures").asSequence()
                .mapNotNull { it.path("img_src").asText().toPublicUrl() },
            dynamic.path("pictures").asSequence()
                .mapNotNull { it.path("image_src").asText().toPublicUrl() },
        ).flatMap { it }
        val images = candidates.distinct().toList()
        if (images.isNotEmpty()) return images
        // Structural fallback: any picture-like node inside the opus payload.
        return root.findValues("img_src").asSequence()
            .mapNotNull { it.asText().toPublicUrl() }
            .distinct()
            .toList()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * Builds DIRECT download items from an anonymous `playurl` response
     * (`fnval=0`, which returns a merged mp4 `durl` instead of split DASH).
     */
    fun buildQualityItems(videoPageUrl: String, playUrlJson: String): List<MediaItem> {
        val root = runCatching { mapper.readTree(playUrlJson) }.getOrNull() ?: return emptyList()
        if (root.path("code").asInt(-1) != 0) return emptyList()
        val data = root.path("data")
        if (!data.isObject) return emptyList()
        val quality = data.path("quality").asInt().takeIf { it > 0 } ?: return emptyList()
        val label = qualityLabel(quality)
        return data.path("durl").asSequence().mapNotNull { segment ->
            val url = segment.path("url").asText().toPublicUrl() ?: return@mapNotNull null
            MediaItem(
                id = stableId("bilibili:$videoPageUrl:$quality:$url"),
                type = MediaType.VIDEO,
                mediaUrl = url,
                format = "mp4",
                width = null,
                height = qualityHeight(quality),
                fileSize = segment.path("size").asLong().takeIf { it > 0L },
                qualityLabel = label,
                previewUrl = url,
                downloadStrategy = DownloadStrategy.DIRECT,
                downloadSourceUrl = videoPageUrl,
                isRecommended = true,
                hasAudio = true,
                codecSummary = "H.264 / AAC · mp4 合流",
                watermarkNote = "使用B站匿名公开播放直链（$label 档位）；更高清晰度可能需要平台登录，本应用不绕过。",
                sourceWatermark = SourceWatermark.PUBLIC_CLEAN,
            )
        }.distinctBy(MediaItem::mediaUrl).toList()
    }

    /** Reads the effective quality code of an anonymous playurl response. */
    fun playUrlQuality(playUrlJson: String): Int? {
        val root = runCatching { mapper.readTree(playUrlJson) }.getOrNull() ?: return null
        if (root.path("code").asInt(-1) != 0) return null
        return root.path("data").path("quality").asInt().takeIf { it > 0 }
    }

    private fun readInitialState(html: String): JsonNode? {
        val document = Jsoup.parse(html)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { stateAssignment.containsMatchIn(it) }
            ?: return null
        val assignment = stateAssignment.find(script) ?: return null
        val json = script.substring(assignment.range.last + 1)
            .firstJsonObject()
            ?: return null
        return runCatching { mapper.readTree(json) }.getOrNull()
    }

    /** The state script keeps executable code after the JSON object, so a
     *  balanced-object slice is required (same technique as Xigua SSR data). */
    private fun String.firstJsonObject(): String? {
        val start = indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until length) {
            val char = this[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
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

    private fun qualityLabel(quality: Int): String = when (quality) {
        16 -> "360P 流畅 · 匿名公开档位"
        32 -> "480P 清晰 · 匿名公开档位"
        64 -> "720P 高清 · 匿名公开档位"
        74 -> "720P60 高帧率 · 匿名公开档位"
        80 -> "1080P 高清 · 匿名公开档位"
        112 -> "1080P+ 高码率 · 匿名公开档位"
        116 -> "1080P60 高帧率 · 匿名公开档位"
        120 -> "4K 超清 · 匿名公开档位"
        else -> "清晰度 $quality · 匿名公开档位"
    }

    private fun qualityHeight(quality: Int): Int? = when (quality) {
        16 -> 360
        32 -> 480
        64 -> 720
        74 -> 720
        80 -> 1080
        112 -> 1080
        116 -> 1080
        120 -> 2160
        else -> null
    }

    private fun String?.toPublicUrl(): String? = this?.trim()
        ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    private fun stableId(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}

/** Matches Bilibili video, opus, bangumi pages and the b23.tv short domain. */
internal object BilibiliUrlDetector {
    fun isBilibili(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv"
    }.getOrDefault(false)

    fun isVideoPage(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        // b23.tv is an opaque short-link domain: claim the whole domain and let
        // the redirect target decide (non-media targets degrade to NO_MEDIA
        // and the fallback chain continues).
        if (host == "b23.tv") return@runCatching true
        (host == "bilibili.com" || host == "m.bilibili.com") &&
            (uri.path.orEmpty().startsWith("/video/") ||
                uri.path.orEmpty().startsWith("/opus/") ||
                uri.path.orEmpty().startsWith("/bangumi/play/"))
    }.getOrDefault(false)

    fun videoPageUrl(bvid: String): String = "https://www.bilibili.com/video/$bvid"
}
