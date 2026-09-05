package com.framepick.app.util

import java.net.URI
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Normalizes only known public platform/CDN URLs; arbitrary user URLs are left unchanged. */
object PublicUrlNormalizer {
    fun upgradeKnownHttp(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.scheme != "http" || !supportsHttps(parsed.host)) return url
        return parsed.newBuilder().scheme("https").build().toString()
    }

    fun isShortLink(url: String): Boolean = hostOf(url)?.let { host ->
        SHORT_LINK_HOSTS.any { host == it || host.endsWith(".$it") }
    } == true

    fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
    }.getOrNull()

    private fun supportsHttps(host: String): Boolean = HTTPS_HOSTS.any {
        host == it || host.endsWith(".$it")
    }

    private val SHORT_LINK_HOSTS = setOf(
        "v.douyin.com",
        "b23.tv",
        "xhslink.cn",
        "xhslink.com",
        "v.kuaishou.com",
        "k.kuaishou.com",
        "weibo.cn",
        "t.cn",
        "v.ixigua.com",
        "v.youku.com",
        "163cn.tv",
        "c6.y.qq.com",
        "fb.watch",
        "fb.com",
        "instagr.am",
        "youtu.be",
    )

    private val HTTPS_HOSTS = setOf(
        "xhslink.cn",
        "xhslink.com",
        "xiaohongshu.com",
        "rednote.com",
        "xhscdn.com",
        "hdslb.com",
        "b23.tv",
        "kuaishou.com",
        "chenzhongtech.com",
        "gifshow.com",
        "yximgs.com",
        "ndcimgs.com",
        "douyin.com",
        "douyinpic.com",
        "douyinvod.com",
        "ixigua.com",
        "byteimg.com",
        "bytecdn.cn",
        "sinaimg.cn",
        "sina.com.cn",
        "bdstatic.com",
        "bdimg.com",
        "163cn.tv",
        "music.163.com",
        "music.126.net",
        "y.qq.com",
        "qqmusic.qq.com",
        "aqqmusic.tc.qq.com",
        "kugou.com",
        "kugou.net",
        "kgimg.com",
        "kugoucdn.com",
        "kuwo.cn",
        "kuwo.com",
        "music.migu.cn",
        "m.music.migu.cn",
        "miguvideo.com",
        "instagram.com",
        "instagr.am",
        "facebook.com",
        "fb.watch",
        "fb.com",
        "youtube.com",
        "youtube-nocookie.com",
        "youtu.be",
        "doubao.com",
    )
}
