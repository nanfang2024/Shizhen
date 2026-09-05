package com.framepick.app.domain.parser

import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.NeteaseAudioUnavailableException
import com.framepick.app.util.NeteasePublicAudioResolver
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Parses public NetEase Cloud Music song pages without cookies or login state. */
class NeteaseMusicMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = PublicUrlNormalizer.hostOf(url)?.let { host ->
        NETEASE_PAGE_HOSTS.any { host == it || host.endsWith(".$it") }
    } == true

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("NETEASE_MUSIC_PARSE", "parse_started", mapOf("url" to url))
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
        }.onSuccess { parsed ->
            DiagnosticLogger.info(
                category = "NETEASE_MUSIC_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "items" to parsed.items.size,
                    "types" to parsed.items.joinToString { it.type.name },
                    "audioBytes" to parsed.items.firstOrNull { it.type == MediaType.AUDIO }?.fileSize,
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "NETEASE_MUSIC_PARSE",
                event = "parse_failed_falling_back",
                failure = failure,
            )
        }
    }

    private fun parseBlocking(sourceUrl: String): ParsedMedia {
        val pageResponse = client.newCall(
            Request.Builder()
                .url(PublicUrlNormalizer.upgradeKnownHttp(sourceUrl))
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build(),
        ).execute()
        val metadata = pageResponse.use { response ->
            DiagnosticLogger.info(
                category = "NETEASE_MUSIC_PARSE",
                event = "page_response",
                details = mapOf(
                    "status" to response.code,
                    "contentType" to response.body?.contentType(),
                    "finalUrl" to response.request.url,
                ),
            )
            if (response.code == 401 || response.code == 403) throw accessRestricted()
            if (!response.isSuccessful) throw IOException("网易云公开页面返回状态 ${response.code}。")
            val html = response.body?.string().orEmpty()
            val extracted = NeteaseMusicPageExtractor.extract(response.request.url.toString(), html)
                ?: throw MediaParseException(
                    MediaParseException.Reason.NO_MEDIA,
                    "网易云公开页面没有返回有效的歌曲信息。",
                )
            DiagnosticLogger.info(
                category = "NETEASE_MUSIC_PARSE",
                event = "metadata_extracted",
                details = mapOf(
                    "songId" to extracted.songId,
                    "durationSeconds" to extracted.durationSeconds,
                    "hasTitle" to !extracted.title.isNullOrBlank(),
                ),
            )
            extracted
        }

        val outerUrl = NeteasePublicAudioResolver.outerUrl(metadata.songId)
        val secureAudioUrl = runCatching {
            NeteasePublicAudioResolver.resolveHttpsCdnUrl(client, outerUrl)
        }.getOrElse { failure ->
            if (failure is NeteaseAudioUnavailableException) {
                throw MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    ParserMessages.ACCESS_RESTRICTED,
                    failure,
                )
            }
            throw failure
        }
        val audioProbe = client.newCall(
            Request.Builder()
                .url(secureAudioUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Range", "bytes=0-${MP3_PROBE_BYTES - 1}")
                .get()
                .build(),
        ).execute()
        val resolvedAudio = audioProbe.use { response ->
            val mime = response.body?.contentType()?.toString()
                ?.substringBefore(';')
                ?.lowercase(Locale.ROOT)
            DiagnosticLogger.info(
                category = "NETEASE_MUSIC_PARSE",
                event = "audio_probe",
                details = mapOf(
                    "status" to response.code,
                    "contentType" to mime,
                    "contentRange" to response.header("Content-Range"),
                    "finalUrl" to response.request.url,
                ),
            )
            if (response.code == 401 || response.code == 403 || response.code == 404 ||
                !response.isSuccessful || mime?.startsWith("audio/") != true
            ) {
                throw accessRestricted()
            }
            val fileSize = NeteasePublicAudioResolver.totalBytes(response)
            val sample = response.body?.byteStream()?.use { it.readAtMost(MP3_PROBE_BYTES) }
                ?: byteArrayOf()
            val estimatedAudioSeconds = Mp3DurationEstimator.estimateSeconds(sample, fileSize)
            val previewOnly = NeteasePreviewDetector.isPreview(
                pageDurationSeconds = metadata.durationSeconds,
                estimatedAudioSeconds = estimatedAudioSeconds,
            )
            DiagnosticLogger.info(
                category = "NETEASE_MUSIC_PARSE",
                event = "audio_completeness_checked",
                details = mapOf(
                    "pageDurationSeconds" to metadata.durationSeconds,
                    "estimatedAudioSeconds" to estimatedAudioSeconds,
                    "fileSize" to fileSize,
                    "previewOnly" to previewOnly,
                ),
            )
            ResolvedNeteaseAudio(
                url = PublicUrlNormalizer.upgradeKnownHttp(response.request.url.toString()),
                fileSize = fileSize,
                previewOnly = previewOnly,
            )
        }

        val audioItem = resolvedAudio.toMediaItem(metadata, outerUrl)
        val coverItem = metadata.coverUrl?.let { cover ->
            MediaItem(
                id = stableId("netease:${metadata.songId}:cover"),
                type = MediaType.COVER,
                mediaUrl = cover,
                format = cover.substringBefore('?').substringAfterLast('.', "jpg")
                    .lowercase(Locale.ROOT),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "专辑封面",
                previewUrl = cover,
            )
        }
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "网易云音乐",
            title = metadata.title,
            author = metadata.author,
            thumbnailUrl = metadata.coverUrl,
            items = listOf(audioItem) + listOfNotNull(coverItem),
        )
    }

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    private companion object {
        val NETEASE_PAGE_HOSTS = setOf("163cn.tv", "music.163.com")
        const val MP3_PROBE_BYTES = 64 * 1024
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal data class NeteaseSongMetadata(
    val songId: String,
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val durationSeconds: Int?,
)

internal data class ResolvedNeteaseAudio(
    val url: String,
    val fileSize: Long?,
    val previewOnly: Boolean,
)

internal fun ResolvedNeteaseAudio.toMediaItem(
    metadata: NeteaseSongMetadata,
    outerUrl: String,
): MediaItem = MediaItem(
    id = UUID.nameUUIDFromBytes("netease:${metadata.songId}:audio".toByteArray()).toString(),
    type = MediaType.AUDIO,
    mediaUrl = url,
    format = "mp3",
    width = null,
    height = null,
    fileSize = fileSize,
    qualityLabel = if (previewOnly) {
        "（仅试听片段） · 网易云平台公开试听 · MP3"
    } else {
        "公开音频 · MP3"
    },
    previewUrl = url,
    downloadStrategy = DownloadStrategy.DIRECT,
    downloadSourceUrl = outerUrl,
    isRecommended = !previewOnly,
    hasAudio = true,
    watermarkNote = if (previewOnly) {
        "检测到公开音频明显短于歌曲页面标注时长；仅保存平台公开试听片段，不会尝试获取受限完整歌曲。"
    } else {
        null
    },
    isPreviewOnly = previewOnly,
)

internal object NeteaseMusicPageExtractor {
    private val mapper = ObjectMapper()
    private val reduxStatePrefix = Regex("window\\.REDUX_STATE\\s*=\\s*")
    private val songTitlePattern = Regex("歌曲名[《“](.+?)[》”]")
    private val artistPattern = Regex("由\\s*(.+?)\\s*演唱")

    fun extract(finalUrl: String, html: String): NeteaseSongMetadata? {
        val songId = finalUrl.toHttpUrlOrNull()?.queryParameter("id")
            ?.substringBefore('.')
            ?.takeIf { it.all(Char::isDigit) && it.isNotBlank() }
            ?: Regex("/song/(\\d+)").find(finalUrl)?.groupValues?.getOrNull(1)
            ?: return null
        val document = Jsoup.parse(html, finalUrl)
        val reduxSong = extractReduxSong(html, songId)
        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
        val openGraphTitle = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
        val title = songTitlePattern.find(description)?.groupValues?.getOrNull(1)?.trim()
            ?.ifBlank { null }
            ?: openGraphTitle.substringBefore(" - ").trim().ifBlank { null }
            ?: reduxSong?.path("name")?.asText()?.trim()?.ifBlank { null }
        val author = artistPattern.find(description)?.groupValues?.getOrNull(1)?.trim()
            ?.ifBlank { null }
            ?: openGraphTitle.substringAfter(" - ", "")
                .substringBefore(" - 单曲")
                .trim()
                .ifBlank { null }
            ?: reduxSong?.path("ar")?.asSequence()
                ?.map { it.path("name").asText().trim() }
                ?.filter(String::isNotBlank)
                ?.joinToString(" / ")
                ?.ifBlank { null }
        val cover = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(PublicUrlNormalizer::upgradeKnownHttp)
            ?: reduxSong?.path("al")?.path("picUrl")?.asText()?.trim()
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let(PublicUrlNormalizer::upgradeKnownHttp)
        val durationSeconds = document.selectFirst("meta[property=music:duration]")
            ?.attr("content")
            ?.trim()
            ?.toDoubleOrNull()
            ?.toInt()
            ?.takeIf { it > 0 }
            ?: reduxSong?.path("dt")?.asLong(0L)
                ?.takeIf { it > 0L }
                ?.let { milliseconds -> ((milliseconds + 500L) / 1_000L).toInt() }
        return NeteaseSongMetadata(songId, title, author, cover, durationSeconds)
    }

    private fun extractReduxSong(html: String, songId: String): JsonNode? {
        val stateStart = reduxStatePrefix.find(html)?.range?.last?.plus(1) ?: return null
        val state = JavascriptObjectExtractor.extract(html, stateStart) ?: return null
        val song = runCatching { mapper.readTree(state).path("Song") }.getOrNull()
            ?.takeIf { it.isObject }
            ?: return null
        return song.takeIf { it.path("id").asText() == songId }
    }
}

internal object NeteasePreviewDetector {
    fun isPreview(pageDurationSeconds: Int?, estimatedAudioSeconds: Double?): Boolean {
        val pageDuration = pageDurationSeconds?.toDouble()?.takeIf { it >= 60.0 } ?: return false
        val audioDuration = estimatedAudioSeconds?.takeIf { it > 0.0 } ?: return false
        return audioDuration <= 95.0 &&
            audioDuration < pageDuration * 0.65 &&
            pageDuration - audioDuration >= 20.0
    }
}

internal object Mp3DurationEstimator {
    private val mpeg1Layer3Bitrates = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
    )
    private val mpeg2Layer3Bitrates = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0,
    )

    fun estimateSeconds(sample: ByteArray, totalBytes: Long?): Double? {
        val size = totalBytes?.takeIf { it > 0 } ?: return null
        val scanStart = id3PayloadEnd(sample) ?: 0
        for (index in scanStart until (sample.size - 3).coerceAtLeast(scanStart)) {
            val first = sample[index].toInt() and 0xff
            val second = sample[index + 1].toInt() and 0xff
            val third = sample[index + 2].toInt() and 0xff
            if (first != 0xff || second and 0xe0 != 0xe0) continue
            val version = second shr 3 and 0x03
            val layer = second shr 1 and 0x03
            val bitrateIndex = third shr 4 and 0x0f
            val sampleRateIndex = third shr 2 and 0x03
            if (version == 1 || layer != 1 || bitrateIndex == 0 ||
                bitrateIndex == 15 || sampleRateIndex == 3
            ) {
                continue
            }
            val bitrateKbps = if (version == 3) {
                mpeg1Layer3Bitrates[bitrateIndex]
            } else {
                mpeg2Layer3Bitrates[bitrateIndex]
            }
            if (bitrateKbps <= 0) continue
            return size * 8.0 / (bitrateKbps * 1_000.0)
        }
        return null
    }

    private fun id3PayloadEnd(sample: ByteArray): Int? {
        if (sample.size < 10 || sample[0] != 'I'.code.toByte() ||
            sample[1] != 'D'.code.toByte() || sample[2] != '3'.code.toByte()
        ) {
            return null
        }
        val sizeBytes = (6..9).map { sample[it].toInt() and 0xff }
        if (sizeBytes.any { it and 0x80 != 0 }) return null
        val payloadSize = sizeBytes.fold(0) { value, byte -> (value shl 7) or byte }
        val footerBytes = if (sample[5].toInt() and 0x10 != 0) 10 else 0
        return (10 + payloadSize + footerBytes).coerceAtMost(sample.size)
    }
}

private fun java.io.InputStream.readAtMost(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(maxBytes)
    val buffer = ByteArray(8 * 1024)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count == 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}
