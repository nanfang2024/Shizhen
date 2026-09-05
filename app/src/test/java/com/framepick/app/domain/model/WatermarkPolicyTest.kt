package com.framepick.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkPolicyTest {
    @Test
    fun ranksMixedWatermarkLevelsInPolicyOrder() {
        val ranked = WatermarkPolicy.ranked(
            listOf(
                item("watermarked", SourceWatermark.WATERMARKED, 1920, 1080),
                item("unknown", SourceWatermark.UNKNOWN, 720, 1280),
                item("original", SourceWatermark.PUBLIC_ORIGINAL, 540, 960),
                item("clean", SourceWatermark.PUBLIC_CLEAN, 1080, 1920),
            ),
        )

        assertEquals(
            listOf("original", "clean", "unknown", "watermarked"),
            ranked.map { it.id },
        )
    }

    @Test
    fun ranksSameLevelByResolutionDescending() {
        val ranked = WatermarkPolicy.ranked(
            listOf(
                item("small", SourceWatermark.PUBLIC_CLEAN, 480, 854),
                item("large", SourceWatermark.PUBLIC_CLEAN, 1080, 1920),
                item("medium", SourceWatermark.PUBLIC_CLEAN, 720, 1280),
            ),
        )

        assertEquals(listOf("large", "medium", "small"), ranked.map { it.id })
    }

    @Test
    fun keepsInsertionOrderForSameLevelAndSameResolution() {
        val ranked = WatermarkPolicy.ranked(
            listOf(
                item("first", SourceWatermark.UNKNOWN, 720, 1280),
                item("second", SourceWatermark.UNKNOWN, 720, 1280),
                item("third", SourceWatermark.UNKNOWN, 720, 1280),
            ),
        )

        assertEquals(listOf("first", "second", "third"), ranked.map { it.id })
    }

    @Test
    fun handlesEmptyAndSingleElementLists() {
        assertTrue(WatermarkPolicy.ranked(emptyList()).isEmpty())
        assertEquals(
            listOf("only"),
            WatermarkPolicy.ranked(listOf(item("only", SourceWatermark.UNKNOWN, null, null)))
                .map { it.id },
        )
    }

    @Test
    fun keepsGalleryOrderForImageSequencesWithinSameLevel() {
        val ranked = WatermarkPolicy.ranked(
            listOf(
                item("note-image-1", SourceWatermark.PUBLIC_ORIGINAL, 1080, 1440, type = MediaType.IMAGE),
                item("note-image-2", SourceWatermark.PUBLIC_ORIGINAL, 1200, 1600, type = MediaType.IMAGE),
            ),
        )

        assertEquals(listOf("note-image-1", "note-image-2"), ranked.map { it.id })
    }

    @Test
    fun placesCoverAfterWatermarkedVideo() {
        val ranked = WatermarkPolicy.ranked(
            listOf(
                item("cover", SourceWatermark.UNKNOWN, 720, 1280, type = MediaType.COVER),
                item("watermarked-video", SourceWatermark.WATERMARKED, 1080, 1920),
            ),
        )

        assertEquals(listOf("watermarked-video", "cover"), ranked.map { it.id })
    }

    @Test
    fun resetsRecommendationToFirstNonCoverItem() {
        val result = WatermarkPolicy.withRecommendation(
            listOf(
                item("cover", SourceWatermark.UNKNOWN, 1080, 1920, type = MediaType.COVER),
                item("clean-video", SourceWatermark.PUBLIC_CLEAN, 1080, 1920),
                item("original-video", SourceWatermark.PUBLIC_ORIGINAL, 720, 1280),
            ),
        )

        assertEquals(listOf("original-video", "clean-video", "cover"), result.map { it.id })
        assertFalse(result.first { it.id == "cover" }.isRecommended)
        assertTrue(result.first { it.id == "original-video" }.isRecommended)
        assertFalse(result.first { it.id == "clean-video" }.isRecommended)
    }

    @Test
    fun recommendsFirstItemWhenAllItemsAreCovers() {
        val result = WatermarkPolicy.withRecommendation(
            listOf(
                item("cover-1", SourceWatermark.UNKNOWN, 480, 854, type = MediaType.COVER),
                item("cover-2", SourceWatermark.UNKNOWN, 1080, 1920, type = MediaType.COVER),
            ),
        )

        assertTrue(result.first { it.id == "cover-2" }.isRecommended)
        assertFalse(result.first { it.id == "cover-1" }.isRecommended)
    }

    private fun item(
        id: String,
        watermark: SourceWatermark,
        width: Int?,
        height: Int?,
        type: MediaType = MediaType.VIDEO,
    ): MediaItem = MediaItem(
        id = id,
        type = type,
        mediaUrl = "https://media.example/$id.mp4",
        format = "mp4",
        width = width,
        height = height,
        fileSize = null,
        qualityLabel = null,
        sourceWatermark = watermark,
    )
}
