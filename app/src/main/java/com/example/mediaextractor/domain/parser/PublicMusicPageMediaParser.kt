package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.util.DiagnosticLogger
import com.example.mediaextractor.util.PublicUrlNormalizer
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Safe fallback for official music pages that directly expose playable audio in their HTML.
 * It never calls third-party parsing services or synthesizes private API signatures.
 */
class PublicMusicPageMediaParser(
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = MusicPlatformSupport.definitionForUrl(url) != null

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info("PUBLIC_MUSIC_PARSE", "parse_started", mapOf("url" to url))
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
                category = "PUBLIC_MUSIC_PARSE",
                event = "parse_succeeded",
                details = mapOf(
                    "platform" to parsed.platform,
                    "items" to parsed.items.size,
                    "audio" to parsed.items.count { it.type == MediaType.AUDIO },
                ),
            )
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "PUBLIC_MUSIC_PARSE",
                event = "parse_failed",
                failure = failure,
            )
        }
    }

    private fun parseBlocking(sourceUrl: String): ParsedMedia {
        val initialDefinition = MusicPlatformSupport.definitionForUrl(sourceUrl)
            ?: throw noMedia()
        val response = client.newCall(
            Request.Builder()
                .url(PublicUrlNormalizer.upgradeKnownHttp(sourceUrl))
                .header("User-Agent", BROWSER_USER_AGENT)
                .get()
                .build(),
        ).execute()
        val page = response.use {
            DiagnosticLogger.info(
                category = "PUBLIC_MUSIC_PARSE",
                event = "page_response",
                details = mapOf(
                    "status" to it.code,
                    "contentType" to it.body?.contentType(),
                    "finalUrl" to it.request.url,
                ),
            )
            if (it.code == 401 || it.code == 403) throw accessRestricted()
            if (!it.isSuccessful) throw IOException("音乐公开页面返回状态 ${it.code}。")
            val finalUrl = it.request.url.toString()
            val definition = MusicPlatformSupport.definitionForUrl(finalUrl) ?: initialDefinition
            val html = it.body?.string().orEmpty()
            val structured = when (definition.displayName) {
                "QQ音乐" -> QqMusicPageExtractor.extract(finalUrl, html)
                "酷狗音乐" -> KugouMusicPageExtractor.extract(html)
                else -> null
            }
            val generic = PublicMusicHtmlExtractor.extract(finalUrl, html)
            val pageData = structured?.merge(generic) ?: generic
            DiagnosticLogger.info(
                category = "PUBLIC_MUSIC_PARSE",
                event = "page_data_extracted",
                details = mapOf(
                    "platform" to definition.displayName,
                    "structured" to (structured != null),
                    "hasTitle" to !pageData.title.isNullOrBlank(),
                    "hasCover" to !pageData.coverUrl.isNullOrBlank(),
                    "audioCandidates" to pageData.audioCandidates.size,
                ),
            )
            PageResponse(
                definition = definition,
                data = pageData,
            )
        }

        val selection = PublicMusicAudioPolicy.select(
            page.data.audioCandidates.filter { page.definition.allowsAudioUrl(it.url) },
        )
        DiagnosticLogger.info(
            category = "PUBLIC_MUSIC_PARSE",
            event = "audio_completeness_filtered",
            details = mapOf(
                "downloadable" to selection.downloadable.size,
                "full" to selection.downloadable.count {
                    it.completeness == MusicAudioCompleteness.FULL
                },
                "unknown" to selection.downloadable.count {
                    it.completeness == MusicAudioCompleteness.UNKNOWN
                },
                "previewAvailable" to selection.previewCount,
            ),
        )
        val resolvedAudio = selection.downloadable.asSequence()
            .distinctBy(PublicMusicAudioCandidate::url)
            .take(MAX_AUDIO_CANDIDATES)
            .mapNotNull { candidate -> probeAudio(page.definition, candidate) }
            .take(MAX_AUDIO_RESULTS)
            .toList()
        if (resolvedAudio.isEmpty()) {
            throw MediaParseException(
                MediaParseException.Reason.ACCESS_RESTRICTED,
                "该平台公开页面没有提供可下载音频，可能需要登录、客户端或存在版权限制，拾帧不支持提取。",
            )
        }

        val audioItems = resolvedAudio.toMediaItems(page.definition.displayName)
        val coverItem = page.data.coverUrl?.let(PublicUrlNormalizer::upgradeKnownHttp)?.let { cover ->
            MediaItem(
                id = stableId("${page.definition.displayName}:cover:$cover"),
                type = MediaType.COVER,
                mediaUrl = cover,
                format = cover.substringBefore('?').substringAfterLast('.', "jpg")
                    .lowercase(Locale.ROOT),
                width = null,
                height = null,
                fileSize = null,
                qualityLabel = "歌曲/专辑封面",
                previewUrl = cover,
            )
        }
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = page.definition.displayName,
            title = page.data.title,
            author = page.data.author,
            thumbnailUrl = page.data.coverUrl?.let(PublicUrlNormalizer::upgradeKnownHttp),
            items = audioItems + listOfNotNull(coverItem),
        )
    }

    private fun probeAudio(
        definition: MusicPlatformDefinition,
        candidate: PublicMusicAudioCandidate,
    ): ResolvedMusicAudio? = runCatching {
        val normalized = PublicUrlNormalizer.upgradeKnownHttp(candidate.url)
        client.newCall(
            Request.Builder()
                .url(normalized)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Range", "bytes=0-${AUDIO_SIGNATURE_BYTES - 1}")
                .get()
                .build(),
        ).execute().use { response ->
            val mime = response.body?.contentType()?.toString()
                ?.substringBefore(';')
                ?.lowercase(Locale.ROOT)
            val finalUrl = PublicUrlNormalizer.upgradeKnownHttp(response.request.url.toString())
            val sample = response.body?.byteStream()?.use {
                it.readAtMostBytes(AUDIO_SIGNATURE_BYTES)
            } ?: byteArrayOf()
            val payloadAccepted = PublicMusicAudioResponsePolicy.accepts(
                mime = mime,
                url = finalUrl,
                sample = sample,
            )
            DiagnosticLogger.info(
                category = "PUBLIC_MUSIC_PARSE",
                event = "audio_probe",
                details = mapOf(
                    "status" to response.code,
                    "contentType" to mime,
                    "finalUrl" to finalUrl,
                    "payloadAccepted" to payloadAccepted,
                ),
            )
            if (!response.isSuccessful || !payloadAccepted ||
                !definition.allowsAudioUrl(finalUrl)
            ) {
                return@use null
            }
            ResolvedMusicAudio(
                url = finalUrl,
                format = when (mime) {
                    "audio/mpeg" -> "mp3"
                    "audio/mp4", "audio/x-m4a" -> "m4a"
                    "audio/flac", "audio/x-flac" -> "flac"
                    "audio/aac" -> "aac"
                    "audio/ogg" -> "ogg"
                    "audio/wav", "audio/x-wav" -> "wav"
                    else -> candidate.url.substringBefore('?').substringAfterLast('.', "")
                        .lowercase(Locale.ROOT).takeIf(String::isNotBlank)
                },
                fileSize = response.header("Content-Range")
                    ?.substringAfterLast('/', "")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: response.body?.contentLength()?.takeIf { it > 1 },
                label = candidate.label,
                completeness = candidate.completeness,
            )
        }
    }.onFailure { failure ->
        DiagnosticLogger.warning(
            category = "PUBLIC_MUSIC_PARSE",
            event = "audio_probe_failed",
            details = mapOf("url" to candidate.url),
            failure = failure,
        )
    }.getOrNull()

    private fun accessRestricted() = MediaParseException(
        MediaParseException.Reason.ACCESS_RESTRICTED,
        ParserMessages.ACCESS_RESTRICTED,
    )

    private fun noMedia() = MediaParseException(
        MediaParseException.Reason.NO_MEDIA,
        ParserMessages.NO_MEDIA,
    )

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    private data class PageResponse(
        val definition: MusicPlatformDefinition,
        val data: PublicMusicPageData,
    )

    private companion object {
        const val MAX_AUDIO_CANDIDATES = 8
        const val MAX_AUDIO_RESULTS = 4
        const val AUDIO_SIGNATURE_BYTES = 16
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

internal object PublicMusicAudioResponsePolicy {
    private val genericBinaryMimes = setOf("", "application/octet-stream", "application/binary")

    fun accepts(mime: String?, url: String, sample: ByteArray): Boolean {
        val normalizedMime = mime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedMime.startsWith("audio/")) return true
        if (normalizedMime !in genericBinaryMimes) return false
        val extension = runCatching { URI(url).path }.getOrNull()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when (extension) {
            "mp3" -> sample.startsWithAscii("ID3") || sample.hasMpegAudioSync()
            "m4a", "mp4" -> sample.asciiAt(4, "ftyp")
            "aac" -> sample.hasMpegAudioSync()
            "flac" -> sample.startsWithAscii("fLaC")
            "ogg", "opus" -> sample.startsWithAscii("OggS")
            "wav" -> sample.startsWithAscii("RIFF") && sample.asciiAt(8, "WAVE")
            else -> false
        }
    }

    private fun ByteArray.hasMpegAudioSync(): Boolean =
        size >= 2 && (this[0].toInt() and 0xff) == 0xff &&
            (this[1].toInt() and 0xe0) == 0xe0

    private fun ByteArray.startsWithAscii(value: String): Boolean = asciiAt(0, value)

    private fun ByteArray.asciiAt(offset: Int, value: String): Boolean =
        offset >= 0 && size >= offset + value.length &&
            value.indices.all { index -> this[offset + index].toInt() == value[index].code }
}

private fun java.io.InputStream.readAtMostBytes(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(maxBytes)
    val buffer = ByteArray(maxBytes.coerceAtMost(8 * 1024))
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count <= 0) break
        output.write(buffer, 0, count)
        remaining -= count
    }
    return output.toByteArray()
}

internal data class ResolvedMusicAudio(
    val url: String,
    val format: String?,
    val fileSize: Long?,
    val label: String?,
    val completeness: MusicAudioCompleteness,
)

internal fun List<ResolvedMusicAudio>.toMediaItems(platform: String): List<MediaItem> =
    mapIndexed { index, audio ->
        val previewOnly = audio.completeness == MusicAudioCompleteness.PREVIEW
        MediaItem(
            id = UUID.nameUUIDFromBytes("$platform:${audio.url}".toByteArray()).toString(),
            type = MediaType.AUDIO,
            mediaUrl = audio.url,
            format = audio.format,
            width = null,
            height = null,
            fileSize = audio.fileSize,
            qualityLabel = if (previewOnly) {
                "（仅试听片段） · ${audio.label ?: "平台公开试听"}"
            } else {
                audio.label ?: "页面公开音频 · 完整性未验证"
            },
            previewUrl = audio.url,
            isRecommended = index == 0 && !previewOnly,
            hasAudio = true,
            watermarkNote = when (audio.completeness) {
                MusicAudioCompleteness.FULL ->
                    "平台公开状态标记为完整音频；受限歌曲不会尝试绕过登录或付费。"
                MusicAudioCompleteness.UNKNOWN ->
                    "平台页面未提供足够字段确认完整时长；下载前请核对资源说明。"
                MusicAudioCompleteness.PREVIEW ->
                    "仅保存平台公开提供的试听片段，不是完整歌曲；不会尝试获取需要登录、购买或客户端授权的完整音频。"
            },
            isPreviewOnly = previewOnly,
        )
    }

internal data class PublicMusicPageData(
    val title: String?,
    val author: String?,
    val coverUrl: String?,
    val audioCandidates: List<PublicMusicAudioCandidate>,
) {
    fun merge(fallback: PublicMusicPageData): PublicMusicPageData = copy(
        title = title ?: fallback.title,
        author = author ?: fallback.author,
        coverUrl = coverUrl ?: fallback.coverUrl,
        audioCandidates = (audioCandidates + fallback.audioCandidates)
            .distinctBy(PublicMusicAudioCandidate::url),
    )
}

internal enum class MusicAudioCompleteness {
    FULL,
    PREVIEW,
    UNKNOWN,
}

internal data class PublicMusicAudioCandidate(
    val url: String,
    val label: String? = null,
    val completeness: MusicAudioCompleteness = MusicAudioCompleteness.UNKNOWN,
)

internal data class PublicMusicAudioSelection(
    val downloadable: List<PublicMusicAudioCandidate>,
    val previewCount: Int,
)

internal object PublicMusicAudioPolicy {
    fun select(candidates: List<PublicMusicAudioCandidate>): PublicMusicAudioSelection {
        val distinct = candidates.distinctBy(PublicMusicAudioCandidate::url)
        val full = distinct.filter { it.completeness == MusicAudioCompleteness.FULL }
        val previews = distinct.filter { it.completeness == MusicAudioCompleteness.PREVIEW }
        val unknown = distinct.filter { it.completeness == MusicAudioCompleteness.UNKNOWN }
        return PublicMusicAudioSelection(
            downloadable = when {
                full.isNotEmpty() -> full
                previews.isNotEmpty() -> previews
                else -> unknown
            },
            previewCount = previews.size,
        )
    }
}

internal object QqMusicPageExtractor {
    private val mapper = ObjectMapper()
    private val stateAssignment = Regex(
        "window\\.__ssrFirstPageData__\\s*=(\"(?:\\\\.|[^\"\\\\])*\")",
    )

    fun extract(finalUrl: String, html: String): PublicMusicPageData? {
        val literal = stateAssignment.find(html)?.groupValues?.getOrNull(1) ?: return null
        val decoded = runCatching { mapper.readValue(literal, String::class.java) }.getOrNull()
            ?: return null
        val song = runCatching { mapper.readTree(decoded).path("song") }.getOrNull()
            ?.takeIf { it.isObject }
            ?: return null
        val title = sequenceOf("title", "name")
            .map { song.path(it).asText().trim() }
            .firstOrNull(String::isNotBlank)
        val author = song.path("singerName").asText().trim().ifBlank {
            song.path("singer").asSequence()
                .map { it.path("name").asText().trim() }
                .filter(String::isNotBlank)
                .joinToString(" / ")
        }.ifBlank { null }
        val cover = song.path("img").asText().trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(PublicUrlNormalizer::upgradeKnownHttp)
        val playUrl = song.path("playUrl").asText().trim()
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let(PublicUrlNormalizer::upgradeKnownHttp)
        val isAudition = song.path("pay").path("pay_play").asInt(0) != 0
        val mid = song.path("mid").asText().trim().ifBlank {
            MusicLinkCanonicalizer.qqSongMid(finalUrl).orEmpty()
        }
        return PublicMusicPageData(
            title = title,
            author = author,
            coverUrl = cover,
            audioCandidates = listOfNotNull(
                playUrl?.let {
                    PublicMusicAudioCandidate(
                        it,
                        if (isAudition) "QQ 音乐页面公开试听" else "QQ 音乐公开完整音频",
                        if (isAudition) {
                            MusicAudioCompleteness.PREVIEW
                        } else {
                            MusicAudioCompleteness.FULL
                        },
                    )
                },
            ),
        ).takeIf { mid.isNotBlank() || it.audioCandidates.isNotEmpty() }
    }
}

internal object KugouMusicPageExtractor {
    private val mapper = ObjectMapper()
    private val statePrefix = Regex("var\\s+phpParam\\s*=\\s*")

    fun extract(html: String): PublicMusicPageData? {
        val stateStart = statePrefix.find(html)?.range?.last?.plus(1) ?: return null
        val state = JavascriptObjectExtractor.extract(html, stateStart) ?: return null
        val root = runCatching { mapper.readTree(state) }.getOrNull() ?: return null
        val song = root.path("song_info").path("data").takeIf { it.isObject } ?: return null
        val title = sequenceOf("songName", "fileName")
            .map { song.path(it).asText().trim() }
            .firstOrNull(String::isNotBlank)
        val author = song.path("authors").asSequence()
            .map { it.path("author_name").asText().trim() }
            .filter(String::isNotBlank)
            .joinToString(" / ")
            .ifBlank {
                sequenceOf("author_name", "singerName")
                    .map { song.path(it).asText().trim() }
                    .firstOrNull(String::isNotBlank)
                    .orEmpty()
            }
            .ifBlank { null }
        val cover = sequenceOf(
            root.path("imgurl").asText(),
            song.path("album_img").asText(),
            song.path("imgUrl").asText(),
        ).map(String::trim)
            .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
            ?.replace("{size}", "400")
            ?.let(PublicUrlNormalizer::upgradeKnownHttp)
        val isAudition = song.path("audition_status").asInt(0) != 0 ||
            song.path("pay_type").asInt(0) != 0
        val bitrate = song.path("bitRate").asInt(0).takeIf { it > 0 }
        val label = buildList {
            add(if (isAudition) "酷狗页面公开试听" else "酷狗公开完整音频")
            bitrate?.let { add("$it kbps") }
        }.joinToString(" · ")
        val audioUrls = buildList {
            song.path("url").asText().trim().takeIf(::isHttpUrl)?.let(::add)
            song.path("backup_url").asSequence()
                .map { it.asText().trim() }
                .filter(::isHttpUrl)
                .forEach(::add)
        }.distinct()
        return PublicMusicPageData(
            title = title,
            author = author,
            coverUrl = cover,
            audioCandidates = audioUrls.map {
                PublicMusicAudioCandidate(
                    url = it,
                    label = label,
                    completeness = if (isAudition) {
                        MusicAudioCompleteness.PREVIEW
                    } else {
                        MusicAudioCompleteness.FULL
                    },
                )
            },
        ).takeIf { it.title != null || it.coverUrl != null || it.audioCandidates.isNotEmpty() }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")
}

/** Extracts one balanced JavaScript object without relying on Android-specific brace regex rules. */
internal object JavascriptObjectExtractor {
    fun extract(source: String, startIndex: Int): String? {
        var index = startIndex.coerceAtLeast(0)
        while (index < source.length && source[index].isWhitespace()) index++
        if (index >= source.length || source[index] != '{') return null
        val objectStart = index
        var depth = 0
        var quote: Char? = null
        var escaped = false
        while (index < source.length) {
            val character = source[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
            } else {
                when (character) {
                    '"', '\'' -> quote = character
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(objectStart, index + 1)
                        if (depth < 0) return null
                    }
                }
            }
            index++
        }
        return null
    }
}

internal object PublicMusicHtmlExtractor {
    private val audioKeyUrl = Regex(
        "(?i)(?:playUrl|play_url|audioUrl|audio_url|listenUrl|listen_url)\\s*[\"']?\\s*[:=]\\s*[\"']" +
            "(https?://[^\"'\\s<>]+)",
    )

    fun extract(finalUrl: String, html: String): PublicMusicPageData {
        val document = Jsoup.parse(html, finalUrl)
        val title = document.meta("meta[property=og:title]", "content")
            ?: document.meta("meta[name=twitter:title]", "content")
            ?: document.title().ifBlank { null }
        val author = document.meta("meta[property=music:musician]", "content")
            ?: document.meta("meta[name=author]", "content")
        val cover = document.meta("meta[property=og:image]", "content")
            ?: document.meta("meta[name=twitter:image]", "content")
        val candidates = buildList {
            listOf(
                "meta[property=og:audio]",
                "meta[property=og:audio:url]",
                "meta[property=og:audio:secure_url]",
                "meta[name=twitter:player:stream]",
            ).forEach { selector ->
                document.select(selector).forEach { element ->
                    element.absUrl("content").ifBlank { element.attr("content") }
                        .takeIf(String::isNotBlank)
                        ?.let { add(genericCandidate(it)) }
                }
            }
            document.select("audio[src], audio source[src]").forEach { element ->
                element.absUrl("src").takeIf(String::isNotBlank)
                    ?.let { add(genericCandidate(it)) }
            }
            val normalizedScripts = html.replace("\\u002F", "/").replace("\\/", "/")
            audioKeyUrl.findAll(normalizedScripts).forEach { match ->
                match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)
                    ?.let { add(genericCandidate(it)) }
            }
        }.distinctBy(PublicMusicAudioCandidate::url)
        return PublicMusicPageData(
            title = title,
            author = author,
            coverUrl = cover?.let { document.resolveUrl(it) },
            audioCandidates = candidates,
        )
    }

    private fun Document.meta(selector: String, attribute: String): String? =
        selectFirst(selector)?.attr(attribute)?.trim()?.ifBlank { null }

    private fun Document.resolveUrl(value: String): String =
        runCatching { URI(baseUri()).resolve(value).toString() }.getOrDefault(value)

    private fun genericCandidate(url: String): PublicMusicAudioCandidate {
        val lower = url.lowercase(Locale.ROOT)
        val isExplicitPreview = listOf("preview", "audition", "trial", "climax")
            .any(lower::contains)
        return PublicMusicAudioCandidate(
            url = url,
            label = if (isExplicitPreview) {
                "页面明确标记的试听片段"
            } else {
                "页面公开音频 · 完整性未验证"
            },
            completeness = if (isExplicitPreview) {
                MusicAudioCompleteness.PREVIEW
            } else {
                MusicAudioCompleteness.UNKNOWN
            },
        )
    }
}

internal data class MusicPlatformDefinition(
    val displayName: String,
    val pageDomains: Set<String>,
    val audioDomains: Set<String>,
) {
    fun allowsAudioUrl(url: String): Boolean = PublicUrlNormalizer.hostOf(url)?.let { host ->
        audioDomains.any { host == it || host.endsWith(".$it") }
    } == true
}

internal object MusicPlatformSupport {
    private val definitions = listOf(
        MusicPlatformDefinition(
            displayName = "QQ音乐",
            pageDomains = setOf("y.qq.com"),
            audioDomains = setOf("qq.com", "qqmusic.qq.com"),
        ),
        MusicPlatformDefinition(
            displayName = "酷狗音乐",
            pageDomains = setOf("kugou.com", "kugou.net"),
            audioDomains = setOf("kugou.com", "kugou.net", "kgimg.com", "kugoucdn.com"),
        ),
        MusicPlatformDefinition(
            displayName = "酷我音乐",
            pageDomains = setOf("kuwo.cn", "kuwo.com"),
            audioDomains = setOf("kuwo.cn", "kuwo.com"),
        ),
        MusicPlatformDefinition(
            displayName = "咪咕音乐",
            pageDomains = setOf("music.migu.cn", "m.music.migu.cn"),
            audioDomains = setOf("migu.cn", "miguvideo.com"),
        ),
    )

    fun definitionForUrl(url: String): MusicPlatformDefinition? =
        PublicUrlNormalizer.hostOf(url)?.let { host ->
            definitions.firstOrNull { definition ->
                definition.pageDomains.any { host == it || host.endsWith(".$it") }
            }
        }

    fun isMusicPlatform(name: String): Boolean = name in definitions.map { it.displayName } + "网易云音乐"
}

internal object MusicLinkCanonicalizer {
    fun canonicalize(url: String): String {
        val mid = qqSongMid(url) ?: return url
        return "https://y.qq.com/n/ryqq/songDetail/$mid"
    }

    fun qqSongMid(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.host != "y.qq.com" && !parsed.host.endsWith(".y.qq.com")) return null
        Regex("/songDetail/([A-Za-z0-9]+)").find(parsed.encodedPath)
            ?.groupValues?.getOrNull(1)?.let { return it }
        return sequenceOf("songmid", "songMid")
            .mapNotNull(parsed::queryParameter)
            .firstOrNull { it.matches(Regex("[A-Za-z0-9]+")) }
    }
}
