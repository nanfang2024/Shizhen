package com.example.mediaextractor.domain.converter

object FfmpegCommandBuilder {
    fun videoToGif(
        inputPath: String,
        outputPath: String,
        startSeconds: Double,
        endSeconds: Double,
        fps: Int,
        width: Int?,
        loop: Boolean,
    ): List<String> {
        val scale = width?.let { ",scale=$it:-1:flags=lanczos" }.orEmpty()
        val filter = "[0:v]fps=$fps$scale,split[s0][s1];" +
            "[s0]palettegen=stats_mode=diff[p];[s1][p]paletteuse=dither=sierra2_4a[out]"
        return listOf(
            "-ss", startSeconds.toString(),
            "-i", inputPath,
            "-t", (endSeconds - startSeconds).toString(),
            "-filter_complex", filter,
            "-map", "[out]",
            "-loop", if (loop) "0" else "-1",
            outputPath,
        )
    }

    fun videoToGifFallback(
        inputPath: String,
        outputPath: String,
        startSeconds: Double,
        endSeconds: Double,
        fps: Int,
        width: Int?,
        loop: Boolean,
    ): List<String> {
        val scale = width?.let { ",scale=$it:-1:flags=lanczos" }.orEmpty()
        return listOf(
            "-ss", startSeconds.toString(),
            "-i", inputPath,
            "-t", (endSeconds - startSeconds).toString(),
            "-vf", "fps=$fps$scale",
            "-loop", if (loop) "0" else "-1",
            outputPath,
        )
    }

    fun gifToMp4(inputPath: String, outputPath: String): List<String> = listOf(
        "-i", inputPath,
        "-an",
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-crf", "18",
        "-pix_fmt", "yuv420p",
        "-vsync", "vfr",
        "-movflags", "+faststart",
        outputPath,
    )

    fun gifToMp4Fallback(inputPath: String, outputPath: String): List<String> = listOf(
        "-i", inputPath,
        "-an",
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-c:v", "mpeg4",
        "-q:v", "2",
        "-pix_fmt", "yuv420p",
        "-vsync", "vfr",
        "-movflags", "+faststart",
        outputPath,
    )

    fun extractFrames(
        inputPath: String,
        outputPattern: String,
        intervalSeconds: Double,
    ): List<String> = listOf(
        "-i", inputPath,
        "-vf", "fps=1/$intervalSeconds",
        "-start_number", "1",
        outputPattern,
    )
}
