package com.framepick.app.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLinkCanonicalizerTest {
    @Test
    fun keepsCanonicalQqSongPage() {
        assertEquals(
            "https://y.qq.com/n/ryqq/songDetail/004Ti8rT003TaZ",
            MusicLinkCanonicalizer.canonicalize(
                "https://y.qq.com/n/ryqq/songDetail/004Ti8rT003TaZ",
            ),
        )
    }

    @Test
    fun convertsMobileQqSharePageToCanonicalSongPage() {
        assertEquals(
            "https://y.qq.com/n/ryqq/songDetail/004Ti8rT003TaZ",
            MusicLinkCanonicalizer.canonicalize(
                "https://i2.y.qq.com/n3/other/pages/playsong/index.html?ADTAG=share&songmid=004Ti8rT003TaZ",
            ),
        )
    }
}
