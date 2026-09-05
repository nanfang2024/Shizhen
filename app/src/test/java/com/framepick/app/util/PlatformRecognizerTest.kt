package com.framepick.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformRecognizerTest {
    @Test
    fun recognizesKnownShortDomain() {
        val result = PlatformRecognizer.recognize("https://b23.tv/abc")
        assertEquals("哔哩哔哩", result?.displayName)
        assertEquals("b23.tv", result?.domain)
    }

    @Test
    fun recognizesBilibiliVideoAndOpusPages() {
        assertEquals(
            "哔哩哔哩",
            PlatformRecognizer.recognize("https://www.bilibili.com/video/BV1GJ411x7h7/")
                ?.displayName,
        )
        assertEquals(
            "哔哩哔哩",
            PlatformRecognizer.recognize("https://m.bilibili.com/opus/123456")?.displayName,
        )
    }

    @Test
    fun usesDomainForUnknownPlatform() {
        assertEquals(
            "media.example.com",
            PlatformRecognizer.recognize("https://media.example.com/file.mp4")?.displayName,
        )
    }

    @Test
    fun rejectsInvalidUrl() {
        assertNull(PlatformRecognizer.recognize("not-a-url"))
    }

    @Test
    fun recognizesXAndLegacyTwitterDomains() {
        assertEquals(
            "X / Twitter",
            PlatformRecognizer.recognize("https://x.com/example/status/123")?.displayName,
        )
        assertEquals(
            "X / Twitter",
            PlatformRecognizer.recognize("https://twitter.com/example/status/123")?.displayName,
        )
    }

    @Test
    fun recognizesXiaohongshuCnShortDomain() {
        assertEquals(
            "小红书",
            PlatformRecognizer.recognize("http://xhslink.cn/o/example")?.displayName,
        )
    }

    @Test
    fun recognizesKuaishouRedirectDomain() {
        assertEquals(
            "快手",
            PlatformRecognizer.recognize("https://v.m.chenzhongtech.com/fw/photo/example")
                ?.displayName,
        )
    }

    @Test
    fun recognizesPipixiaShareAndItemDomains() {
        assertEquals(
            "皮皮虾",
            PlatformRecognizer.recognize("https://h5.pipix.com/s/abc/")?.displayName,
        )
        assertEquals(
            "皮皮虾",
            PlatformRecognizer.recognize("https://www.pipix.com/item/123456/")?.displayName,
        )
    }

    @Test
    fun recognizesNeteaseMusicShortAndPageDomains() {
        assertEquals(
            "网易云音乐",
            PlatformRecognizer.recognize("https://163cn.tv/example")?.displayName,
        )
        assertEquals(
            "网易云音乐",
            PlatformRecognizer.recognize("https://y.music.163.com/m/song?id=1")?.displayName,
        )
    }

    @Test
    fun recognizesMainstreamMusicPlatforms() {
        assertEquals("QQ音乐", PlatformRecognizer.recognize("https://c6.y.qq.com/share")?.displayName)
        assertEquals("酷狗音乐", PlatformRecognizer.recognize("https://m.kugou.com/share")?.displayName)
        assertEquals("酷我音乐", PlatformRecognizer.recognize("https://www.kuwo.cn/play_detail/1")?.displayName)
        assertEquals("咪咕音乐", PlatformRecognizer.recognize("https://music.migu.cn/v3/music/song/1")?.displayName)
    }

    @Test
    fun treatsDouyinShortHostAsAmbiguousUntilRedirected() {
        assertEquals(
            "抖音/西瓜视频短链",
            PlatformRecognizer.recognize("https://v.douyin.com/N0oHncGQg4Y/")?.displayName,
        )
    }

    @Test
    fun recognizesXiguaPageServedFromIesDouyin() {
        assertEquals(
            "西瓜视频",
            PlatformRecognizer.recognize(
                "https://www.iesdouyin.com/xg/video/7651243563103112484/",
            )?.displayName,
        )
        assertEquals(
            "抖音",
            PlatformRecognizer.recognize("https://www.iesdouyin.com/share/video/123")?.displayName,
        )
    }

    @Test
    fun recognizesMainstreamInternationalPlatformsAndShareDomains() {
        assertEquals(
            "Instagram",
            PlatformRecognizer.recognize("https://www.instagram.com/reel/Chunk8-jurw/")
                ?.displayName,
        )
        assertEquals(
            "Facebook",
            PlatformRecognizer.recognize("https://fb.watch/example/")?.displayName,
        )
        assertEquals(
            "YouTube",
            PlatformRecognizer.recognize("https://www.youtube-nocookie.com/embed/YE7VzlLtp-4")
                ?.displayName,
        )
    }

    @Test
    fun recognizesDoubaoPublicPages() {
        assertEquals(
            "豆包",
            PlatformRecognizer.recognize("https://www.doubao.com/share/example")?.displayName,
        )
    }
}
