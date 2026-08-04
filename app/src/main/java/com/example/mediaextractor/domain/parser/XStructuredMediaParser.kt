package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
import com.example.mediaextractor.util.DiagnosticLogger
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * X/Twitter 的保守兜底解析器。
 *
 * 这里只接受单条 status URL，并且只读取 Open Graph/Twitter Card 元数据。故意不扫描页面
 * 中的 img 标签，避免把头像、推荐内容和相邻回复混入当前评论的结果。
 */
class XStructuredMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = STATUS_URL.matches(url)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("X_STRUCTURED_PARSE", "parse_started", mapOf("url" to url))
        runCatching {
            val response = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute()
            response.use {
                DiagnosticLogger.info(
                    category = "X_STRUCTURED_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "status" to it.code,
                        "contentType" to it.body?.contentType(),
                        "finalUrl" to it.request.url,
                    ),
                )
                if (it.code == 401 || it.code == 403) throw accessRestricted()
                if (!it.isSuccessful) throw IOException("X 返回状态 ${it.code}")
                val document = Jsoup.parse(it.body?.string().orEmpty(), it.request.url.toString())
                val pageText = "${document.title()} ${document.body().text().take(800)}"
                    .lowercase(Locale.ROOT)
                if (listOf("sign in", "log in", "登录", "访问受限").any(pageText::contains)) {
                    throw accessRestricted()
                }

                val title = document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.trim()?.ifBlank { null }
                    ?: document.selectFirst("meta[name=twitter:title]")?.attr("content")
                        ?.trim()?.ifBlank { null }
                val image = sequenceOf(
                    document.selectFirst("meta[property=og:image]")?.attr("content"),
                    document.selectFirst("meta[name=twitter:image]")?.attr("content"),
                ).filterNotNull().map(::normalizePublicImage).firstOrNull { it != null }
                val video = sequenceOf(
                    document.selectFirst("meta[property=og:video:secure_url]")?.attr("content"),
                    document.selectFirst("meta[property=og:video]")?.attr("content"),
                    document.selectFirst("meta[name=twitter:player:stream]")?.attr("content"),
                ).filterNotNull().map(String::trim).firstOrNull {
                    it.startsWith("https://") || it.startsWith("http://")
                }

                val item = when {
                    video != null -> mediaItem(video, MediaType.VIDEO, video.formatFromUrl())
                    image != null -> {
                        val format = image.formatFromUrl()
                        mediaItem(
                            image,
                            if (format == "gif") MediaType.GIF else MediaType.IMAGE,
                            format,
                        )
                    }
                    else -> throw MediaParseException(
                        MediaParseException.Reason.NO_MEDIA,
                        "这条 X / Twitter 状态没有找到可公开读取的媒体。",
                    )
                }
                DiagnosticLogger.info(
                    category = "X_STRUCTURED_PARSE",
                    event = "media_selected",
                    details = mapOf(
                        "type" to item.type,
                        "format" to item.format,
                        "mediaUrl" to item.mediaUrl,
                        "hasImage" to (image != null),
                        "hasVideo" to (video != null),
                    ),
                )

                ParsedMedia(
                    sourceUrl = url,
                    platform = "X / Twitter",
                    title = title,
                    author = null,
                    thumbnailUrl = image,
                    items = listOf(item),
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
                    MediaParseException.Reason.NO_MEDIA,
                    ParserMessages.NO_MEDIA,
                    failure,
                )
            }
        }
    }

    private fun normalizePublicImage(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (url.host != "pbs.twimg.com" || !url.encodedPath.startsWith("/media/")) return null
        return url.newBuilder().setQueryParameter("name", "orig").build().toString()
    }

    private fun String.formatFromUrl(): String? {
        val url = toHttpUrlOrNull()
        return url?.queryParameter("format")?.lowercase(Locale.ROOT)
            ?: url?.encodedPath?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
                ?.takeIf { it.length in 2..5 }
    }

    private fun mediaItem(url: String, type: MediaType, format: String?) = MediaItem(
        id = UUID.nameUUIDFromBytes(url.toByteArray()).toString(),
        type = type,
        mediaUrl = url,
        format = format,
        width = null,
        height = null,
        fileSize = null,
        qualityLabel = if (type == MediaType.IMAGE) "公开原图" else null,
        previewUrl = url,
        isRecommended = true,
        sourceWatermark = SourceWatermark.PUBLIC_ORIGINAL,
        watermarkNote = "已请求 X / Twitter 公开图片的原始尺寸（name=orig），不做画面后处理。",
    )

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private companion object {
        val STATUS_URL = Regex(
            "https?://(?:www\\.|mobile\\.)?(?:x|twitter)\\.com/[^/?#]+/status/\\d+(?:[/?#].*)?",
            RegexOption.IGNORE_CASE,
        )
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}
