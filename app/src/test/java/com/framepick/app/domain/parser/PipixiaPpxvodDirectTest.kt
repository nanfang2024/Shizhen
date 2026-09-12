package com.framepick.app.domain.parser

import com.framepick.app.domain.model.SourceWatermark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures mirror the pipix-website reference implementation: the h5 item page
 * embeds ppxvod direct links percent-encoded (or JSON-escaped), where
 * `dr=6&dy_q` marks the watermark-free source and `lr=superb` the watermarked
 * fallback.
 */
class PipixiaPpxvodDirectTest {
    @Test
    fun itemIdExtractorReadsNumericShareLinks() {
        assertEquals(
            "696123456789",
            PipixItemIdExtractor.fromUrl("https://h5.pipix.com/item/696123456789/?app_id=1319"),
        )
        assertEquals(
            "696123456789",
            PipixItemIdExtractor.fromUrl(
                "https://h5.pipix.com/ppx/item/696123456789?app_id=1319&app=super",
            ),
        )
        assertEquals("696123456789", PipixItemIdExtractor.fromUrl("696123456789"))
    }

    @Test
    fun itemIdExtractorReadsShortCodesAndRejectsForeignLinks() {
        assertEquals("JyVd8m", PipixItemIdExtractor.fromUrl("https://h5.pipix.com/s/JyVd8m/"))
        assertNull(PipixItemIdExtractor.fromUrl("https://example.com/video/123"))
        assertNull(PipixItemIdExtractor.fromUrl(""))
    }

    @Test
    fun scannerFindsPercentEncodedCleanLink() {
        val encoded = "https%3A%2F%2Fv26-cdn.ppxvod.com%2Fvideo%2Ftos%2Fcn%2F1%2F" +
            "%3Fa%3D1319%26dr%3D6%26dy_q%3D1700000000"
        val candidates = PpxvodUrlScanner.scan("""<script>{"u":"$encoded"}</script>""")

        assertEquals(1, candidates.size)
        assertEquals(PpxvodUrlScanner.Tier.CLEAN, candidates[0].tier)
        assertEquals(
            "https://v26-cdn.ppxvod.com/video/tos/cn/1/",
            candidates[0].baseUrl,
        )
        assertTrue("dr=6" in candidates[0].url && "dy_q" in candidates[0].url)
    }

    @Test
    fun scannerUnescapesJsonSlashes() {
        val candidates = PpxvodUrlScanner.scan(
            """{"list":["https:\/\/v9-cdn.ppxvod.com\/media\/1.mp4?lr=superb&ch=add"]}""",
        )

        assertEquals(1, candidates.size)
        assertEquals("https://v9-cdn.ppxvod.com/media/1.mp4?lr=superb&ch=add", candidates[0].url)
        assertEquals(PpxvodUrlScanner.Tier.WATERMARKED, candidates[0].tier)
    }

    @Test
    fun scannerPrefersCleanTierOverWatermarkedEvenWhenListedLater() {
        val html = """
            {"first":"https://v9-cdn.ppxvod.com/media/1/?lr=superb&ch=add",
             "second":"https://v9-cdn.ppxvod.com/media/1/?dr=6&dy_q=1700000000&ch=add"}
        """.trimIndent()
        val candidates = PpxvodUrlScanner.scan(html)

        assertEquals(1, candidates.size)
        assertEquals(PpxvodUrlScanner.Tier.CLEAN, candidates[0].tier)
    }

    @Test
    fun scannerKeepsDistinctBaseUrlsAndRanksTiersThenV26() {
        val html = """
            {"a":"https://v3-cdn.ppxvod.com/a.mp4?ch=add",
             "b":"https://v26-cdn.ppxvod.com/b.mp4?ch=add",
             "c":"https://v9-dy.ppxvod.com/c.mp4?lr=superb"}
        """.trimIndent()
        val candidates = PpxvodUrlScanner.scan(html)

        assertEquals(3, candidates.size)
        // A known watermarked source outranks an unknown-tier source.
        assertEquals(PpxvodUrlScanner.Tier.WATERMARKED, candidates[0].tier)
        assertEquals(PpxvodUrlScanner.Tier.UNKNOWN, candidates[1].tier)
        assertTrue(candidates[1].url.startsWith("https://v26-cdn."))
        assertEquals(PpxvodUrlScanner.Tier.UNKNOWN, candidates[2].tier)
        assertTrue(candidates[2].url.startsWith("https://v3-cdn."))
    }

    @Test
    fun scannerReturnsEmptyWithoutPpxvodHosts() {
        val html = """
            {"a":"https://example.com/video/1.mp4","b":"https://v26-cdn.example.com/b.mp4"}
        """.trimIndent()
        assertTrue(PpxvodUrlScanner.scan(html).isEmpty())
    }

    @Test
    fun extractorBuildsRecommendedCleanVideoWithBackupAndCover() {
        val encoded = "https%3A%2F%2Fv26-cdn.ppxvod.com%2Fmedia%2Fvideo%2F1%2F" +
            "%3Fa%3D1319%26dr%3D6%26dy_q%3D1700000000"
        val html = """
            <html><head>
            <title>这个皮皮虾视频太好笑了 - 皮皮虾</title>
            <meta name="description" content="分享一个搞笑视频，看完不亏">
            <meta property="og:image" content="https://img.pipix.com/cover/abc.jpg">
            </head><body>
            <script>window.__DATA__ = {"play":"$encoded",
              "backup":"https://v9-cdn.ppxvod.com/media/1.mp4?lr=superb"};</script>
            </body></html>
        """.trimIndent()

        val parsed = PipixiaDirectExtractor.extract(
            sourceUrl = "https://h5.pipix.com/item/696123456789/",
            itemId = "696123456789",
            html = html,
        )

        requireNotNull(parsed)
        assertEquals("皮皮虾", parsed.platform)
        assertEquals("分享一个搞笑视频，看完不亏", parsed.title)
        assertEquals("https://img.pipix.com/cover/abc.jpg", parsed.thumbnailUrl)
        assertEquals(1, parsed.items.size)
        val item = parsed.items[0]
        assertTrue(item.isRecommended)
        assertEquals("https://v26-cdn.ppxvod.com/media/video/1/?a=1319&dr=6&dy_q=1700000000", item.mediaUrl)
        assertEquals("https://v9-cdn.ppxvod.com/media/1.mp4?lr=superb", item.backupUrl)
        assertEquals(PpxvodUrlScanner.Tier.CLEAN.label, item.qualityLabel)
        assertEquals(SourceWatermark.PUBLIC_ORIGINAL, item.sourceWatermark)
        assertEquals(PpxvodUrlScanner.Tier.CLEAN.note, item.watermarkNote)
    }

    @Test
    fun scannerSurvivesIllegalPercentSequencesFromPageCss() {
        // Real h5 pages embed CSS like `rgba(...) -5.1%, rgba(...)`; the strict
        // java.net.URLDecoder throws on the `%,` pair, which emptied the
        // decoded scan variant on device. Lenient decoding must keep scanning.
        val encoded = "https%3A%2F%2Fv26-cdn.ppxvod.com%2Fmedia%2F1%2F" +
            "%3Fdr%3D6%26dy_q%3D1700000000"
        val html = """
            <html><head><style>
            .g{background:linear-gradient(104.5deg, rgba(133,157,255,0.2) -5.1%, rgba(255,97,92,0.2) 89.33%);}
            </style></head><body>
            <script>{"play":"$encoded"}</script>
            </body></html>
        """.trimIndent()

        val candidates = PpxvodUrlScanner.scan(html)

        assertEquals(1, candidates.size)
        assertEquals(PpxvodUrlScanner.Tier.CLEAN, candidates[0].tier)
        assertTrue("dr=6" in candidates[0].url && "dy_q" in candidates[0].url)
    }

    @Test
    fun lenientDecoderKeepsIllegalPercentSequencesAndPlusSigns() {
        assertEquals("a+1+2% zz", LenientPercentDecoder.decode("a+1%2B2% zz"))
        assertEquals("50;;", LenientPercentDecoder.decode("50%3B;"))
        assertEquals("50%zz;", LenientPercentDecoder.decode("50%zz%3B"))
        assertEquals("héllo", LenientPercentDecoder.decode("h%C3%A9llo"))
        assertEquals("100%分", LenientPercentDecoder.decode("100%25分"))
    }

    @Test
    fun extractorReturnsNullWhenPageHasNoDirectLinks() {
        assertNull(
            PipixiaDirectExtractor.extract(
                sourceUrl = "https://h5.pipix.com/item/696123456789/",
                itemId = "696123456789",
                html = "<html><head><title>皮皮虾</title></head><body>验证码</body></html>",
            ),
        )
    }
}
