package com.framepick.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePublicAudioResolverTest {
    @Test
    fun recognizesOnlyOfficialOuterSongEndpoint() {
        assertTrue(
            NeteasePublicAudioResolver.isOuterUrl(
                NeteasePublicAudioResolver.outerUrl("1365898499"),
            ),
        )
        assertFalse(
            NeteasePublicAudioResolver.isOuterUrl(
                "https://example.com/song/media/outer/url?id=1365898499.mp3",
            ),
        )
    }
}
