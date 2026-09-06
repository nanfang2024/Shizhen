package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.domain.model.WatermarkPolicy
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.PublicUrlNormalizer
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves WeChat Channels (视频号) share links.
 *
 * The short link `weixin.qq.com/sph/<id>` redirects to a `finder-preview`
 * shell page whose only data channel is `WeixinJSBridge.invoke("openFinderFeed")`
 * — a bridge that exists exclusively inside the WeChat client. The web feed
 * page (`channels.weixin.qq.com/web/pages/feed`) returns an empty shell to
 * anonymous clients as well, so today the public web surface carries no media
 * data at all. This parser therefore:
 *  1. follows the redirect chain and scans the landing page for any public
 *     media URL (defensive: if Tencent ever embeds one again, it is picked up);
 *  2. otherwise fails with an explicit explanation instead of the generic
 *     "platform rule changed" message, so users understand why this platform
 *     behaves differently.
 */
class WeixinChannelsParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = WeixinChannelsLink.isChannelsUrl(url)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info(
            category = "WEIXIN_CHANNELS_PARSE",
            event = "parse_started",
            details = mapOf("url" to url),
        )
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(PublicUrlNormalizer.upgradeKnownHttp(url))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                DiagnosticLogger.info(
                    category = "WEIXIN_CHANNELS_PARSE",
                    event = "http_response",
                    details = mapOf(
                        "status" to response.code,
                        "finalUrl" to response.request.url,
                    ),
                )
                if (response.code == 401 || response.code == 403) throw accessRestricted()
                if (!response.isSuccessful) throw IOException("视频号页面返回状态 ${response.code}")
                val html = response.body?.string().orEmpty()
                val finalUrl = response.request.url.toString()

                WeixinChannelsPageExtractor.findDirectMediaUrl(html)?.let { mediaUrl ->
                    DiagnosticLogger.info(
                        category = "WEIXIN_CHANNELS_PARSE",
                        event = "public_media_found_on_page",
                        details = mapOf("finalUrl" to finalUrl),
                    )
                    return@runCatching WeixinChannelsPageExtractor.buildResult(
                        sourceUrl = url,
                        finalUrl = finalUrl,
                        mediaUrl = mediaUrl,
                    )
                }

                DiagnosticLogger.warning(
                    category = "WEIXIN_CHANNELS_PARSE",
                    event = "client_only_resource_detected",
                    details = mapOf("finalUrl" to finalUrl),
                )
                throw MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    CLIENT_ONLY_MESSAGE,
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
        }
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        CLIENT_ONLY_MESSAGE,
    )

    private companion object {
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

        /**
         * Verified 2026-09 against live endpoints: the finder-preview shell and
         * the web feed page both deliver data only through WeChat-internal
         * channels, so a plain client cannot reach the media payload.
         */
        const val CLIENT_ONLY_MESSAGE =
            "微信视频号的内容只在微信客户端内部分发，公开网页不含媒体数据，" +
                "拾光无痕暂时无法直接解析视频号链接。"
    }
}

internal object WeixinChannelsLink {
    private const val SHORT_HOST = "weixin.qq.com"
    private const val SHORT_PATH_PREFIX = "/sph/"
    private const val CHANNELS_HOST = "channels.weixin.qq.com"

    fun isChannelsUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return@runCatching false
        when {
            // channels.weixin.qq.com also ends with .weixin.qq.com, so it must
            // be checked before the generic short-host branch.
            host == CHANNELS_HOST || host.endsWith(".$CHANNELS_HOST") -> true
            host == SHORT_HOST || host.endsWith(".$SHORT_HOST") ->
                uri.path.orEmpty().startsWith(SHORT_PATH_PREFIX)
            else -> false
        }
    }.getOrDefault(false)
}

internal object WeixinChannelsPageExtractor {
    private val directMediaPattern =
        Regex("https?://[A-Za-z0-9.\\-]*finder\\.video\\.qq\\.com[^\"'\\s\\\\<>]+")

    /**
     * Defensive future-proofing: the public shell embeds no media today, but if
     * Tencent ever returns a public playable URL (or the HTML carries an
     * escaped JSON payload), pick it up instead of failing.
     */
    fun findDirectMediaUrl(html: String): String? {
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/")
        return directMediaPattern.find(unescaped)?.value
    }

    fun buildResult(sourceUrl: String, finalUrl: String, mediaUrl: String): ParsedMedia {
        val item = MediaItem(
            id = stableId("weixin-channels:$mediaUrl"),
            type = MediaType.VIDEO,
            mediaUrl = mediaUrl,
            format = "mp4",
            width = null,
            height = null,
            fileSize = null,
            qualityLabel = "页面公开播放源",
            previewUrl = mediaUrl,
            isRecommended = true,
            hasAudio = true,
            sourceWatermark = SourceWatermark.UNKNOWN,
            watermarkNote = "使用视频号公开页面播放源；页面未提供可验证的水印标记。",
        )
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "微信视频号",
            title = null,
            author = null,
            thumbnailUrl = null,
            items = WatermarkPolicy.withRecommendation(listOf(item)),
        )
    }

    private fun stableId(seed: String): String =
        UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
}
