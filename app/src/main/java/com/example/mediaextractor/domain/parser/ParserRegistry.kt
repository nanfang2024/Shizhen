package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.DownloadStrategy
import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
import com.example.mediaextractor.util.DiagnosticLogger

class ParserRegistry(
    private val parsers: List<MediaParser>,
) {
    suspend fun parse(url: String): Result<ParsedMedia> {
        val available = parsers.filter { it.canHandle(url) }
        DiagnosticLogger.info(
            category = "PARSER_REGISTRY",
            event = "candidates_selected",
            details = mapOf(
                "url" to url,
                "parsers" to available.joinToString { it::class.java.simpleName },
            ),
        )
        if (available.isEmpty()) {
            DiagnosticLogger.warning(
                category = "PARSER_REGISTRY",
                event = "no_parser_available",
                details = mapOf("url" to url),
            )
            return Result.failure(
                MediaParseException(
                    MediaParseException.Reason.UNSUPPORTED,
                    ParserMessages.NO_MEDIA,
                ),
            )
        }

        var lastFailure: Throwable? = null
        var accessRestrictedFailure: MediaParseException? = null
        for ((index, parser) in available.withIndex()) {
            if (accessRestrictedFailure != null && parser is GenericMediaParser) {
                DiagnosticLogger.warning(
                    category = "PARSER_REGISTRY",
                    event = "generic_fallback_skipped_after_access_restriction",
                    details = mapOf("parser" to parser::class.java.simpleName),
                )
                continue
            }
            val parserName = parser::class.java.simpleName
            DiagnosticLogger.info(
                category = "PARSER_REGISTRY",
                event = "parser_attempt",
                details = mapOf("parser" to parserName),
            )
            val result = parser.parse(url)
            if (result.isSuccess) {
                val enriched = result.map(AudioDownloadOptionEnricher::addAudioOption)
                val parsed = enriched.getOrNull()
                if (accessRestrictedFailure != null && parsed != null &&
                    ParserFallbackResultPolicy.isMetadataOnly(parsed)
                ) {
                    DiagnosticLogger.warning(
                        category = "PARSER_REGISTRY",
                        event = "metadata_only_fallback_rejected_after_access_restriction",
                        details = mapOf(
                            "parser" to parserName,
                            "platform" to parsed.platform,
                            "types" to parsed.items.joinToString { it.type.name },
                        ),
                    )
                    lastFailure = accessRestrictedFailure
                    continue
                }
                DiagnosticLogger.info(
                    category = "PARSER_REGISTRY",
                    event = "parser_succeeded",
                    details = mapOf(
                        "parser" to parserName,
                        "items" to enriched.getOrNull()?.items?.size,
                        "audioOptionAdded" to (
                            enriched.getOrNull()?.items?.size != result.getOrNull()?.items?.size
                        ),
                    ),
                )
                return enriched
            }

            val failure = result.exceptionOrNull()
            DiagnosticLogger.warning(
                category = "PARSER_REGISTRY",
                event = "parser_failed",
                details = mapOf("parser" to parserName),
                failure = failure,
            )
            if (failure is MediaParseException &&
                failure.reason == MediaParseException.Reason.NO_CLEAN_SOURCE
            ) {
                DiagnosticLogger.warning(
                    category = "PARSER_REGISTRY",
                    event = "fallback_stopped",
                    details = mapOf("reason" to failure.reason.name),
                )
                return result
            }
            if (failure is MediaParseException &&
                failure.reason == MediaParseException.Reason.ACCESS_RESTRICTED
            ) {
                // Prefer the most recent platform-specific explanation. For example, yt-dlp may
                // report a generic login restriction before the official page parser identifies
                // the more useful "preview only" condition.
                accessRestrictedFailure = failure
                val hasSpecificFallback = available.drop(index + 1).any { it !is GenericMediaParser }
                if (hasSpecificFallback) {
                    DiagnosticLogger.info(
                        category = "PARSER_REGISTRY",
                        event = "specific_fallback_continued_after_access_restriction",
                        details = mapOf("failedParser" to parserName),
                    )
                    lastFailure = failure
                    continue
                }
                DiagnosticLogger.warning(
                    category = "PARSER_REGISTRY",
                    event = "fallback_stopped",
                    details = mapOf("reason" to failure.reason.name),
                )
                return Result.failure(accessRestrictedFailure)
            }
            lastFailure = failure
        }

        val finalFailure = accessRestrictedFailure ?: lastFailure ?: MediaParseException(
                MediaParseException.Reason.NO_MEDIA,
                ParserMessages.NO_MEDIA,
            )
        DiagnosticLogger.error(
            category = "PARSER_REGISTRY",
            event = "all_parsers_failed",
            failure = finalFailure,
        )
        return Result.failure(finalFailure)
    }
}

internal object ParserFallbackResultPolicy {
    fun isMetadataOnly(parsed: ParsedMedia): Boolean =
        parsed.items.isNotEmpty() && parsed.items.all { it.type == MediaType.COVER }
}

internal object AudioDownloadOptionEnricher {
    private const val BEST_AUDIO_SELECTOR = "bestaudio[ext=m4a]/bestaudio/best"

    fun addAudioOption(parsed: ParsedMedia): ParsedMedia {
        if (parsed.items.any { it.type == MediaType.AUDIO }) return parsed
        val video = parsed.items.asSequence()
            .filter { it.type == MediaType.VIDEO && it.hasAudio == true }
            .maxWithOrNull(
                compareBy<MediaItem> { if (it.isRecommended) 1 else 0 }
                    .thenBy { (it.width ?: 0).toLong() * (it.height ?: 0).toLong() },
            ) ?: return parsed
        val strategy = when (video.downloadStrategy) {
            DownloadStrategy.DIRECT, DownloadStrategy.DIRECT_AUDIO -> DownloadStrategy.DIRECT_AUDIO
            DownloadStrategy.YT_DLP, DownloadStrategy.YT_DLP_AUDIO -> DownloadStrategy.YT_DLP_AUDIO
        }
        val audio = MediaItem(
            id = "${video.id}-audio",
            type = MediaType.AUDIO,
            mediaUrl = video.mediaUrl,
            format = "m4a",
            width = null,
            height = null,
            fileSize = null,
            qualityLabel = if (strategy == DownloadStrategy.YT_DLP_AUDIO) {
                "最佳公开音轨 · 输出 M4A"
            } else {
                "从公开视频本地提取 · 输出 M4A"
            },
            previewUrl = video.previewUrl,
            downloadStrategy = strategy,
            downloadSourceUrl = video.downloadSourceUrl,
            formatSelector = BEST_AUDIO_SELECTOR.takeIf {
                strategy == DownloadStrategy.YT_DLP_AUDIO
            },
            isRecommended = false,
            hasAudio = true,
            codecSummary = "AAC / M4A",
            watermarkNote = "只保存音轨，不保存视频画面。",
            sourceWatermark = SourceWatermark.UNKNOWN,
        )
        return parsed.copy(items = parsed.items + audio)
    }
}
