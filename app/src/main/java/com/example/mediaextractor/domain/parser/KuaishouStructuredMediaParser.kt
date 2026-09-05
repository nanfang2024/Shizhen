package com.example.mediaextractor.domain.parser

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
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Reads the public INIT_STATE embedded in a Kuaishou mobile share page. */
class KuaishouStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = PublicUrlNormalizer.hostOf(url)?.let { host ->
        KUAISHOU_HOSTS.any { host == it || host.endsWith(".$it") }
    } == true

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("KUAISHOU_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                DiagnosticLogger.info(
                    category = "KUAISHOU_STRUCTURED_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "status" to response.code,
                        "contentType" to response.body?.contentType(),
                        "finalUrl" to response.request.url,
                    ),
                )
                if (response.code == 401 || response.code == 403) throw accessRestricted()
                if (!response.isSuccessful) throw IOException("快手公开页面返回状态 ${response.code}")
                val html = response.body?.string().orEmpty()
                if (looksRestricted(html)) throw accessRestricted()
                KuaishouStateExtractor.extract(url, response.request.url.toString(), html)
                    ?: throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "快手公开页面没有返回可读取的媒体资源。",
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
                category = "KUAISHOU_STRUCTURED_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "KUAISHOU_STRUCTURED_PARSE",
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
        val KUAISHOU_HOSTS = setOf(
            "kuaishou.com", "chenzhongtech.com", "gifshow.com", "kwai.com",
        )
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal object KuaishouStateExtractor {
    private val mapper = ObjectMapper()
    private val stateAssignment = Regex("window\\.INIT_STATE\\s*=")

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        val document = Jsoup.parse(html, finalUrl)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { stateAssignment.containsMatchIn(it) }
            ?: return null
        val assignment = stateAssignment.find(script) ?: return null
        val json = script.substring(assignment.range.last + 1).trim().removeSuffix(";")
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        // Kuaishou obfuscates the top-level hydration keys. The public payload remains a
        // regular object containing `photo`, so select by structure instead of key name.
        val payload = root.elements().asSequence().firstOrNull { it.path("photo").isObject }
            ?: root.takeIf { it.path("photo").isObject }
            ?: return null
        val photo = payload.path("photo")
        val photoId = photo.path("photoId").asText().ifBlank { sourceUrl.hashCode().toString() }
        val title = photo.path("caption").asText().trim().ifBlank { null }
        val author = photo.path("userName").asText().trim().ifBlank {
            photo.path("user").path("user_name").asText().trim()
        }.ifBlank { null }
        val isPicture = photo.path("singlePicture").asBoolean(false) ||
            photo.path("photoType").asText().contains("PICTURE", ignoreCase = true)
        val items = if (isPicture) {
            photo.publicImageUrls().mapIndexed { index, mediaUrl ->
                MediaItem(
                    id = stableId("$photoId:image:$mediaUrl"),
                    type = MediaType.IMAGE,
                    mediaUrl = mediaUrl,
                    format = mediaUrl.imageFormat(),
                    width = photo.positiveInt("width"),
                    height = photo.positiveInt("height"),
                    fileSize = null,
                    qualityLabel = "页面公开图片",
                    previewUrl = mediaUrl,
                    isRecommended = index == 0,
                    sourceWatermark = SourceWatermark.UNKNOWN,
                    watermarkNote = "使用快手页面公开图片源；平台未提供可验证的水印标记。",
                )
            }
        } else {
            val videos = photo.videoCandidates().mapIndexed { index, candidate ->
                MediaItem(
                    id = stableId("$photoId:video:${candidate.url}"),
                    type = MediaType.VIDEO,
                    mediaUrl = candidate.url,
                    format = "mp4",
                    width = candidate.width,
                    height = candidate.height,
                    fileSize = candidate.fileSize,
                    qualityLabel = listOfNotNull(
                        candidate.width?.let { width ->
                            candidate.height?.let { height -> "${width}×$height" }
                        },
                        candidate.label,
                        "页面公开播放源",
                    ).distinct().joinToString(" · "),
                    previewUrl = candidate.url,
                    isRecommended = index == 0,
                    hasAudio = candidate.hasAudio,
                    codecSummary = candidate.codec,
                    sourceWatermark = SourceWatermark.PUBLIC_CLEAN,
                    watermarkNote = "与快手网页播放器同源（manifest/mainMvUrls）；平台未对网页播放源附加分享水印，作者上传时烧录的标识不在处理范围。",
                )
            }
            val cover = photo.publicCoverUrl()?.let { coverUrl ->
                MediaItem(
                    id = stableId("$photoId:cover:$coverUrl"),
                    type = MediaType.COVER,
                    mediaUrl = coverUrl,
                    format = coverUrl.imageFormat(),
                    width = photo.positiveInt("width"),
                    height = photo.positiveInt("height"),
                    fileSize = null,
                    qualityLabel = "页面封面",
                    previewUrl = coverUrl,
                    sourceWatermark = SourceWatermark.UNKNOWN,
                    watermarkNote = "这是页面公开封面，不是视频原始帧文件。",
                )
            }
            videos + listOfNotNull(cover)
        }
        if (items.isEmpty()) return null
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "快手",
            title = title,
            author = author,
            thumbnailUrl = photo.publicCoverUrl() ?: items.first().previewUrl,
            items = WatermarkPolicy.withRecommendation(items),
        )
    }

    private fun JsonNode.videoCandidates(): List<VideoCandidate> {
        val representations = path("manifest").path("adaptationSet").asSequence()
            .flatMap { it.path("representation").asSequence() }
            .toList()
        val nodes = representations.ifEmpty { path("mainMvUrls").asSequence().toList() }
        return nodes.mapNotNull { node ->
            val url = node.publicUrl("url") ?: node.path("backupUrl").firstPublicUrl()
                ?: return@mapNotNull null
            VideoCandidate(
                url = url,
                width = node.positiveInt("width") ?: positiveInt("width"),
                height = node.positiveInt("height") ?: positiveInt("height"),
                fileSize = node.path("fileSize").asLong().takeIf { it > 0 },
                bitrate = node.path("avgBitrate").asLong().takeIf { it > 0 },
                label = node.path("qualityLabel").asText().trim().ifBlank {
                    node.path("quality").asText().trim()
                }.ifBlank { null },
                hasAudio = !node.path("mute").asBoolean(false),
                codec = node.path("videoCodec").asText().trim().ifBlank { null },
            )
        }.distinctBy(VideoCandidate::url)
            .sortedWith(
                compareByDescending<VideoCandidate> {
                    (it.width?.toLong() ?: 0L) * (it.height?.toLong() ?: 0L)
                }
                    .thenByDescending { it.bitrate ?: 0L },
            )
    }

    private fun JsonNode.publicImageUrls(): List<String> =
        (path("coverUrls").asSequence() + path("webpCoverUrls").asSequence())
            .mapNotNull { it.publicUrl("url") ?: it.asText().toPublicUrl() }
            .distinct()
            .toList()

    private fun JsonNode.publicCoverUrl(): String? = sequenceOf(
        path("coverUrls"), path("webpCoverUrls"), path("headUrls"),
    ).mapNotNull { it.firstPublicUrl() }.firstOrNull()

    private fun JsonNode.firstPublicUrl(): String? = asSequence().mapNotNull { node ->
        node.publicUrl("url") ?: node.asText().toPublicUrl()
    }.firstOrNull()

    private fun JsonNode.publicUrl(field: String): String? = path(field).asText().toPublicUrl()

    private fun String?.toPublicUrl(): String? = this?.takeIf {
        it.startsWith("https://") || it.startsWith("http://")
    }?.let(PublicUrlNormalizer::upgradeKnownHttp)

    private fun JsonNode.positiveInt(field: String): Int? = path(field).asInt().takeIf { it > 0 }

    private fun String.imageFormat(): String {
        val path = runCatching { URI(this).path.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return when {
            path.endsWith(".png") -> "png"
            path.endsWith(".webp") -> "webp"
            path.endsWith(".gif") -> "gif"
            else -> "jpg"
        }
    }

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    private data class VideoCandidate(
        val url: String,
        val width: Int?,
        val height: Int?,
        val fileSize: Long?,
        val bitrate: Long?,
        val label: String?,
        val hasAudio: Boolean,
        val codec: String?,
    )
}
