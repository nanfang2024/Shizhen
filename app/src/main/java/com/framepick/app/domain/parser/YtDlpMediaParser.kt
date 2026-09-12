package com.framepick.app.domain.parser

import android.content.Context
import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.InternationalMediaUrlCanonicalizer
import com.framepick.app.util.InternationalPlatformFailurePolicy
import com.framepick.app.util.PlatformRecognizer
import com.framepick.app.util.PublicUrlNormalizer
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class YtDlpMediaParser(
    private val context: Context,
    private val client: OkHttpClient,
) : MediaParser {
    override fun canHandle(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT)
        SUPPORTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    override suspend fun parse(url: String): Result<ParsedMedia> = withContext(Dispatchers.IO) {
        DiagnosticLogger.info(
            category = "YT_DLP_PARSE",
            event = "parse_started",
            details = mapOf("url" to url),
        )
        val result = runCatching {
            YoutubeDL.getInstance().init(context.applicationContext)
            maybeUpdateExtractor()
            val extractorUrl = resolveForExtractor(url)
            val info = try {
                getInfoWithFallback(extractorUrl)
            } catch (failure: YoutubeDLException) {
                val carousel = if (
                    YtDlpInternationalFallbackPolicy.shouldParseInstagramPlaylist(
                        extractorUrl,
                        failure.message,
                    )
                ) {
                    parseInstagramPlaylist(extractorUrl)
                } else {
                    null
                }
                if (carousel != null) {
                    return@runCatching carousel.copy(sourceUrl = url)
                }
                throw failure
            }
            val formats = info.formats.orEmpty().filter { !it.url.isNullOrBlank() }
            val rawVideoFormats = formats.filter { it.vcodec != "none" }
            val audioFormats = formats.filter { it.vcodec == "none" && it.acodec != "none" }
            val selectableAudioFormats = AudioFormatSelector.select(audioFormats)
            val bestAudio = selectableAudioFormats.firstOrNull()
            // Short domains can represent more than one product (v.douyin.com also carries
            // Xigua shares), so platform identity must come from the expanded URL.
            val platform = PlatformRecognizer.recognize(extractorUrl)?.displayName
                ?: PlatformRecognizer.recognize(url)?.displayName
                ?: info.extractor.orEmpty()
            val musicMetadata = fetchQqMusicMetadata(platform, extractorUrl)
            val classifiedFormats = rawVideoFormats.map { format ->
                format to PublicSourceClassifier.classify(
                    platform = platform,
                    formatId = format.formatId,
                    formatNote = format.formatNote,
                    url = format.url,
                )
            }
            val originalFormats = classifiedFormats.filter { it.second == SourceWatermark.PUBLIC_ORIGINAL }
            val nonWatermarkedFormats = classifiedFormats.filter { it.second != SourceWatermark.WATERMARKED }
            DiagnosticLogger.info(
                category = "YT_DLP_PARSE",
                event = "format_inventory",
                details = mapOf(
                    "extractor" to info.extractor,
                    "total" to formats.size,
                    "video" to rawVideoFormats.size,
                    "audioOnly" to audioFormats.size,
                    "original" to originalFormats.size,
                    "unknownWatermark" to classifiedFormats.count { it.second == SourceWatermark.UNKNOWN },
                    "watermarked" to classifiedFormats.count { it.second == SourceWatermark.WATERMARKED },
                    "formats" to rawVideoFormats.take(MAX_DIAGNOSTIC_FORMATS).joinToString { it.diagnosticSummary() },
                    "watermarkClasses" to classifiedFormats.take(MAX_DIAGNOSTIC_FORMATS)
                        .joinToString { "${it.first.formatId.orEmpty()}:${it.second.name}" },
                ),
            )
            val usableFormats = if (PublicSourceClassifier.isWatermarkAware(platform)) {
                when {
                    originalFormats.isNotEmpty() -> originalFormats
                    nonWatermarkedFormats.isNotEmpty() -> nonWatermarkedFormats
                    rawVideoFormats.isNotEmpty() -> {
                        DiagnosticLogger.warning(
                            category = "YT_DLP_PARSE",
                            event = "all_video_formats_marked_watermarked",
                            details = mapOf("count" to rawVideoFormats.size),
                        )
                        throw MediaParseException(
                            MediaParseException.Reason.NO_CLEAN_SOURCE,
                            ParserMessages.NO_CLEAN_SOURCE,
                        )
                    }
                    else -> emptyList()
                }
            } else {
                classifiedFormats
            }
            val watermarkByFormat = usableFormats.associate { it.first to it.second }
            val videoFormats = usableFormats.map { it.first }
            val isTwitterAnimatedGif = PlatformMediaClassifier.isTwitterAnimatedGif(
                platform,
                videoFormats.map { it.url },
            )
            DiagnosticLogger.info(
                category = "YT_DLP_PARSE",
                event = "formats_selected",
                details = mapOf(
                    "platform" to platform,
                    "usableVideo" to videoFormats.size,
                    "bestAudio" to bestAudio?.diagnosticSummary(),
                    "selectableAudio" to selectableAudioFormats.size,
                    "twitterAnimatedGif" to isTwitterAnimatedGif,
                ),
            )

            val heights = videoFormats.mapNotNull { it.height.takeIf { height -> height > 0 } }
                .distinct()
                .sortedDescending()
                .take(MAX_QUALITY_OPTIONS)
            val items = if (MusicPlatformSupport.isMusicPlatform(platform)) {
                selectableAudioFormats.mapIndexed { index, audio ->
                    MediaItem(
                        id = "yt-${info.id.orEmpty()}-audio-${audio.formatId.orEmpty()}",
                        type = MediaType.AUDIO,
                        mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(audio.url.orEmpty()),
                        format = audio.ext?.lowercase(Locale.ROOT),
                        width = null,
                        height = null,
                        fileSize = formatSize(audio),
                        qualityLabel = audioQualityLabel(audio),
                        previewUrl = audio.url?.let(PublicUrlNormalizer::upgradeKnownHttp),
                        downloadStrategy = DownloadStrategy.YT_DLP,
                        downloadSourceUrl = extractorUrl,
                        formatSelector = exactAudioFormatSelector(audio),
                        isRecommended = index == 0,
                        hasAudio = true,
                        codecSummary = audio.acodec?.takeUnless { it == "none" },
                    )
                }.toMutableList()
            } else {
                mutableListOf()
            }
            items += heights.mapIndexed { index, height ->
                val candidates = videoFormats.filter { it.height == height }
                val preview = candidates
                    .filter { it.acodec != "none" }
                    .maxWithOrNull(compareBy<VideoFormat> { watermarkByFormat[it].sourcePriority() }
                        .thenBy { it.bitrateScore() })
                    ?: candidates.maxWithOrNull(compareBy<VideoFormat> { watermarkByFormat[it].sourcePriority() }
                        .thenBy { it.bitrateScore() })
                    ?: error("缺少视频格式")
                val selectedVideo = candidates.maxWithOrNull(
                    compareBy<VideoFormat> { watermarkByFormat[it].sourcePriority() }
                        .thenBy { it.bitrateScore() },
                ) ?: preview
                val audioAvailable = selectedVideo.acodec != "none" || bestAudio != null
                val watermarkStatus = watermarkByFormat[selectedVideo] ?: SourceWatermark.UNKNOWN
                MediaItem(
                    id = "yt-${info.id.orEmpty()}-$height-${selectedVideo.formatId.orEmpty()}",
                    type = if (isTwitterAnimatedGif) MediaType.GIF else MediaType.VIDEO,
                    mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(preview.url.orEmpty()),
                    format = if (isTwitterAnimatedGif) "gif" else "mp4",
                    width = selectedVideo.width.takeIf { it > 0 },
                    height = height,
                    fileSize = estimatedCombinedSize(selectedVideo, bestAudio),
                    qualityLabel = buildQualityLabel(
                        height,
                        audioAvailable,
                        watermarkStatus,
                        isTwitterAnimatedGif,
                    ),
                    previewUrl = preview.url?.let(PublicUrlNormalizer::upgradeKnownHttp),
                    downloadStrategy = DownloadStrategy.YT_DLP,
                    downloadSourceUrl = extractorUrl,
                    formatSelector = exactFormatSelector(selectedVideo, height),
                    isRecommended = index == 0,
                    hasAudio = audioAvailable,
                    codecSummary = codecSummary(selectedVideo, bestAudio),
                    watermarkNote = watermarkNote(platform, watermarkStatus),
                    sourceWatermark = watermarkStatus,
                    previewAsVideo = isTwitterAnimatedGif,
                )
            }

            if (items.isEmpty() && videoFormats.isNotEmpty()) {
                val preview = videoFormats.maxByOrNull { it.bitrateScore() }!!
                val watermarkStatus = watermarkByFormat[preview] ?: SourceWatermark.UNKNOWN
                items += MediaItem(
                    id = "yt-${info.id.orEmpty()}-best",
                    type = if (isTwitterAnimatedGif) MediaType.GIF else MediaType.VIDEO,
                    mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(preview.url.orEmpty()),
                    format = if (isTwitterAnimatedGif) "gif" else "mp4",
                    width = preview.width.takeIf { it > 0 },
                    height = preview.height.takeIf { it > 0 },
                    fileSize = estimatedCombinedSize(preview, bestAudio),
                    qualityLabel = buildQualityLabel(
                        preview.height.takeIf { it > 0 },
                        preview.acodec != "none" || bestAudio != null,
                        watermarkStatus,
                        isTwitterAnimatedGif,
                    ),
                    previewUrl = preview.url?.let(PublicUrlNormalizer::upgradeKnownHttp),
                    downloadStrategy = DownloadStrategy.YT_DLP,
                    downloadSourceUrl = extractorUrl,
                    formatSelector = exactFormatSelector(preview, null),
                    isRecommended = true,
                    hasAudio = preview.acodec != "none" || bestAudio != null,
                    codecSummary = codecSummary(preview, bestAudio),
                    watermarkNote = watermarkNote(platform, watermarkStatus),
                    sourceWatermark = watermarkStatus,
                    previewAsVideo = isTwitterAnimatedGif,
                )
            }

            val fallbackUrl = info.url
            val fallbackIsAnimatedGif = PlatformMediaClassifier.isTwitterAnimatedGif(
                platform,
                listOf(fallbackUrl),
            )
            val fallbackUrlExtension = fallbackUrl.orEmpty().substringBefore('?')
                .substringAfterLast('.', "").lowercase(Locale.ROOT)
            val fallbackLooksLikeVideo = info.height > 0 ||
                info.ext.orEmpty().lowercase(Locale.ROOT) in VIDEO_EXTENSIONS ||
                fallbackUrlExtension in VIDEO_EXTENSIONS || fallbackIsAnimatedGif
            if (items.isEmpty() && !fallbackUrl.isNullOrBlank() && fallbackLooksLikeVideo) {
                items += MediaItem(
                    id = info.formatId ?: info.id ?: fallbackUrl.hashCode().toString(),
                    type = if (fallbackIsAnimatedGif) MediaType.GIF else MediaType.VIDEO,
                    mediaUrl = PublicUrlNormalizer.upgradeKnownHttp(fallbackUrl),
                    format = if (fallbackIsAnimatedGif) "gif" else info.ext,
                    width = info.width.takeIf { it > 0 },
                    height = info.height.takeIf { it > 0 },
                    fileSize = info.fileSize.takeIf { it > 0 }
                        ?: info.fileSizeApproximate.takeIf { it > 0 },
                    qualityLabel = info.resolution ?: "最佳公开质量",
                    previewUrl = PublicUrlNormalizer.upgradeKnownHttp(fallbackUrl),
                    downloadStrategy = DownloadStrategy.YT_DLP,
                    downloadSourceUrl = extractorUrl,
                    formatSelector = BEST_VIDEO_SELECTOR,
                    isRecommended = true,
                    hasAudio = null,
                    watermarkNote = watermarkNote(platform, SourceWatermark.UNKNOWN),
                    sourceWatermark = SourceWatermark.UNKNOWN,
                    previewAsVideo = fallbackIsAnimatedGif,
                )
            }
            val instagramImageUrl = info.thumbnail
                ?.let(PublicUrlNormalizer::upgradeKnownHttp)
                ?.takeIf { platform == "Instagram" && items.isEmpty() }
            if (instagramImageUrl != null) {
                val imageFormat = instagramImageUrl.substringBefore('?')
                    .substringAfterLast('.', "jpg")
                    .lowercase(Locale.ROOT)
                    .takeIf { it in IMAGE_EXTENSIONS }
                    ?: "jpg"
                items += MediaItem(
                    id = "yt-${info.id.orEmpty()}-image",
                    type = MediaType.IMAGE,
                    mediaUrl = instagramImageUrl,
                    format = imageFormat,
                    width = info.width.takeIf { it > 0 },
                    height = info.height.takeIf { it > 0 },
                    fileSize = null,
                    qualityLabel = "公开图片 · 平台返回的最佳可用尺寸",
                    previewUrl = instagramImageUrl,
                    downloadStrategy = DownloadStrategy.DIRECT,
                    downloadSourceUrl = extractorUrl,
                    isRecommended = true,
                    sourceWatermark = SourceWatermark.UNKNOWN,
                    watermarkNote = "使用 Instagram 匿名公开响应返回的图片，不修改画面内容。",
                )
            }
            val musicCoverUrl = (musicMetadata?.coverUrl ?: info.thumbnail)
                ?.let(PublicUrlNormalizer::upgradeKnownHttp)
            if (items.any { it.type == MediaType.AUDIO } && !musicCoverUrl.isNullOrBlank()) {
                items += MediaItem(
                    id = "yt-${info.id.orEmpty()}-cover",
                    type = MediaType.COVER,
                    mediaUrl = musicCoverUrl,
                    format = musicCoverUrl.substringBefore('?').substringAfterLast('.', "jpg")
                        .lowercase(Locale.ROOT),
                    width = null,
                    height = null,
                    fileSize = null,
                    qualityLabel = "歌曲/专辑封面",
                    previewUrl = musicCoverUrl,
                )
            }
            if (items.isEmpty()) throw MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                ParserMessages.NO_MEDIA,
            )

            ParsedMedia(
                sourceUrl = url,
                platform = platform,
                title = musicMetadata?.title ?: info.title,
                author = musicMetadata?.author ?: info.uploader ?: musicAuthor(info.description),
                thumbnailUrl = musicCoverUrl,
                items = items,
            ).also { parsed ->
                DiagnosticLogger.info(
                    category = "YT_DLP_PARSE",
                    event = "parse_succeeded",
                    details = mapOf(
                        "items" to parsed.items.size,
                        "types" to parsed.items.joinToString { it.type.name },
                        "qualities" to parsed.items.joinToString { it.qualityLabel.orEmpty() },
                    ),
                )
            }
        }.recoverCatching { failure ->
            throw when (failure) {
                is MediaParseException -> failure
                is YoutubeDLException -> mapYoutubeDlFailure(failure, url)
                is InterruptedException -> MediaParseException(
                    MediaParseException.Reason.NETWORK,
                    "解析已取消。",
                    failure,
                )
                else -> MediaParseException(
                    MediaParseException.Reason.INVALID_RESPONSE,
                    ParserMessages.NO_MEDIA,
                    failure,
                )
            }
        }
        result.onFailure { failure ->
            DiagnosticLogger.error(
                category = "YT_DLP_PARSE",
                event = "parse_failed",
                failure = failure,
                details = mapOf("url" to url),
            )
        }
        result
    }

    private fun mapYoutubeDlFailure(
        failure: YoutubeDLException,
        url: String,
    ): MediaParseException {
        if (YtDlpBotCheckPolicy.isBotCheck(url, failure.message)) {
            return MediaParseException(
                MediaParseException.Reason.ACCESS_RESTRICTED,
                "YouTube 风控要求验证设备，已自动尝试多种客户端仍被拒绝；请稍后或更换网络后重试。",
                failure,
            )
        }
        val text = failure.message.orEmpty().lowercase(Locale.ROOT)
        val internationalMessage = InternationalPlatformFailurePolicy
            .accessRestrictionMessage(url, text)
        return if (internationalMessage != null ||
            listOf("login", "sign in", "cookie", "private", "age-restricted")
                .any(text::contains)
        ) {
            MediaParseException(
                MediaParseException.Reason.ACCESS_RESTRICTED,
                internationalMessage ?: ParserMessages.ACCESS_RESTRICTED,
                failure,
            )
        } else {
            MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                ParserMessages.NO_MEDIA,
                failure,
            )
        }
    }

    private fun getInfoWithFallback(url: String): VideoInfo {
        val primaryRequest = createInfoRequest(url)
        DiagnosticLogger.info("YT_DLP_PARSE", "primary_info_request")
        return try {
            YoutubeDL.getInstance().getInfo(primaryRequest).also {
                DiagnosticLogger.info("YT_DLP_PARSE", "primary_info_succeeded")
            }
        } catch (primaryFailure: YoutubeDLException) {
            if (YtDlpBotCheckPolicy.isBotCheck(url, primaryFailure.message)) {
                return getInfoWithBotCheckRetries(url, primaryFailure)
            }
            if (YtDlpInternationalFallbackPolicy.shouldRetryInstagramAsImage(url, primaryFailure.message)) {
                DiagnosticLogger.info(
                    category = "YT_DLP_PARSE",
                    event = "instagram_image_info_retry",
                    details = mapOf("reason" to "video_formats_absent"),
                )
                val imageRequest = createInfoRequest(url)
                    .addOption("--ignore-no-formats-error")
                return YoutubeDL.getInstance().getInfo(imageRequest).also {
                    DiagnosticLogger.info(
                        category = "YT_DLP_PARSE",
                        event = "instagram_image_info_succeeded",
                        details = mapOf(
                            "thumbnailAvailable" to !it.thumbnail.isNullOrBlank(),
                            "thumbnailCount" to it.thumbnails.orEmpty().size,
                        ),
                    )
                }
            }
            if (!isTwitterUrl(url)) throw primaryFailure
            DiagnosticLogger.warning(
                category = "YT_DLP_PARSE",
                event = "primary_info_failed_trying_syndication",
                failure = primaryFailure,
            )
            val syndicationRequest = createInfoRequest(url)
                .addOption("--extractor-args", "twitter:api=syndication")
            YoutubeDL.getInstance().getInfo(syndicationRequest).also {
                DiagnosticLogger.info("YT_DLP_PARSE", "syndication_info_succeeded")
            }
        }
    }

    /** YouTube bot-check workaround: retry non-web clients, then self-heal via update. */
    private fun getInfoWithBotCheckRetries(
        url: String,
        primaryFailure: YoutubeDLException,
    ): VideoInfo {
        try {
            return attemptBotCheckClients(url, primaryFailure)
        } catch (firstRound: YoutubeDLException) {
            DiagnosticLogger.warning(
                category = "YT_DLP_PARSE",
                event = "bot_check_all_clients_failed_forcing_update",
                failure = firstRound,
            )
            maybeUpdateExtractor(force = true)
            return attemptBotCheckClients(url, firstRound)
        }
    }

    private fun attemptBotCheckClients(
        url: String,
        primaryFailure: YoutubeDLException,
    ): VideoInfo {
        var lastFailure = primaryFailure
        for (client in YtDlpBotCheckPolicy.playerClientRetries()) {
            try {
                DiagnosticLogger.warning(
                    category = "YT_DLP_PARSE",
                    event = "bot_check_retry",
                    failure = lastFailure,
                    details = mapOf("playerClient" to client),
                )
                return YoutubeDL.getInstance()
                    .getInfo(
                        createInfoRequest(url)
                            .addOption("--extractor-args", "youtube:player_client=$client"),
                    )
                    .also {
                        DiagnosticLogger.info(
                            category = "YT_DLP_PARSE",
                            event = "bot_check_retry_succeeded",
                            details = mapOf("playerClient" to client),
                        )
                    }
            } catch (failure: YoutubeDLException) {
                lastFailure = failure
            }
        }
        throw lastFailure
    }

    private fun parseInstagramPlaylist(url: String): ParsedMedia? {
        DiagnosticLogger.info(
            category = "YT_DLP_PARSE",
            event = "instagram_playlist_json_retry",
            details = mapOf("reason" to "one_or_more_children_without_formats"),
        )
        val request = createInfoRequest(url)
            .addOption("--dump-single-json")
            .addOption("--ignore-no-formats-error")
        return runCatching {
            val response = YoutubeDL.getInstance().execute(request)
            DiagnosticLogger.info(
                category = "YT_DLP_PARSE",
                event = "instagram_playlist_json_response",
                details = mapOf(
                    "exitCode" to response.exitCode,
                    "outputLength" to response.out.length,
                    "errorLength" to response.err.length,
                ),
            )
            InstagramPlaylistJsonExtractor.extract(url, response.out)
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "YT_DLP_PARSE",
                event = "instagram_playlist_json_failed",
                failure = failure,
            )
        }.getOrNull()
    }

    private fun resolveForExtractor(url: String): String {
        val normalized = InternationalMediaUrlCanonicalizer.canonicalize(
            PublicUrlNormalizer.upgradeKnownHttp(url),
        )
        val needsExpansion = PublicUrlNormalizer.isShortLink(normalized) ||
            InternationalMediaUrlCanonicalizer.needsNetworkExpansion(normalized)
        val resolved = if (!needsExpansion) {
            normalized
        } else runCatching {
            client.newCall(
                Request.Builder()
                    .url(normalized)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException(
                    "短链展开返回状态 ${response.code}",
                )
                response.request.url.newBuilder()
                    .fragment(null)
                    .build()
                    .toString()
                    .also { resolved ->
                        DiagnosticLogger.info(
                            category = "YT_DLP_PARSE",
                            event = "short_url_resolved_with_android_network",
                        details = mapOf("original" to url, "resolved" to resolved),
                        )
                    }
            }
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "YT_DLP_PARSE",
                event = "short_url_resolution_failed_using_original",
                failure = failure,
                details = mapOf("url" to url),
            )
        }.getOrDefault(normalized)
        return MusicLinkCanonicalizer.canonicalize(
            InternationalMediaUrlCanonicalizer.canonicalize(resolved),
        )
    }

    private fun fetchQqMusicMetadata(platform: String, url: String): PublicMusicPageData? {
        if (platform != "QQ音乐") return null
        return runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                QqMusicPageExtractor.extract(
                    response.request.url.toString(),
                    response.body?.string().orEmpty(),
                )
            }
        }.onFailure { failure ->
            DiagnosticLogger.warning(
                category = "YT_DLP_PARSE",
                event = "qq_metadata_enrichment_failed",
                failure = failure,
            )
        }.getOrNull()
    }

    private fun createInfoRequest(url: String): YoutubeDLRequest = YoutubeDLRequest(url)
        .addOption("--no-playlist")
        .addOption("--no-warnings")
        .addOption("--no-check-certificates")
        .addOption("--skip-download")
        .addOption("--extractor-retries", 2)
        .addOption("--socket-timeout", 20)

    private fun isTwitterUrl(url: String): Boolean = runCatching {
        val host = java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT)
        host == "x.com" || host.endsWith(".x.com") ||
            host == "twitter.com" || host.endsWith(".twitter.com") || host == "t.co"
    }.getOrDefault(false)

    private fun formatSelector(height: Int): String =
        "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/" +
            "best[height<=$height][ext=mp4]/" +
            "bestvideo[height<=$height]+bestaudio/best[height<=$height]"

    private fun exactFormatSelector(format: VideoFormat, height: Int?): String {
        val formatId = format.formatId?.takeIf { it.matches(SAFE_FORMAT_ID) }
            ?: return height?.let(::formatSelector) ?: BEST_VIDEO_SELECTOR
        return if (format.acodec != "none") {
            formatId
        } else {
            "$formatId+bestaudio/$formatId"
        }
    }

    private fun exactAudioFormatSelector(format: VideoFormat): String =
        format.formatId?.takeIf { it.matches(SAFE_FORMAT_ID) } ?: "bestaudio"

    private fun audioQualityLabel(format: VideoFormat): String = buildList {
        val bitrate = format.abr.takeIf { it > 0 } ?: format.tbr.takeIf { it > 0 }
        bitrate?.let { add("$it kbps") }
        format.ext?.uppercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
        format.formatNote?.takeIf(String::isNotBlank)?.let(::add)
        if (isEmpty()) add("最佳公开音质")
    }.distinct().joinToString(" · ")

    private fun musicAuthor(description: String?): String? = description?.let {
        Regex("(?m)^\\[ar:([^]]+)]").find(it)?.groupValues?.getOrNull(1)?.trim()
    }?.ifBlank { null }

    private fun buildQualityLabel(
        height: Int?,
        audioAvailable: Boolean,
        watermark: SourceWatermark,
        animatedGif: Boolean,
    ): String = buildList {
        add(height?.let { "${it}p" } ?: "最佳公开质量")
        add(if (animatedGif) "动图 · 下载后转 GIF" else if (audioAvailable) "视频+音频" else "仅视频")
        if (watermark == SourceWatermark.PUBLIC_ORIGINAL) add("公开原始源")
        if (watermark == SourceWatermark.PUBLIC_CLEAN) add("公开无水印源")
    }.joinToString(" · ")

    private fun watermarkNote(platform: String, status: SourceWatermark): String? =
        if (!PublicSourceClassifier.isWatermarkAware(platform)) {
            null
        } else {
            when (status) {
                SourceWatermark.PUBLIC_ORIGINAL ->
                    "已选择提取器标记的公开原始播放源，不使用平台标记的带水印下载源。"
                SourceWatermark.PUBLIC_CLEAN ->
                    "已选择平台公开的无水印播放源；它可能是平台转码版本，拾帧不做画面后处理。"
                SourceWatermark.WATERMARKED -> ParserMessages.NO_CLEAN_SOURCE
                SourceWatermark.UNKNOWN ->
                    "平台未返回可验证的水印标记，无法保证该公开源没有平台水印。"
            }
        }

    private fun SourceWatermark?.sourcePriority(): Int = when (this) {
        SourceWatermark.PUBLIC_ORIGINAL -> 3
        SourceWatermark.PUBLIC_CLEAN -> 2
        SourceWatermark.UNKNOWN -> 1
        SourceWatermark.WATERMARKED, null -> 0
    }

    private fun maybeUpdateExtractor(force: Boolean = false) {
        val preferences = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSuccess = preferences.getLong(KEY_LAST_UPDATE_ATTEMPT, 0L)
        val lastFailure = preferences.getLong(KEY_LAST_UPDATE_FAILURE, 0L)
        if (!force && YtDlpUpdateThrottle.shouldSkip(now, lastSuccess, lastFailure)) {
            DiagnosticLogger.info(
                category = "YT_DLP_UPDATE",
                event = "update_skipped_recent_attempt",
                details = mapOf(
                    "ageMinutes" to ((now - maxOf(lastSuccess, lastFailure)) / 60_000L),
                ),
            )
            return
        }
        DiagnosticLogger.info("YT_DLP_UPDATE", "stable_update_started")
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.STABLE,
            )
        }.onSuccess { result ->
            preferences.edit()
                .putLong(KEY_LAST_UPDATE_ATTEMPT, now)
                .putLong(KEY_LAST_UPDATE_FAILURE, 0L)
                .apply()
            DiagnosticLogger.info(
                category = "YT_DLP_UPDATE",
                event = "stable_update_finished",
                details = mapOf("result" to result),
            )
        }.onFailure { failure ->
            preferences.edit().putLong(KEY_LAST_UPDATE_FAILURE, now).apply()
            DiagnosticLogger.warning(
                category = "YT_DLP_UPDATE",
                event = "stable_update_failed_using_bundled_version",
                failure = failure,
            )
        }
    }

    private fun VideoFormat.bitrateScore(): Double =
        tbr.toDouble().takeIf { it > 0 } ?: 0.0

    private fun VideoFormat.diagnosticSummary(): String =
        "id=${formatId.orEmpty()},ext=${ext.orEmpty()},${width}x$height,v=${vcodec.orEmpty()}," +
            "a=${acodec.orEmpty()},tbr=$tbr"

    private fun formatSize(format: VideoFormat?): Long? = format?.let {
        it.fileSize.takeIf { size -> size > 0 }
            ?: it.fileSizeApproximate.takeIf { size -> size > 0 }
    }

    private fun estimatedCombinedSize(video: VideoFormat, audio: VideoFormat?): Long? {
        val videoSize = formatSize(video) ?: return null
        if (video.acodec != "none") return videoSize
        val audioSize = formatSize(audio) ?: return null
        return videoSize + audioSize
    }

    private fun codecSummary(video: VideoFormat, audio: VideoFormat?): String? {
        val videoCodec = video.vcodec?.takeUnless { it == "none" }
        val audioCodec = if (video.acodec != "none") video.acodec else audio?.acodec
        return listOfNotNull(videoCodec, audioCodec?.takeUnless { it == "none" })
            .joinToString(" + ")
            .ifBlank { null }
    }

    private companion object {
        const val MAX_QUALITY_OPTIONS = 6
        const val MAX_DIAGNOSTIC_FORMATS = 30
        const val BEST_VIDEO_SELECTOR =
            "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo+bestaudio/best"
        val SAFE_FORMAT_ID = Regex("[A-Za-z0-9._-]+")
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v", "m3u8")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "avif")
        const val UPDATE_PREFERENCES = "yt_dlp_updates"
        const val KEY_LAST_UPDATE_ATTEMPT = "last_stable_update_attempt"
        const val KEY_LAST_UPDATE_FAILURE = "last_stable_update_failure"
        val SUPPORTED_HOSTS = setOf(
            "youtube.com", "youtube-nocookie.com", "youtu.be", "instagram.com", "instagr.am",
            "facebook.com", "fb.watch", "fb.com", "vimeo.com", "tiktok.com", "douyin.com",
            "bilibili.com", "b23.tv", "weibo.com", "weibo.cn", "t.cn", "xiaohongshu.com",
            "xhslink.com", "xhslink.cn", "rednote.com", "twitter.com", "x.com", "t.co",
            "ixigua.com", "xigua.com", "acfun.cn", "youku.com", "tudou.com", "iqiyi.com",
            "mgtv.com", "v.qq.com", "haokan.baidu.com", "toutiao.com", "pearvideo.com",
            "miaopai.com", "meipai.com", "sohu.com", "163.com", "zhihu.com",
            "y.qq.com",
        )
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    }
}

/** Detects YouTube's anti-bot interstitial and offers non-web player clients. */
internal object YtDlpBotCheckPolicy {
    private val markers = listOf(
        "confirm you're not a bot",
        "confirm you are not a bot",
        "sign in to confirm",
        "not a bot",
    )

    fun isBotCheck(url: String, message: String?): Boolean {
        val platform = PlatformRecognizer.recognize(url)?.displayName
        if (platform != "YouTube") return false
        val text = message.orEmpty().lowercase(Locale.ROOT)
        return markers.any(text::contains)
    }

    fun playerClientRetries(): List<String> = listOf(
        "tv", "mweb", "web_safari", "android_vr", "ios", "android",
    )
}

/**
 * Update throttle: a successful refresh keeps a 24-hour window, but a failed
 * one only backs off for an hour — otherwise a single flaky update pins the
 * bundled (outdated) yt-dlp for a whole day, which is how devices ended up on
 * an extractor that always hits YouTube bot checks.
 */
internal object YtDlpUpdateThrottle {
    const val SUCCESS_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    const val FAILURE_RETRY_INTERVAL_MS = 60L * 60L * 1_000L

    fun shouldSkip(now: Long, lastSuccess: Long, lastFailure: Long): Boolean {
        val last = maxOf(lastSuccess, lastFailure)
        if (last <= 0L) return false
        val interval =
            if (lastFailure > lastSuccess) FAILURE_RETRY_INTERVAL_MS else SUCCESS_INTERVAL_MS
        return now - last < interval
    }
}

internal object YtDlpInternationalFallbackPolicy {
    fun shouldRetryInstagramAsImage(url: String, message: String?): Boolean {
        val platform = PlatformRecognizer.recognize(url)?.displayName
        return platform == "Instagram" &&
            message.orEmpty().contains("there is no video in this post", ignoreCase = true)
    }

    fun shouldParseInstagramPlaylist(url: String, message: String?): Boolean {
        val platform = PlatformRecognizer.recognize(url)?.displayName
        return platform == "Instagram" &&
            message.orEmpty().contains("no video formats found", ignoreCase = true)
    }
}

internal object AudioFormatSelector {
    private const val MAX_OPTIONS = 4
    private val supportedExtensions = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")

    fun select(formats: List<VideoFormat>): List<VideoFormat> = formats.asSequence()
        .filter { format ->
            !format.url.isNullOrBlank() && format.vcodec == "none" &&
                format.acodec != "none" &&
                format.ext?.lowercase(Locale.ROOT) in supportedExtensions
        }
        .sortedWith(
            compareByDescending<VideoFormat> { it.abr.takeIf { rate -> rate > 0 } ?: it.tbr }
                .thenByDescending { it.preference }
                .thenByDescending {
                    it.fileSize.takeIf { size -> size > 0 }
                        ?: it.fileSizeApproximate.takeIf { size -> size > 0 }
                        ?: 0L
                },
        )
        .distinctBy { format ->
            listOf(format.formatId, format.ext, format.abr, format.tbr).joinToString(":")
        }
        .take(MAX_OPTIONS)
        .toList()
}
