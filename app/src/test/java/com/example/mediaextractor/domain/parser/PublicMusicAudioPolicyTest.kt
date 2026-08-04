package com.example.mediaextractor.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class PublicMusicAudioPolicyTest {
    @Test
    fun keepsPreviewOnlyCandidateWhenNoFullAudioExists() {
        val selection = PublicMusicAudioPolicy.select(
            listOf(
                candidate("preview.mp3", MusicAudioCompleteness.PREVIEW),
            ),
        )

        assertEquals(1, selection.downloadable.size)
        assertEquals(
            MusicAudioCompleteness.PREVIEW,
            selection.downloadable.single().completeness,
        )
        assertEquals(1, selection.previewCount)
    }

    @Test
    fun previewFlagAllowsOnlyExplicitlyFullCandidates() {
        val selection = PublicMusicAudioPolicy.select(
            listOf(
                candidate("full.mp3", MusicAudioCompleteness.FULL),
                candidate("unknown.m4a", MusicAudioCompleteness.UNKNOWN),
                candidate("preview.mp3", MusicAudioCompleteness.PREVIEW),
            ),
        )

        assertEquals(
            listOf("https://audio.example/full.mp3"),
            selection.downloadable.map { it.url },
        )
        assertEquals(1, selection.previewCount)
    }

    @Test
    fun duplicatePreviewUrlsCountOnce() {
        val preview = candidate("preview.mp3", MusicAudioCompleteness.PREVIEW)

        val selection = PublicMusicAudioPolicy.select(listOf(preview, preview))

        assertEquals(1, selection.previewCount)
        assertEquals(1, selection.downloadable.size)
    }

    @Test
    fun explicitPreviewWinsOverUnknownCandidateWhenNoFullAudioExists() {
        val selection = PublicMusicAudioPolicy.select(
            listOf(
                candidate("unknown.m4a", MusicAudioCompleteness.UNKNOWN),
                candidate("preview.mp3", MusicAudioCompleteness.PREVIEW),
            ),
        )

        assertEquals(
            listOf("https://audio.example/preview.mp3"),
            selection.downloadable.map { it.url },
        )
    }

    @Test
    fun keepsUnknownCandidateWhenPageHasNoPreviewFlag() {
        val selection = PublicMusicAudioPolicy.select(
            listOf(candidate("unknown.m4a", MusicAudioCompleteness.UNKNOWN)),
        )

        assertEquals(1, selection.downloadable.size)
        assertEquals(0, selection.previewCount)
    }

    private fun candidate(name: String, completeness: MusicAudioCompleteness) =
        PublicMusicAudioCandidate(
            url = "https://audio.example/$name",
            completeness = completeness,
        )
}
