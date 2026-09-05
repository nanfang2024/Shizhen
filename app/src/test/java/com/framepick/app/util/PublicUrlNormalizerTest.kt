package com.framepick.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicUrlNormalizerTest {
    @Test
    fun upgradesKnownXiaohongshuShareLinkToHttps() {
        assertEquals(
            "https://xhslink.cn/o/example",
            PublicUrlNormalizer.upgradeKnownHttp("http://xhslink.cn/o/example"),
        )
    }

    @Test
    fun doesNotRewriteUnknownHttpDomain() {
        assertEquals(
            "http://legacy.example.com/file.mp4",
            PublicUrlNormalizer.upgradeKnownHttp("http://legacy.example.com/file.mp4"),
        )
    }

    @Test
    fun recognizesDomesticShortLinkHosts() {
        assertTrue(PublicUrlNormalizer.isShortLink("https://b23.tv/example"))
        assertTrue(PublicUrlNormalizer.isShortLink("https://v.kuaishou.com/example"))
        assertTrue(PublicUrlNormalizer.isShortLink("http://xhslink.cn/o/example"))
        assertTrue(PublicUrlNormalizer.isShortLink("https://163cn.tv/example"))
        assertFalse(PublicUrlNormalizer.isShortLink("https://www.bilibili.com/video/BV1"))
    }

    @Test
    fun upgradesNeteaseMusicCdnToHttps() {
        assertEquals(
            "https://p2.music.126.net/cover/album.jpg",
            PublicUrlNormalizer.upgradeKnownHttp("http://p2.music.126.net/cover/album.jpg"),
        )
    }

    @Test
    fun recognizesQqMusicShortLinkAndUpgradesOfficialAudioCdn() {
        assertTrue(PublicUrlNormalizer.isShortLink("http://c6.y.qq.com/base/fcgi-bin/u?__=share"))
        assertEquals(
            "https://aqqmusic.tc.qq.com/public.m4a",
            PublicUrlNormalizer.upgradeKnownHttp("http://aqqmusic.tc.qq.com/public.m4a"),
        )
    }

    @Test
    fun recognizesInternationalShareHosts() {
        assertTrue(PublicUrlNormalizer.isShortLink("https://fb.watch/example/"))
        assertTrue(PublicUrlNormalizer.isShortLink("https://instagr.am/p/example/"))
        assertTrue(PublicUrlNormalizer.isShortLink("https://youtu.be/YE7VzlLtp-4"))
    }
}
