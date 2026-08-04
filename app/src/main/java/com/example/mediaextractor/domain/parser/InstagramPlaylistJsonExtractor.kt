package com.example.mediaextractor.domain.parser

import com.example.mediaextractor.domain.model.DownloadStrategy
import com.example.mediaextractor.domain.model.MediaItem
import com.example.mediaextractor.domain.model.MediaType
import com.example.mediaextractor.domain.model.ParsedMedia
import com.example.mediaextractor.domain.model.SourceWatermark
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale

/** Keeps usable Instagram carousel children when another child has no video formats. */
internal object InstagramPlaylistJsonExtractor {
    private val objectMapper = ObjectMapper()

    fun extract(sourceUrl: String, json: String): ParsedMedia? {
        val root = runCatching { objectMapper.readTree(json.trim()) }.getOrNull() ?: return null
        val entries = root.path("entries").takeIf(JsonNode::isArray) ?: return null
        val extractedItems = entries.mapIndexedNotNull { index, entry ->
            videoItem(entry, index, sourceUrl)
        }
        if (extractedItems.isEmpty()) return null
        val items = extractedItems.mapIndexed { index, item ->
            item.copy(isRecommended = index == 0)
        }

        val firstThumbnail = entries.asSequence()
            .mapNotNull(::thumbnailOf)
            .firstOrNull()
        return ParsedMedia(
            sourceUrl = sourceUrl,
            platform = "Instagram",
            title = root.textOrNull("title"),
            author = root.textOrNull("uploader") ?: root.textOrNull("channel"),
            thumbnailUrl = root.textOrNull("thumbnail") ?: firstThumbnail,
            items = items,
        )
    }

    private fun videoItem(entry: JsonNode, index: Int, sourceUrl: String): MediaItem? {
        if (entry.isNull || entry.isMissingNode) return null
        val formats = entry.path("formats").takeIf(JsonNode::isArray)
            ?.mapNotNull(::formatCandidate)
            .orEmpty()
        val selected = formats.maxWithOrNull(
            compareBy<FormatCandidate> { if (it.hasAudio) 1 else 0 }
                .thenBy { it.height ?: 0 }
                .thenBy { it.width ?: 0 }
                .thenBy { it.bitrate ?: 0.0 },
        ) ?: return null
        val entryId = entry.textOrNull("id") ?: "item-${index + 1}"
        return MediaItem(
            id = "instagram-$entryId-${selected.formatId.orEmpty()}",
            type = MediaType.VIDEO,
            mediaUrl = selected.url,
            format = selected.extension ?: "mp4",
            width = selected.width,
            height = selected.height,
            fileSize = selected.fileSize,
            qualityLabel = buildList {
                add("轮播第 ${index + 1} 项")
                selected.height?.let { add("${it}p") }
                add(if (selected.hasAudio) "视频+音频" else "仅视频")
            }.joinToString(" · "),
            previewUrl = thumbnailOf(entry) ?: selected.url,
            // A carousel child's webpage_url points back to the parent playlist, so a second
            // yt-dlp download would fail again on the broken siblings. Download the selected,
            // signed public CDN resource directly while it is valid.
            downloadStrategy = DownloadStrategy.DIRECT,
            downloadSourceUrl = sourceUrl,
            formatSelector = null,
            isRecommended = false,
            hasAudio = selected.hasAudio,
            codecSummary = listOfNotNull(selected.videoCodec, selected.audioCodec)
                .filterNot { it == "none" }
                .joinToString(" + ")
                .ifBlank { null },
            watermarkNote = "来自 Instagram 匿名公开响应；拾帧不修改画面内容。",
            sourceWatermark = SourceWatermark.UNKNOWN,
        )
    }

    private fun formatCandidate(node: JsonNode): FormatCandidate? {
        val url = node.textOrNull("url")?.takeIf(::isHttpUrl) ?: return null
        val videoCodec = node.textOrNull("vcodec")
        if (videoCodec == null || videoCodec == "none") return null
        val extension = node.textOrNull("ext")?.lowercase(Locale.ROOT)
        if (extension != null && extension !in setOf("mp4", "m4v", "mov")) return null
        return FormatCandidate(
            url = url,
            formatId = node.textOrNull("format_id"),
            extension = extension,
            width = node.positiveIntOrNull("width"),
            height = node.positiveIntOrNull("height"),
            fileSize = node.positiveLongOrNull("filesize")
                ?: node.positiveLongOrNull("filesize_approx"),
            bitrate = node.path("tbr").takeIf(JsonNode::isNumber)?.asDouble(),
            videoCodec = videoCodec,
            audioCodec = node.textOrNull("acodec"),
        )
    }

    private fun thumbnailOf(node: JsonNode): String? = node.textOrNull("thumbnail")
        ?.takeIf(::isHttpUrl)
        ?: node.path("thumbnails").takeIf(JsonNode::isArray)
            ?.asSequence()
            ?.mapNotNull { it.textOrNull("url")?.takeIf(::isHttpUrl) }
            ?.lastOrNull()

    private fun JsonNode.textOrNull(field: String): String? = path(field)
        .takeIf(JsonNode::isTextual)
        ?.asText()
        ?.trim()
        ?.ifBlank { null }

    private fun JsonNode.positiveIntOrNull(field: String): Int? = path(field)
        .takeIf(JsonNode::isNumber)
        ?.asInt()
        ?.takeIf { it > 0 }

    private fun JsonNode.positiveLongOrNull(field: String): Long? = path(field)
        .takeIf(JsonNode::isNumber)
        ?.asLong()
        ?.takeIf { it > 0L }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("https://") || value.startsWith("http://")

    private data class FormatCandidate(
        val url: String,
        val formatId: String?,
        val extension: String?,
        val width: Int?,
        val height: Int?,
        val fileSize: Long?,
        val bitrate: Double?,
        val videoCodec: String?,
        val audioCodec: String?,
    ) {
        val hasAudio: Boolean = audioCodec != null && audioCodec != "none"
    }
}
