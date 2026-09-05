package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.jsoup.Jsoup

/** Extracts the single public work represented by a Xigua mobile share page. */
internal object XiguaSsrDataExtractor {
    private const val SSR_MARKER = "window._SSR_DATA ="
    private val mapper = ObjectMapper()

    fun extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia? {
        if (!XiguaUrlDetector.isXigua(finalUrl)) return null
        val document = Jsoup.parse(html, finalUrl)
        val script = document.select("script").asSequence()
            .map { it.data().ifBlank { it.html() } }
            .firstOrNull { it.contains(SSR_MARKER) }
            ?: return null
        val json = script.substringAfter(SSR_MARKER).firstJsonObject() ?: return null
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        val result = root.findValues("videoData").asSequence()
            .map { it.path("result") }
            .firstOrNull { it.isObject && it.path("group_type").asText() == "xigua_video" }
            ?: return null

        val encryptedUrl = result.path("url").asText().trim()
        val mediaUrl = OpenSslCryptoJsAes.decryptReversed(encryptedUrl) ?: return null
        if (!mediaUrl.isPublicHttpUrl()) return null

        val gid = result.path("gid").asText().ifBlank { sourceUrl.hashCode().toString() }
        val title = result.path("title").asText().trim().ifBlank { null }
        val author = result.path("media_user").path("screen_name")
            .asText().trim().ifBlank { null }
        val coverUrl = result.path("cover_image_url").asText().trim()
            .takeIf { it.isPublicHttpUrl() }
        val format = mediaUrl.mediaFormat(default = "mp4")
        val video = MediaItem(
            id = UUID.nameUUIDFromBytes("xigua:$gid:$mediaUrl".toByteArray()).toString(),
            type = MediaType.VIDEO,
            mediaUrl = mediaUrl,
            format = format,
            width = null,
            height = null,
            fileSize = null,
            qualityLabel = "页面公开播放源 · ${format.uppercase(Locale.ROOT)}",
            previewUrl = mediaUrl,
            isRecommended = true,
            hasAudio = true,
            sourceWatermark = SourceWatermark.PUBLIC_ORIGINAL,
            watermarkNote =
                "直接使用西瓜视频公开页面播放器返回的播放源，不裁剪画面、不修改视频内容。",
        )
        val cover = coverUrl?.let { url ->
            MediaItem(
                id = UUID.nameUUIDFromBytes("xigua:$gid:cover:$url".toByteArray()).toString(),
                type = MediaType.COVER,
                mediaUrl = url,
                format = url.mediaFormat(default = "jpg"),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "页面封面",
                previewUrl = url,
                sourceWatermark = SourceWatermark.UNKNOWN,
                watermarkNote = "这是页面公开封面，不是视频画面原始文件。",
            )
        }
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "西瓜视频",
            title = title,
            author = author,
            thumbnailUrl = coverUrl,
            items = listOfNotNull(video, cover),
        )
    }

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

    private fun String.isPublicHttpUrl(): Boolean = runCatching {
        val uri = URI(this)
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun String.mediaFormat(default: String): String {
        val uri = runCatching { URI(this) }.getOrNull() ?: return default
        val mime = uri.rawQuery.orEmpty().split('&').asSequence()
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                pieces.getOrNull(1)?.takeIf { pieces.firstOrNull() == "mime_type" }
            }
            .firstOrNull()
            ?.substringAfter("video_", missingDelimiterValue = "")
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
        return mime ?: uri.path.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .takeIf { it.length in 2..5 }
            ?: default
    }
}

internal object XiguaUrlDetector {
    fun isXigua(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        host == "ixigua.com" || host.endsWith(".ixigua.com") ||
            host == "xigua.com" || host.endsWith(".xigua.com") ||
            ((host == "iesdouyin.com" || host.endsWith(".iesdouyin.com")) &&
                uri.path.orEmpty().startsWith("/xg/"))
    }.getOrDefault(false)
}

/** Removes unstable share parameters and tries only official, cookie-free Xigua page routes. */
internal object XiguaFallbackUrls {
    private val videoIdPattern = Regex("/xg/video/(\\d+)")

    fun candidates(finalUrl: String): List<String> {
        if (!XiguaUrlDetector.isXigua(finalUrl)) return emptyList()
        val videoId = videoId(finalUrl) ?: return emptyList()
        return listOf(
            "https://www.iesdouyin.com/xg/video/$videoId/?app=video_article",
            "https://m.ixigua.com/xg/video/$videoId/?app=video_article",
            "https://m.ixigua.com/video/$videoId",
            "https://m.ixigua.com/dx/$videoId",
        )
    }

    fun videoId(url: String): String? = runCatching { URI(url).path.orEmpty() }.getOrNull()
        ?.let { videoIdPattern.find(it)?.groupValues?.getOrNull(1) }
}

/** CryptoJS passphrase format: reverse(Base64("Salted__" + salt + AES-256-CBC)). */
internal object OpenSslCryptoJsAes {
    private const val PASSPHRASE = "xigua.fe.web_mobile"
    private val saltedPrefix = "Salted__".toByteArray(StandardCharsets.US_ASCII)

    fun decryptReversed(value: String): String? = runCatching {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val payload = Base64.getDecoder().decode(value.reversed())
        require(payload.size > 16 && payload.copyOfRange(0, 8).contentEquals(saltedPrefix))
        val salt = payload.copyOfRange(8, 16)
        val keyAndIv = evpBytesToKey(
            password = PASSPHRASE.toByteArray(StandardCharsets.UTF_8),
            salt = salt,
            requiredBytes = 48,
        )
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES"),
            IvParameterSpec(keyAndIv.copyOfRange(32, 48)),
        )
        String(cipher.doFinal(payload.copyOfRange(16, payload.size)), StandardCharsets.UTF_8)
            .trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: error("Decrypted Xigua value is not a URL")
    }.getOrNull()

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, requiredBytes: Int): ByteArray {
        val output = ArrayList<Byte>(requiredBytes)
        var previous = ByteArray(0)
        while (output.size < requiredBytes) {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(previous)
            digest.update(password)
            digest.update(salt)
            previous = digest.digest()
            previous.forEach(output::add)
        }
        return output.take(requiredBytes).toByteArray()
    }
}
