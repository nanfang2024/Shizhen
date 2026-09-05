package com.framepick.app.domain.model

/**
 * Unified watermark-source priority across structured parsers.
 *
 * Ranking: PUBLIC_ORIGINAL > PUBLIC_CLEAN > UNKNOWN > WATERMARKED. Within
 * the same level, VIDEO/AUDIO items (alternative qualities of the same
 * content) are ordered by resolution descending; IMAGE/GIF sequences (pages
 * of a gallery note) keep their insertion order. COVER items are
 * supplementary and always sort last, by resolution descending.
 */
object WatermarkPolicy {
    private val levelRank = mapOf(
        SourceWatermark.PUBLIC_ORIGINAL to 0,
        SourceWatermark.PUBLIC_CLEAN to 1,
        SourceWatermark.UNKNOWN to 2,
        SourceWatermark.WATERMARKED to 3,
    )

    fun ranked(items: List<MediaItem>): List<MediaItem> {
        val content = items
            .filter { it.type != MediaType.COVER }
            .sortedWith(
                compareBy<MediaItem> { levelRank.getValue(it.sourceWatermark) }
                    .thenByDescending { qualityTiebreak(it) },
            )
        val covers = items
            .filter { it.type == MediaType.COVER }
            .sortedByDescending { (it.width ?: 0) * (it.height ?: 0) }
        return content + covers
    }

    private fun qualityTiebreak(item: MediaItem): Long =
        if (item.type == MediaType.VIDEO || item.type == MediaType.AUDIO) {
            (item.width ?: 0).toLong() * (item.height ?: 0)
        } else {
            0L
        }

    /**
     * Ranks the items and resets the recommendation flag: the first non-cover
     * item becomes the recommended one (first item when everything is a
     * cover), all others lose the flag.
     */
    fun withRecommendation(items: List<MediaItem>): List<MediaItem> {
        val ranked = ranked(items)
        val recommendedIndex = ranked.indexOfFirst { it.type != MediaType.COVER }
            .takeIf { it >= 0 } ?: 0
        return ranked.mapIndexed { index, mediaItem ->
            if (index == recommendedIndex && !mediaItem.isRecommended) {
                mediaItem.copy(isRecommended = true)
            } else if (index != recommendedIndex && mediaItem.isRecommended) {
                mediaItem.copy(isRecommended = false)
            } else {
                mediaItem
            }
        }
    }
}
