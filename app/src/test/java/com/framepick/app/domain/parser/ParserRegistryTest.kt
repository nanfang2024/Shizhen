package com.framepick.app.domain.parser

import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserRegistryTest {
    @Test
    fun accessRestrictionCanFallBackToAnotherSpecificParser() = runBlocking {
        var fallbackCalls = 0
        val restricted = FakeParser(
            Result.failure(
                MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    ParserMessages.ACCESS_RESTRICTED,
                ),
            ),
        )
        val publicPageFallback = FakeParser(Result.success(parsed())) { fallbackCalls++ }

        val result = ParserRegistry(listOf(restricted, publicPageFallback))
            .parse("https://music.example/song/1")

        assertTrue(result.isSuccess)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun accessRestrictionNeverFallsThroughToGenericPageScanning() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val restricted = FakeParser(
                Result.failure(
                    MediaParseException(
                        MediaParseException.Reason.ACCESS_RESTRICTED,
                        ParserMessages.ACCESS_RESTRICTED,
                    ),
                ),
            )
            val noMedia = FakeParser(
                Result.failure(
                    MediaParseException(MediaParseException.Reason.NO_MEDIA, ParserMessages.NO_MEDIA),
                ),
            )
            val generic = GenericMediaParser(OkHttpClient())

            val failure = ParserRegistry(listOf(restricted, noMedia, generic))
                .parse(server.url("/song").toString())
                .exceptionOrNull()

            assertTrue(failure is MediaParseException)
            assertEquals(
                MediaParseException.Reason.ACCESS_RESTRICTED,
                (failure as MediaParseException).reason,
            )
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingCleanSourceStillStopsAllFallbacks() = runBlocking {
        var fallbackCalls = 0
        val noCleanSource = FakeParser(
            Result.failure(
                MediaParseException(
                    MediaParseException.Reason.NO_CLEAN_SOURCE,
                    ParserMessages.NO_CLEAN_SOURCE,
                ),
            ),
        )
        val fallback = FakeParser(Result.success(parsed())) { fallbackCalls++ }

        val failure = ParserRegistry(listOf(noCleanSource, fallback))
            .parse("https://media.example/post/1")
            .exceptionOrNull()

        assertTrue(failure is MediaParseException)
        assertEquals(MediaParseException.Reason.NO_CLEAN_SOURCE, (failure as MediaParseException).reason)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun laterSpecificRestrictionKeepsPreviewOnlyExplanation() = runBlocking {
        val genericRestriction = FakeParser(
            Result.failure(
                MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    ParserMessages.ACCESS_RESTRICTED,
                ),
            ),
        )
        val previewOnly = FakeParser(
            Result.failure(
                MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    ParserMessages.PREVIEW_ONLY,
                ),
            ),
        )

        val failure = ParserRegistry(listOf(genericRestriction, previewOnly))
            .parse("https://music.example/song/preview")
            .exceptionOrNull()

        assertEquals(ParserMessages.PREVIEW_ONLY, failure?.message)
    }

    @Test
    fun coverOnlyMetadataDoesNotHideVideoAccessRestriction() = runBlocking {
        val restricted = FakeParser(
            Result.failure(
                MediaParseException(
                    MediaParseException.Reason.ACCESS_RESTRICTED,
                    "YouTube 当前要求登录验证。",
                ),
            ),
        )
        val coverOnly = FakeParser(
            Result.success(
                parsed().copy(
                    platform = "YouTube",
                    items = listOf(
                        MediaItem(
                            id = "cover",
                            type = MediaType.COVER,
                            mediaUrl = "https://i.ytimg.com/cover.jpg",
                            format = "jpg",
                            width = 480,
                            height = 360,
                            fileSize = null,
                            qualityLabel = "公开视频封面 · 视频流暂不可用",
                        ),
                    ),
                ),
            ),
        )

        val failure = ParserRegistry(listOf(restricted, coverOnly))
            .parse("https://youtu.be/example")
            .exceptionOrNull()

        assertTrue(failure is MediaParseException)
        assertEquals("YouTube 当前要求登录验证。", failure?.message)
    }

    private class FakeParser(
        private val result: Result<ParsedMedia>,
        private val onParse: () -> Unit = {},
    ) : MediaParser {
        override fun canHandle(url: String): Boolean = true

        override suspend fun parse(url: String): Result<ParsedMedia> {
            onParse()
            return result
        }
    }

    private companion object {
        fun parsed() = ParsedMedia(
            sourceUrl = "https://music.example/song/1",
            platform = "测试音乐",
            title = "测试歌曲",
            author = "测试歌手",
            thumbnailUrl = null,
            items = listOf(
                MediaItem(
                    id = "audio-1",
                    type = MediaType.AUDIO,
                    mediaUrl = "https://audio.example/song.mp3",
                    format = "mp3",
                    width = null,
                    height = null,
                    fileSize = 123L,
                    qualityLabel = "公开音频",
                ),
            ),
        )
    }
}
