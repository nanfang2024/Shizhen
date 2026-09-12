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
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/** Reads only media exposed in the public state of a Xiaohongshu/RedNote note page. */
class XiaohongshuStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = PublicUrlNormalizer.hostOf(url)?.let { host ->
        XHS_HOSTS.any { host == it || host.endsWith(".$it") }
    } == true

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("XHS_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            val parsed = client.newCall(
                Request.Builder()
                    .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                DiagnosticLogger.info(
                    category = "XHS_STRUCTURED_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "status" to response.code,
                        "contentType" to response.body?.contentType(),
                        "finalUrl" to response.request.url,
                    ),
                )
                if (response.code == 401 || response.code == 403) throw accessRestricted()
                if (!response.isSuccessful) throw IOException("小红书公开页面返回状态 ${response.code}")
                val html = response.body?.string().orEmpty()
                if (looksRestricted(html)) throw accessRestricted()
                XiaohongshuStateExtractor.extract(url, response.request.url.toString(), html)
                    ?: throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "小红书公开页面没有返回可读取的媒体资源。",
                    )
            }
            probePublicOriginalImages(parsed)
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
                category = "XHS_STRUCTURED_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                    "publicOriginals" to parsed.items.count {
                        it.sourceWatermark == SourceWatermark.PUBLIC_ORIGINAL
                    },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "XHS_STRUCTURED_PARSE",
                event = "parse_failed_falling_back",
                failure = failure,
            )
        }
    }

    private fun looksRestricted(html: String): Boolean {
        val text = Jsoup.parse(html).text().take(5_000)
        return listOf("请输入验证码", "安全验证", "登录后查看", "访问受限").any(text::contains)
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private suspend fun probePublicOriginalImages(parsed: ParsedMedia): ParsedMedia = coroutineScope {
        val items = parsed.items.map { item ->
            async(Dispatchers.IO) {
                if (item.sourceWatermark != SourceWatermark.PUBLIC_ORIGINAL ||
                    item.type !in setOf(MediaType.IMAGE, MediaType.COVER)
                ) {
                    return@async item
                }
                runCatching {
                    client.newCall(
                        Request.Builder()
                            .url(item.mediaUrl)
                            .header("User-Agent", BROWSER_USER_AGENT)
                            .header("Range", "bytes=0-0")
                            .get()
                            .build(),
                    ).execute().use { response ->
                        val mime = response.body?.contentType()?.toString()
                            ?.substringBefore(';')
                            ?.lowercase(Locale.ROOT)
                        if (!response.isSuccessful || mime?.startsWith("image/") != true) {
                            throw IOException("原始图片探测返回状态 ${response.code} / $mime")
                        }
                        item.copy(
                            format = when (mime) {
                                "image/jpeg" -> "jpg"
                                "image/png" -> "png"
                                "image/webp" -> "webp"
                                "image/gif" -> "gif"
                                else -> item.format
                            },
                            fileSize = response.header("Content-Range")
                                ?.substringAfterLast('/', "")
                                ?.toLongOrNull()
                                ?.takeIf { it > 0 }
                                ?: response.body?.contentLength()?.takeIf { it > 1 },
                        )
                    }
                }.onFailure { failure ->
                    DiagnosticLogger.warning(
                        category = "XHS_STRUCTURED_PARSE",
                        event = "original_image_probe_failed",
                        details = mapOf("url" to item.mediaUrl),
                        failure = failure,
                    )
                }.getOrElse {
                    item.copy(
                        sourceWatermark = SourceWatermark.UNKNOWN,
                        watermarkNote = "公开原始图片入口暂时无法验证；请预览确认后再下载。",
                    )
                }
            }
        }.awaitAll()
        parsed.copy(items = items)
    }

    private companion object {
        val XHS_HOSTS = setOf("xiaohongshu.com", "xhslink.com", "xhslink.cn", "rednote.com")
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal object XiaohongshuStateExtractor {
    private val mapper = ObjectMapper()
    private val stateAssignment = Regex("window\\.__INITIAL_STATE__\\s*=")
    private val undefinedValue = Regex("(?<=[:,\\[])\\s*undefined\\s*(?=[,}\\]])")

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        val document = Jsoup.parse(html, finalUrl)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { stateAssignment.containsMatchIn(it) }
            ?: return null
        val assignment = stateAssignment.find(script) ?: return null
        val json = script.substring(assignment.range.last + 1).trim().removeSuffix(";")
            .replace(undefinedValue, "null")
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val note = root.findPublicNote(finalUrl) ?: return null
        val rawImageOrigin = root.findPublicRawImageOrigin()
        val noteId = note.path("noteId").asText().ifBlank {
            runCatching { URI(finalUrl).path.substringAfterLast('/') }.getOrDefault("")
        }.ifBlank { sourceUrl.hashCode().toString() }
        val title = note.path("title").asText().trim().ifBlank {
            note.path("desc").asText().trim().take(80)
        }.ifBlank { null }
        val author = sequenceOf("nickname", "nickName", "name")
            .map { note.path("user").path(it).asText().trim() }
            .firstOrNull(String::isNotBlank)

        val videoCandidates = note.path("video").collectVideoCandidates()
        val imageCandidates = note.path("imageList").asSequence().mapNotNull { image ->
            image.toImageCandidate(rawImageOrigin)
        }.distinctBy(ImageCandidate::url).toList()

        val items = if (videoCandidates.isNotEmpty()) {
            val videos = videoCandidates.mapIndexed { index, candidate ->
                MediaItem(
                    id = stableId("$noteId:video:${candidate.url}"),
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
                        if (candidate.isOriginal) "公开原始源" else "页面公开播放源",
                    ).distinct().joinToString(" · "),
                    previewUrl = candidate.url,
                    downloadSourceUrl = finalUrl,
                    isRecommended = index == 0,
                    hasAudio = true,
                    codecSummary = candidate.codec,
                    sourceWatermark = if (candidate.isOriginal) {
                        SourceWatermark.PUBLIC_ORIGINAL
                    } else {
                        SourceWatermark.UNKNOWN
                    },
                    watermarkNote = if (candidate.isOriginal) {
                        "使用页面公开的 originVideoKey 原始视频入口。"
                    } else {
                        "使用页面公开播放源；平台未提供可验证的水印标记。"
                    },
                )
            }
            val cover = imageCandidates.firstOrNull()?.let { image ->
                MediaItem(
                    id = stableId("$noteId:cover:${image.url}"),
                    type = MediaType.COVER,
                    mediaUrl = image.url,
                    format = image.format,
                    width = image.width,
                    height = image.height,
                    fileSize = null,
                    qualityLabel = "页面封面",
                    previewUrl = image.url,
                    downloadSourceUrl = finalUrl,
                    sourceWatermark = if (image.isOriginal) {
                        SourceWatermark.PUBLIC_ORIGINAL
                    } else {
                        SourceWatermark.UNKNOWN
                    },
                    watermarkNote = if (image.isOriginal) {
                        "使用页面公开 fileId 对应的原始图片；不会移除作者自行添加的署名或标识。"
                    } else {
                        "这是页面公开封面，不是视频原始帧文件。"
                    },
                )
            }
            videos + listOfNotNull(cover)
        } else {
            imageCandidates.mapIndexed { index, image ->
                MediaItem(
                    id = stableId("$noteId:image:${image.url}"),
                    type = if (image.format == "gif") MediaType.GIF else MediaType.IMAGE,
                    mediaUrl = image.url,
                    format = image.format,
                    width = image.width,
                    height = image.height,
                    fileSize = null,
                    qualityLabel = listOfNotNull(
                        image.width?.let { width -> image.height?.let { height -> "${width}×$height" } },
                        if (image.isOriginal) "公开原始尺寸" else "页面最高公开尺寸",
                    ).joinToString(" · "),
                    previewUrl = image.url,
                    downloadSourceUrl = finalUrl,
                    isRecommended = index == 0,
                    sourceWatermark = if (image.isOriginal) {
                        SourceWatermark.PUBLIC_ORIGINAL
                    } else {
                        SourceWatermark.UNKNOWN
                    },
                    watermarkNote = if (image.isOriginal) {
                        "使用页面公开 fileId 对应的原始图片，不包含平台下载展示层；作者自行添加的署名或标识会保留。"
                    } else {
                        "使用当前笔记公开图片源；页面未提供可验证的原图/水印标记。"
                    },
                )
            }
        }
        if (items.isEmpty()) return null
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "小红书",
            title = title,
            author = author,
            thumbnailUrl = imageCandidates.firstOrNull()?.url,
            items = WatermarkPolicy.withRecommendation(items),
        )
    }

    private fun JsonNode.findPublicNote(finalUrl: String): JsonNode? {
        path("noteData").path("data").path("noteData").takeIf(JsonNode::isObject)?.let {
            return it
        }
        val detailMap = path("note").path("noteDetailMap")
        if (detailMap.isObject) {
            val noteId = runCatching { URI(finalUrl).path.substringAfterLast('/') }.getOrDefault("")
            detailMap.path(noteId).path("note").takeIf(JsonNode::isObject)?.let { return it }
            detailMap.elements().asSequence().map { it.path("note") }
                .firstOrNull(JsonNode::isObject)?.let { return it }
        }
        return path("note").takeIf { it.isObject && it.has("imageList") }
    }

    /**
     * Newer public note pages expose one full-size image URL next to the hydrated note state.
     * Its HTTPS origin is then combined only with fileIds from the current note, preventing the
     * H5 display transform (for example !h5_1080jpg) from being mistaken for the original image.
     */
    private fun JsonNode.findPublicRawImageOrigin(): String? {
        val preloadImages = path("noteData").path("normalNotePreloadData").path("imagesList")
        return preloadImages.asSequence().flatMap { image ->
            sequenceOf("urlSizeLarge", "url").map { image.path(it).asText().trim() }
        }.mapNotNull { rawUrl ->
            rawUrl.toImageUrl()?.let { normalized ->
                runCatching { URI(normalized) }.getOrNull()?.takeIf { uri ->
                    uri.scheme == "https" && uri.host.orEmpty().lowercase(Locale.ROOT).let { host ->
                        host == "xhscdn.com" || host.endsWith(".xhscdn.com")
                    }
                }
            }
        }.map { uri ->
            val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
            "${uri.scheme}://${uri.host}$port"
        }.firstOrNull()
    }

    private fun JsonNode.collectVideoCandidates(): List<VideoCandidate> {
        if (isMissingNode || isNull) return emptyList()
        val found = mutableListOf<VideoCandidate>()
        fun visit(node: JsonNode) {
            if (node.isObject) {
                val width = node.path("width").asInt().takeIf { it > 0 }
                val height = node.path("height").asInt().takeIf { it > 0 }
                val fileSize = sequenceOf("size", "fileSize")
                    .map { node.path(it).asLong() }.firstOrNull { it > 0 }
                val codec = node.path("videoCodec").asText().trim().ifBlank { null }
                val label = node.path("qualityType").asText().trim().ifBlank { null }
                sequenceOf("masterUrl", "url").forEach { field ->
                    node.path(field).asText().toVideoUrl()?.let { url ->
                        found += VideoCandidate(url, width, height, fileSize, label, codec, false)
                    }
                }
                node.path("backupUrls").asSequence().forEach { backup ->
                    backup.asText().toVideoUrl()?.let { url ->
                        found += VideoCandidate(url, width, height, fileSize, label, codec, false)
                    }
                }
                node.fields().forEachRemaining { (_, child) -> visit(child) }
            } else if (node.isArray) {
                node.forEach(::visit)
            }
        }
        visit(this)
        findValues("originVideoKey").asSequence().map(JsonNode::asText)
            .firstOrNull(String::isNotBlank)?.let { key ->
                val url = "https://sns-video-bd.xhscdn.com/${key.trimStart('/')}"
                found += VideoCandidate(url, null, null, null, "原始视频", null, true)
            }
        return found.distinctBy(VideoCandidate::url)
            .sortedWith(
                compareByDescending<VideoCandidate> { it.isOriginal }
                    .thenByDescending { (it.width?.toLong() ?: 0L) * (it.height?.toLong() ?: 0L) },
            )
    }

    private fun JsonNode.toImageCandidate(rawImageOrigin: String?): ImageCandidate? {
        val urls = buildList {
            sequenceOf("urlDefault", "url", "urlPre").forEach { field ->
                path(field).asText().toImageUrl()?.let(::add)
            }
            path("infoList").asSequence().forEach { info ->
                info.path("url").asText().toImageUrl()?.let(::add)
            }
        }
        val displayUrl = urls.firstOrNull() ?: return null
        val fileId = path("fileId").asText().trim().takeIf { SAFE_FILE_ID.matches(it) }
        val originalUrl = if (rawImageOrigin != null && fileId != null) {
            "$rawImageOrigin/$fileId"
        } else {
            null
        }
        val url = originalUrl ?: displayUrl
        return ImageCandidate(
            url = url,
            width = path("width").asInt().takeIf { it > 0 },
            height = path("height").asInt().takeIf { it > 0 },
            format = if (originalUrl != null) null else url.imageFormat(),
            isOriginal = originalUrl != null,
        )
    }

    private fun String.toVideoUrl(): String? = takeIf {
        (startsWith("https://") || startsWith("http://")) && runCatching {
            val uri = URI(this)
            uri.host.orEmpty().contains("video", ignoreCase = true) ||
                VIDEO_EXTENSIONS.any(uri.path.lowercase(Locale.ROOT)::endsWith)
        }.getOrDefault(false)
    }?.let(PublicUrlNormalizer::upgradeKnownHttp)

    private fun String.toImageUrl(): String? = takeIf {
        startsWith("https://") || startsWith("http://")
    }?.let(PublicUrlNormalizer::upgradeKnownHttp)

    private fun String.imageFormat(): String {
        val lowered = lowercase(Locale.ROOT)
        return when {
            ".png" in lowered -> "png"
            ".webp" in lowered || "webp" in lowered -> "webp"
            ".gif" in lowered -> "gif"
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
        val label: String?,
        val codec: String?,
        val isOriginal: Boolean,
    )

    private data class ImageCandidate(
        val url: String,
        val width: Int?,
        val height: Int?,
        val format: String?,
        val isOriginal: Boolean,
    )

    private val VIDEO_EXTENSIONS = setOf(".mp4", ".m4v", ".mov", ".webm", ".m3u8")
    private val SAFE_FILE_ID = Regex("(?:notes_pre_post/)?[A-Za-z0-9_-]{8,200}")
}
