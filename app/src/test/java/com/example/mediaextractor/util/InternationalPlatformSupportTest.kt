package com.example.mediaextractor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternationalPlatformSupportTest {
    @Test
    fun canonicalizesYouTubeShareShortsAndEmbedUrls() {
        val expected = "https://www.youtube.com/watch?v=YE7VzlLtp-4"
        assertEquals(expected, InternationalMediaUrlCanonicalizer.canonicalize(
            "https://youtu.be/YE7VzlLtp-4?si=tracking",
        ))
        assertEquals(expected, InternationalMediaUrlCanonicalizer.canonicalize(
            "https://m.youtube.com/shorts/YE7VzlLtp-4?feature=share",
        ))
        assertEquals(expected, InternationalMediaUrlCanonicalizer.canonicalize(
            "https://www.youtube-nocookie.com/embed/YE7VzlLtp-4?start=10",
        ))
    }

    @Test
    fun canonicalizesInstagramMediaAndRemovesTrackingParameters() {
        assertEquals(
            "https://www.instagram.com/reel/Chunk8-jurw/",
            InternationalMediaUrlCanonicalizer.canonicalize(
                "https://www.instagram.com/example/reels/Chunk8-jurw/?igsh=tracking",
            ),
        )
    }

    @Test
    fun preservesOnlyFacebookIdentityQueryParameters() {
        assertEquals(
            "https://www.facebook.com/watch?v=1195289147628387",
            InternationalMediaUrlCanonicalizer.canonicalize(
                "https://m.facebook.com/watch?v=1195289147628387&ref=sharing&fbclid=tracking",
            ),
        )
    }

    @Test
    fun identifiesShareRoutesThatNeedOfficialRedirectExpansion() {
        assertTrue(InternationalMediaUrlCanonicalizer.needsNetworkExpansion("https://fb.watch/abc/"))
        val instagramShare = InternationalMediaUrlCanonicalizer.canonicalize(
            "https://www.instagram.com/share/reel/abc/?igsh=tracking",
        )
        assertEquals("https://www.instagram.com/share/reel/abc/", instagramShare)
        assertTrue(InternationalMediaUrlCanonicalizer.needsNetworkExpansion(instagramShare))
        assertFalse(InternationalMediaUrlCanonicalizer.needsNetworkExpansion(
            "https://www.instagram.com/reel/abc/",
        ))
    }

    @Test
    fun mapsInternationalAccessRestrictionsWithoutAffectingOrdinaryErrors() {
        assertEquals(
            "请更换节点重试",
            InternationalPlatformFailurePolicy.accessRestrictionMessage(
                "https://www.youtube.com/watch?v=YE7VzlLtp-4",
                "Sign in to confirm you're not a bot",
            ),
        )
        assertNotNull(InternationalPlatformFailurePolicy.accessRestrictionMessage(
            "https://www.instagram.com/reel/abc/",
            "Main webpage is locked behind the login page",
        ))
        assertNull(InternationalPlatformFailurePolicy.accessRestrictionMessage(
            "https://www.facebook.com/reel/123",
            "Unable to extract media formats",
        ))
    }
}
