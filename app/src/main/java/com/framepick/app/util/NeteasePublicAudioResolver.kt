package com.framepick.app.util

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class NeteaseAudioUnavailableException(message: String) : IOException(message)

/** Resolves only the official public NetEase Cloud Music outer-song redirect. */
object NeteasePublicAudioResolver {
    fun outerUrl(songId: String): String =
        "https://music.163.com/song/media/outer/url?id=$songId.mp3"

    fun isOuterUrl(url: String): Boolean = url.toHttpUrlOrNull()?.let { parsed ->
        parsed.host == "music.163.com" && parsed.encodedPath == "/song/media/outer/url"
    } == true

    /**
     * NetEase currently redirects its HTTPS outer URL to an HTTP CDN URL. Android blocks that
     * cleartext hop, so read (but do not follow) the public redirect and use the same CDN URL over
     * HTTPS. Only official music.126.net destinations are accepted.
     */
    @Throws(IOException::class)
    fun resolveHttpsCdnUrl(client: OkHttpClient, outerUrl: String): String {
        if (!isOuterUrl(outerUrl)) throw NeteaseAudioUnavailableException("网易云音频入口无效。")
        val redirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(outerUrl)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        return redirectClient.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw NeteaseAudioUnavailableException("网易云音频入口存在访问限制。")
            }
            val resolved = if (response.isRedirect) {
                val location = response.header("Location")
                    ?: throw NeteaseAudioUnavailableException("网易云音频入口没有返回下载地址。")
                response.request.url.resolve(location)?.toString()
                    ?: throw NeteaseAudioUnavailableException("网易云音频下载地址格式无效。")
            } else if (response.isSuccessful) {
                response.request.url.toString()
            } else {
                throw NeteaseAudioUnavailableException("网易云音频入口返回状态 ${response.code}。")
            }
            val secure = PublicUrlNormalizer.upgradeKnownHttp(resolved)
            val parsed = secure.toHttpUrlOrNull()
                ?: throw NeteaseAudioUnavailableException("网易云音频下载地址格式无效。")
            if (parsed.scheme != "https" ||
                (parsed.host != "music.126.net" && !parsed.host.endsWith(".music.126.net"))
            ) {
                throw NeteaseAudioUnavailableException("网易云音频入口返回了非官方资源地址。")
            }
            secure
        }
    }

    fun totalBytes(response: Response): Long? {
        val fromRange = response.header("Content-Range")
            ?.substringAfterLast('/', "")
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
        return fromRange ?: response.body?.contentLength()?.takeIf { it > 1 }
    }

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
}
