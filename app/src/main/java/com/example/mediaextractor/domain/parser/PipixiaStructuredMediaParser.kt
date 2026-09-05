package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
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

/** Reads the public `__INITIAL_STATE__` embedded in a Pipixia share page. */
class PipixiaStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = PipixUrlDetector.isPipix(url)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("PIPPIX_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                DiagnosticLogger.info(
                    category = "PIPPIX_STRUCTURED_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "status" to response.code,
                        "contentType" to response.body?.contentType(),
                        "finalUrl" to response.request.url,
                    ),
                )
                if (response.code == 401 || response.code == 403) throw accessRestricted()
                if (!response.isSuccessful) throw IOException("皮皮虾公开页面返回状态 ${response.code}")
                val html = response.body?.string().orEmpty()
                if (looksRestricted(html)) throw accessRestricted()
                PipixiaStateExtractor.extract(url, response.request.url.toString(), html)
                    ?: throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "皮皮虾公开页面没有返回可读取的媒体资源。",
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
            items = items,
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

/** Matches Pipixia share and work pages without trusting sub-domain details. */
internal object PipixUrlDetector {
    fun isPipix(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT)
        host == "pipix.com" || host.endsWith(".pipix.com")
    }.getOrDefault(false)
}
