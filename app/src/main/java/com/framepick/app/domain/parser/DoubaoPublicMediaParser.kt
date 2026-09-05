package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.PublicUrlNormalizer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * Reads only media fields already embedded in an anonymously accessible Doubao page.
 *
 * Field-name compatibility was independently implemented after reviewing
 * Qalxry and Zhanghuaimin-233's GPL-3.0 userscript `doubao-no-watermark`, commit
 * a6ea8a47fe13f76e72f69b47c809443a9919187d. This parser does not copy its
 * logged-in XHR interception or two-image pixel-splicing behavior.
 */
class DoubaoPublicMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = runCatching {
        val host = URI(url).host.orEmpty().lowercase(Locale.ROOT)
        host == "doubao.com" || host.endsWith(".doubao.com")
    }.getOrDefault(false)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("DOUBAO_PARSE", "parse_started", mapOf("url" to url))
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
        }.onFailure { failure ->
            DiagnosticLogger.error(
                category = "DOUBAO_PARSE",
                event = "parse_failed",
                failure = failure,
                details = mapOf("url" to url),
            )
        }
    }

    private fun parseBlocking(url: String): ParsedMedia {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .get()
                .build(),
        ).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val contentType = response.body?.contentType()?.toString().orEmpty()
            DiagnosticLogger.info(
                category = "DOUBAO_PARSE",
                event = "http_response",
                details = mapOf(
                    "status" to response.code,
                    "contentType" to contentType,
                    "contentLength" to response.body?.contentLength(),
                    "finalUrl" to finalUrl,
                ),
            )
            if (response.code == 401 || response.code == 403) throw accessRestricted()
            if (response.code == 404 || response.code == 410) {
                throw MediaParseException(
                    MediaParseException.Reason.EXPIRED,
                    "链接已失效或资源已被删除。",
                )
            }
            if (!response.isSuccessful) {
                throw MediaParseException(
                    MediaParseException.Reason.INVALID_RESPONSE,
                    "豆包公开页面返回异常状态（${response.code}），请稍后重试。",
                )
            }

            val directType = directMediaType(contentType, finalUrl)
            if (directType != null) {
                return ParsedMedia(
                    sourceUrl = url,
                    platform = "豆包",
                    title = finalUrl.substringAfterLast('/').substringBefore('?').ifBlank { null },
                    author = null,
                    thumbnailUrl = finalUrl.takeIf { directType != MediaType.VIDEO },
                    items = listOf(
                        MediaItem(
                            id = UUID.nameUUIDFromBytes(finalUrl.toByteArray()).toString(),
                            type = directType,
                            mediaUrl = finalUrl,
                            format = formatOf(finalUrl, contentType),
                            width = null,
                            height = null,
                            fileSize = response.body?.contentLength()?.takeIf { it >= 0L },
                            qualityLabel = "公开直链资源",
                            previewUrl = finalUrl,
                            downloadStrategy = DownloadStrategy.DIRECT,
                            downloadSourceUrl = url,
                            isRecommended = true,
                            sourceWatermark = SourceWatermark.UNKNOWN,
                        ),
                    ),
                )
            }

            val body = response.body?.string().orEmpty()
            val extracted = DoubaoPublicPageExtractor.extract(finalUrl, body)
                ?: fetchVideoSharingApi(finalUrl)
            if (extracted == null) {
                if (DoubaoPublicPageExtractor.looksAccessRestricted(finalUrl, body)) {
                    throw accessRestricted()
                }
                throw MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    "豆包页面未公开内嵌可下载的原始资源；私有会话或需登录内容不支持提取。",
                )
            }
            return extracted.copy(sourceUrl = url)
        }
    }

    private fun fetchVideoSharingApi(url: String): ParsedMedia? {
        val parameters = DoubaoVideoSharingRequest.fromUrl(url) ?: return null
        DiagnosticLogger.info(
            category = "DOUBAO_PARSE",
            event = "video_share_api_started",
            details = mapOf(
                "shareIdPresent" to parameters.shareId.isNotBlank(),
                "creationIdPresent" to parameters.creationId.isNotBlank(),
                "videoIdPresent" to parameters.videoId.isNotBlank(),
            ),
        )
        val payload = ObjectMapper().writeValueAsString(
            mapOf(
                "share_id" to parameters.shareId,
                "creation_id" to parameters.creationId,
                "vid" to parameters.videoId,
            ),
        )
        val sharedVideo = client.newCall(
            Request.Builder()
                .url(VIDEO_SHARE_ENDPOINT)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.doubao.com")
                .header("Referer", url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            DiagnosticLogger.info(
                category = "DOUBAO_PARSE",
                event = "video_share_api_response",
                details = mapOf(
                    "status" to response.code,
                    "bodyLength" to body.length,
                ),
            )
            if (!response.isSuccessful) return@use null
            DoubaoPublicVideoApiExtractor.extract(url, body)?.let { return@use it }

            // Keep the generic public-field fallback for older response shapes.
            val escaped = body.replace("<", "\\u003c")
            DoubaoPublicPageExtractor.extract(
                url,
                "<script type=\"application/json\">$escaped</script>",
            )
        }
        if (sharedVideo != null) return sharedVideo

        // Some video-sharing responses only contain metadata. The same public page uses this
        // anonymous endpoint to obtain its playable preview; no Cookie or private signature is sent.
        return parameters.videoId.takeIf(String::isNotBlank)
            ?.let { fetchPublicPlayInfo(url, it) }
    }

    private fun fetchPublicPlayInfo(url: String, videoId: String): ParsedMedia? {
        DiagnosticLogger.info(
            category = "DOUBAO_PARSE",
            event = "public_play_info_started",
            details = mapOf("videoIdPresent" to videoId.isNotBlank()),
        )
        val payload = ObjectMapper().writeValueAsString(mapOf("key" to videoId))
        return client.newCall(
            Request.Builder()
                .url(VIDEO_PLAY_INFO_ENDPOINT)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://www.doubao.com")
                .header("Referer", url)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val parsed = if (response.isSuccessful) {
                DoubaoPublicVideoApiExtractor.extract(url, body)
            } else {
                null
            }
            DiagnosticLogger.info(
                category = "DOUBAO_PARSE",
                event = "public_play_info_response",
                details = mapOf(
                    "status" to response.code,
                    "bodyLength" to body.length,
                    "videoFound" to (parsed != null),
                ),
            )
            parsed
        }
    }

    private fun directMediaType(contentType: String, url: String): MediaType? {
        val lower = contentType.lowercase(Locale.ROOT)
        val extension = formatOf(url, contentType)
        return when {
            lower.startsWith("video/") || extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            lower == "image/gif" || extension == "gif" -> MediaType.GIF
            lower.startsWith("image/") || extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
            else -> null
        }
    }

    private fun formatOf(url: String, contentType: String = ""): String? = contentType
        .substringAfter('/', "")
        .substringBefore(';')
        .lowercase(Locale.ROOT)
        .takeIf(String::isNotBlank)
        ?: url.substringBefore('?').substringAfterLast('.', "")
            .lowercase(Locale.ROOT).takeIf(String::isNotBlank)

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        const val VIDEO_SHARE_ENDPOINT =
            "https://www.doubao.com/creativity/share/get_video_share_info"
        const val VIDEO_PLAY_INFO_ENDPOINT =
            "https://www.doubao.com/samantha/media/get_play_info?" +
                "version_code=20800&language=zh-CN&device_platform=web&aid=497858&" +
                "real_aid=497858&pkg_type=release_version&pc_version=2.51.7&" +
                "samantha_web=1&use-olympus-account=1"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "m3u8")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif")
    }
}

/**
 * Parses the two anonymous public-video response shapes currently used by Doubao.
 *
 * The `original_media_info` fallback shape and request parameters were independently adapted
 * after reviewing ihmily/doubao-nomark (MIT), commit 24e0089eaacbb993e492632e4a9ddaa2e4ed4b08.
 * The response URL itself is inspected for an explicit watermark marker so the UI never labels
 * a watermarked preview as a clean/original download.
 */
internal object DoubaoPublicVideoApiExtractor {
    private val objectMapper = ObjectMapper()

    fun extract(pageUrl: String, body: String): ParsedMedia? {
        val root = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return null
        val data = root.path("data").takeUnless(JsonNode::isMissingNode) ?: return null
        val playInfo = data.path("play_info")
        val originalInfo = data.path("original_media_info")
        val mediaInfo = data.path("media_info").takeIf(JsonNode::isArray)?.firstOrNull()

        val mediaUrl = firstText(
            playInfo.path("main"),
            originalInfo.path("main_url"),
            mediaInfo?.path("main_url"),
        )?.takeIf(::isHttpUrl) ?: return null
        val meta = originalInfo.path("meta")
        val width = firstPositiveInt(playInfo.path("width"), meta.path("width"))
        val height = firstPositiveInt(playInfo.path("height"), meta.path("height"))
        val definition = firstText(playInfo.path("definition"), meta.path("definition"))
        val posterUrl = firstText(
            playInfo.path("poster_url"),
            data.path("poster_url"),
        )?.takeIf(::isHttpUrl)
        val title = firstText(data.path("title"), data.path("prompt"))
            ?: "豆包公开视频"
        val userInfo = data.path("user_info")
        val author = firstText(
            userInfo.path("nickname"),
            userInfo.path("user_name"),
            data.path("nick_name"),
        )
        val explicitlyWatermarked = WATERMARK_MARKERS.any(mediaUrl.lowercase(Locale.ROOT)::contains)
        val sourceWatermark = if (explicitlyWatermarked) {
            SourceWatermark.WATERMARKED
        } else {
            SourceWatermark.UNKNOWN
        }
        val resolution = when {
            width != null && height != null -> "${width}×$height"
            !definition.isNullOrBlank() -> definition
            else -> null
        }
        val item = MediaItem(
            id = UUID.nameUUIDFromBytes(mediaUrl.toByteArray()).toString(),
            type = MediaType.VIDEO,
            mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(mediaUrl),
            format = "mp4",
            width = width,
            height = height,
            fileSize = null,
            qualityLabel = buildList {
                resolution?.let(::add)
                add(if (explicitlyWatermarked) "平台公开预览源（含水印）" else "平台公开播放源")
            }.joinToString(" · "),
            previewUrl = posterUrl?.let(PublicUrlNormalizer::upgradeKnownHttp) ?: mediaUrl,
            downloadStrategy = DownloadStrategy.DIRECT,
            downloadSourceUrl = pageUrl,
            isRecommended = true,
            sourceWatermark = sourceWatermark,
            allowWatermarkedDownload = explicitlyWatermarked,
            watermarkNote = if (explicitlyWatermarked) {
                "豆包匿名公开接口只返回了明确标记为带水印的视频源；可以下载，但文件会原样保留平台水印。"
            } else {
                "豆包公开响应未提供可验证的无水印标记，水印状态未知。"
            },
        )
        return ParsedMedia(
            sourceUrl = pageUrl,
            platform = "豆包",
            title = title,
            author = author,
            thumbnailUrl = posterUrl,
            items = listOf(item),
        )
    }

    private fun firstText(vararg nodes: JsonNode?): String? = nodes.asSequence()
        .filterNotNull()
        .filter(JsonNode::isTextual)
        .map { it.asText().trim() }
        .firstOrNull(String::isNotBlank)

    private fun firstPositiveInt(vararg nodes: JsonNode?): Int? = nodes.asSequence()
        .filterNotNull()
        .mapNotNull { node ->
            when {
                node.isIntegralNumber -> node.asInt()
                node.isTextual -> node.asText().toIntOrNull()
                else -> null
            }
        }
        .firstOrNull { it > 0 }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    private val WATERMARK_MARKERS = setOf(
        "watermark", "logo_type=watermarked", "playwm", "_wm_",
    )
}

internal data class DoubaoVideoSharingRequest(
    val shareId: String,
    val creationId: String,
    val videoId: String,
) {
    companion object {
        fun fromUrl(url: String): DoubaoVideoSharingRequest? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            if (parsed.encodedPath != "/video-sharing") return null
            val request = DoubaoVideoSharingRequest(
                shareId = parsed.queryParameter("share_id").orEmpty(),
                creationId = parsed.queryParameter("creation_id").orEmpty(),
                videoId = parsed.queryParameter("video_id")
                    ?: parsed.queryParameter("vid").orEmpty(),
            )
            return request.takeIf {
                it.shareId.isNotBlank() || it.creationId.isNotBlank() || it.videoId.isNotBlank()
            }
        }
    }
}

internal object DoubaoPublicPageExtractor {
    private val objectMapper = ObjectMapper()
    private val originalImageFields = setOf(
        "image_thumb_ori", "image_ori_raw", "image_raw", "originalimage",
    )
    private val displayImageFields = setOf(
        "image_ori", "image_thumb", "image_preview", "previewimage", "downloadimage",
        "thumbimage",
    )
    private val videoFields = setOf(
        "video_url", "videourl", "video_ori", "video_raw", "play_url", "playurl",
        "download_url", "downloadurl", "main_url", "mainurl", "video_play_url",
        "videoplayurl", "play_addr", "playaddr", "download_addr", "downloadaddr",
    )
    private val allFields = originalImageFields + displayImageFields + videoFields
    private val quotedFieldPattern = Regex(
        """(?i)[\"']?(${allFields.joinToString("|")})[\"']?\s*:\s*(\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*')""",
    )

    fun extract(pageUrl: String, body: String): ParsedMedia? {
        val document = Jsoup.parse(body, pageUrl)
        val state = ExtractionState()
        document.select("script").forEach { script ->
            buildList {
                add(script.data().ifBlank { script.html() })
                SCRIPT_DATA_ATTRIBUTES.mapTo(this) { script.attr(it) }
            }.filter(String::isNotBlank).distinct().forEach { payload ->
                collectPayload(payload, state)
            }
        }
        document.select("meta[property=og:image], meta[name=twitter:image]").forEach { meta ->
            meta.absUrl("content").ifBlank { meta.attr("content") }
                .takeIf(::isHttpUrl)
                ?.let { state.candidates += Candidate("og:image", it, MediaType.IMAGE, false) }
        }
        document.select(
            "meta[property=og:video], meta[property=og:video:url], " +
                "meta[property=og:video:secure_url], meta[name=twitter:player:stream]",
        ).forEach { meta ->
            meta.absUrl("content").ifBlank { meta.attr("content") }
                .takeIf(::isHttpUrl)
                ?.let { state.candidates += Candidate("og:video", it, MediaType.VIDEO, false) }
        }

        val cleanCandidates = state.candidates.asSequence()
            .mapNotNull(::normalizeCandidate)
            .filterNot { looksWatermarked(it.url) }
            .distinctBy { it.url }
            .toList()
        val hasOriginalImages = cleanCandidates.any {
            it.type != MediaType.VIDEO && it.original
        }
        val normalized = cleanCandidates.asSequence()
            .filter { candidate ->
                !hasOriginalImages || candidate.type == MediaType.VIDEO || candidate.original
            }
            .sortedWith(
                compareByDescending<Candidate> { it.original }
                    .thenByDescending { it.type == MediaType.VIDEO },
            )
            .take(MAX_ITEMS)
            .toList()
        if (normalized.isEmpty()) return null

        val items = normalized.mapIndexed { index, candidate ->
            val format = candidate.url.substringBefore('?').substringAfterLast('.', "")
                .lowercase(Locale.ROOT).takeIf { it.length in 2..5 }
                ?: if (candidate.type == MediaType.VIDEO) "mp4" else "jpg"
            MediaItem(
                id = UUID.nameUUIDFromBytes(candidate.url.toByteArray()).toString(),
                type = if (format == "gif") MediaType.GIF else candidate.type,
                mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(candidate.url),
                format = format,
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = when {
                    candidate.original -> "平台公开原始图片字段"
                    candidate.type == MediaType.VIDEO -> "平台公开视频资源"
                    else -> "平台公开图片资源 · 水印状态未知"
                },
                previewUrl = PublicUrlNormalizer.upgradeKnownHttp(candidate.url),
                downloadStrategy = DownloadStrategy.DIRECT,
                downloadSourceUrl = pageUrl,
                isRecommended = index == 0,
                sourceWatermark = if (candidate.original) {
                    SourceWatermark.PUBLIC_ORIGINAL
                } else {
                    SourceWatermark.UNKNOWN
                },
                watermarkNote = if (candidate.original) {
                    "使用豆包公开响应中的原始图片字段；拾帧不修改图片像素。"
                } else {
                    "该公开字段没有可验证的水印标记，拾帧不会拼接或擦除图片水印。"
                },
            )
        }
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?: state.title
            ?: document.title().trim().ifBlank { null }
        val author = document.selectFirst("meta[name=author], meta[property=article:author]")
            ?.attr("content")?.trim()?.ifBlank { null }
            ?: state.author
        return ParsedMedia(
            sourceUrl = pageUrl,
            platform = "豆包",
            title = title,
            author = author,
            thumbnailUrl = items.firstOrNull { it.type != MediaType.VIDEO }?.previewUrl,
            items = items,
        )
    }

    fun looksAccessRestricted(pageUrl: String, body: String): Boolean {
        val lower = body.take(200_000).lowercase(Locale.ROOT)
        val path = runCatching { URI(pageUrl).path.orEmpty() }.getOrDefault("")
        return path.startsWith("/chat/") || listOf(
            "请登录", "登录后继续", "sign in to continue", "login required",
            "passport.doubao.com", "sso.doubao.com",
        ).any(lower::contains)
    }

    private fun parseJson(value: String): JsonNode? {
        val trimmed = value.trim()
        if (!(trimmed.startsWith('{') && trimmed.endsWith('}')) &&
            !(trimmed.startsWith('[') && trimmed.endsWith(']'))
        ) return null
        return runCatching { objectMapper.readTree(trimmed) }.getOrNull()
    }

    private fun collectPayload(value: String, state: ExtractionState) {
        val decoded = org.jsoup.parser.Parser.unescapeEntities(value, false).trim()
        parseJson(decoded)?.let { collectJsonCandidates(it, state) }
        collectQuotedCandidates(decoded, state.candidates)
    }

    private fun collectJsonCandidates(node: JsonNode, state: ExtractionState) {
        when {
            node.isObject -> node.fields().forEach { (name, value) ->
                val normalizedName = name.lowercase(Locale.ROOT)
                if (normalizedName in allFields) {
                    textUrls(value).forEach { url ->
                        state.candidates += candidateOf(normalizedName, url)
                    }
                }
                if (value.isTextual && isHttpUrl(value.asText()) && looksLikeVideoUrl(value.asText())) {
                    state.candidates += Candidate(
                        field = normalizedName,
                        url = value.asText(),
                        type = MediaType.VIDEO,
                        original = false,
                    )
                }
                if (state.title == null && normalizedName in TITLE_FIELDS && value.isTextual) {
                    state.title = value.asText().trim().ifBlank { null }
                }
                if (state.author == null && normalizedName in AUTHOR_FIELDS && value.isTextual) {
                    state.author = value.asText().trim().ifBlank { null }
                }
                collectJsonCandidates(value, state)
            }
            node.isArray -> node.forEach { collectJsonCandidates(it, state) }
            node.isTextual -> {
                val nested = org.jsoup.parser.Parser.unescapeEntities(node.asText(), false).trim()
                parseJson(nested)?.let { collectJsonCandidates(it, state) }
                collectQuotedCandidates(nested, state.candidates)
            }
        }
    }

    private fun textUrls(node: JsonNode): Sequence<String> = sequence {
        when {
            node.isTextual -> yield(node.asText())
            node.isArray -> node.forEach { yieldAll(textUrls(it)) }
            node.isObject -> node.elements().forEach { yieldAll(textUrls(it)) }
        }
    }.filter(::isHttpUrl)

    private fun collectQuotedCandidates(value: String, output: MutableList<Candidate>) {
        quotedFieldPattern.findAll(value).forEach { match ->
            val field = match.groupValues[1].lowercase(Locale.ROOT)
            val decoded = decodeQuotedString(match.groupValues[2])
            if (isHttpUrl(decoded)) output += candidateOf(field, decoded)
        }
    }

    private fun decodeQuotedString(value: String): String {
        if (value.startsWith('"')) {
            return runCatching { objectMapper.readValue(value, String::class.java) }
                .getOrDefault(value.removeSurrounding("\""))
        }
        return value.removeSurrounding("'")
            .replace("\\/", "/")
            .replace("\\'", "'")
    }

    private fun candidateOf(field: String, url: String): Candidate {
        val isVideo = field in videoFields || looksLikeVideoUrl(url)
        return Candidate(
            field = field,
            url = url,
            type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            original = field in originalImageFields,
        )
    }

    private fun looksLikeVideoUrl(url: String): Boolean =
        url.substringBefore('?').substringAfterLast('.', "")
            .lowercase(Locale.ROOT) in VIDEO_URL_EXTENSIONS

    private fun normalizeCandidate(candidate: Candidate): Candidate? {
        val decoded = Jsoup.parse(candidate.url).text().trim()
        if (!isHttpUrl(decoded)) return null
        return candidate.copy(url = decoded)
    }

    private fun looksWatermarked(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return WATERMARK_URL_MARKERS.any(lower::contains)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    private data class Candidate(
        val field: String,
        val url: String,
        val type: MediaType,
        val original: Boolean,
    )

    private data class ExtractionState(
        val candidates: MutableList<Candidate> = mutableListOf(),
        var title: String? = null,
        var author: String? = null,
    )

    private const val MAX_ITEMS = 24
    private val SCRIPT_DATA_ATTRIBUTES = listOf(
        "data-fn-args", "data-router-data", "data-state", "data-props",
    )
    private val TITLE_FIELDS = setOf("share_name", "title")
    private val AUTHOR_FIELDS = setOf("nick_name", "author_name", "user_name")
    private val WATERMARK_URL_MARKERS = setOf(
        "watermark", "_wm_", "i_dld_wm", "i_pre_wm", "playwm",
    )
    private val VIDEO_URL_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "m3u8")
}
