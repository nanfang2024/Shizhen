package com.example.mediaextractor.domain.converter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegCommandBuilderTest {
    @Test
    fun videoToGifUsesPaletteAndRequestedTiming() {
        val command = FfmpegCommandBuilder.videoToGif(
            inputPath = "input.mp4",
            outputPath = "output.gif",
            startSeconds = 1.0,
            endSeconds = 4.0,
            fps = 12,
            width = 480,
            loop = true,
        )

        assertTrue(command.containsAll(listOf("-ss", "1.0", "-t", "3.0", "-loop", "0")))
        assertTrue(command[command.indexOf("-filter_complex") + 1].contains("palettegen"))
        assertTrue(command[command.indexOf("-filter_complex") + 1].contains("scale=480:-1"))
        assertTrue(command.containsAll(listOf("-map", "[out]")))
    }

    @Test
    fun gifToMp4UsesCompatiblePixelFormat() {
        val command = FfmpegCommandBuilder.gifToMp4("input.gif", "output.mp4")

        assertEquals("yuv420p", command[command.indexOf("-pix_fmt") + 1])
        assertEquals("libx264", command[command.indexOf("-c:v") + 1])
        assertEquals("vfr", command[command.indexOf("-vsync") + 1])
    }

    @Test
    fun frameExtractionUsesRequestedInterval() {
        val command = FfmpegCommandBuilder.extractFrames("in.mp4", "frame_%05d.png", 2.5)

        assertEquals("fps=1/2.5", command[command.indexOf("-vf") + 1])
    }

    @Test
    fun compatibilityProfilesAvoidPrimaryFiltersAndEncoder() {
        val gifFallback = FfmpegCommandBuilder.videoToGifFallback(
            "input.mp4", "output.gif", 0.0, 3.0, 10, 480, true,
        )
        val mp4Fallback = FfmpegCommandBuilder.gifToMp4Fallback("input.gif", "output.mp4")

        assertTrue(gifFallback.contains("-vf"))
        assertTrue(gifFallback.none { it.contains("palettegen") })
        assertEquals("mpeg4", mp4Fallback[mp4Fallback.indexOf("-c:v") + 1])
        assertEquals("yuv420p", mp4Fallback[mp4Fallback.indexOf("-pix_fmt") + 1])
    }
}
