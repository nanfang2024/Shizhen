package com.framepick.app.util

import java.net.URI

data class PlatformInfo(
    val displayName: String,
    val domain: String,
)

object PlatformRecognizer {
    private val knownPlatforms = listOf(
        setOf("douyin.com", "iesdouyin.com") to "抖音",
        setOf("doubao.com") to "豆包",
        setOf("tiktok.com") to "TikTok",
        setOf("bilibili.com", "b23.tv") to "哔哩哔哩",
        setOf("weibo.com", "weibo.cn", "t.cn") to "微博",
        setOf("xiaohongshu.com", "xhslink.com", "xhslink.cn", "rednote.com") to "小红书",
        setOf("kuaishou.com", "chenzhongtech.com", "gifshow.com", "kwai.com") to "快手",
        setOf("ixigua.com", "xigua.com") to "西瓜视频",
        setOf("pipix.com") to "皮皮虾",
        setOf("acfun.cn") to "AcFun",
        setOf("youku.com", "tudou.com") to "优酷",
        setOf("iqiyi.com") to "爱奇艺",
        setOf("mgtv.com") to "芒果 TV",
        setOf("v.qq.com") to "腾讯视频",
        setOf("haokan.baidu.com") to "好看视频",
        setOf("toutiao.com") to "今日头条",
        setOf("pearvideo.com") to "梨视频",
        setOf("miaopai.com") to "秒拍",
        setOf("meipai.com") to "美拍",
        setOf("sohu.com") to "搜狐视频",
        setOf("y.qq.com") to "QQ音乐",
        setOf("kugou.com", "kugou.net") to "酷狗音乐",
        setOf("kuwo.cn", "kuwo.com") to "酷我音乐",
        setOf("music.migu.cn", "m.music.migu.cn") to "咪咕音乐",
        setOf("163cn.tv", "music.163.com") to "网易云音乐",
        setOf("163.com") to "网易视频",
        setOf("zhihu.com") to "知乎",
        setOf("x.com", "twitter.com", "t.co") to "X / Twitter",
        setOf("instagram.com", "instagr.am") to "Instagram",
        setOf("facebook.com", "fb.watch", "fb.com") to "Facebook",
        setOf("youtube.com", "youtube-nocookie.com", "youtu.be") to "YouTube",
        setOf("vimeo.com") to "Vimeo",
    )

    fun recognize(url: String): PlatformInfo? = runCatching {
        val uri = URI(url)
        val domain = uri.host?.lowercase()?.removePrefix("www.")
            ?: return@runCatching null
        val path = uri.path.orEmpty()
        val displayName = when {
            isDomain(domain, "iesdouyin.com") && path.startsWith("/xg/") -> "西瓜视频"
            domain == "v.douyin.com" -> "抖音/西瓜视频短链"
            domain == "weixin.qq.com" && path.startsWith("/sph/") -> "微信视频号"
            isDomain(domain, "channels.weixin.qq.com") -> "微信视频号"
            else -> knownPlatforms.firstOrNull { (domains, _) ->
                domains.any { isDomain(domain, it) }
            }?.second ?: domain
        }
        PlatformInfo(displayName = displayName, domain = domain)
    }.getOrNull()

    private fun isDomain(actual: String, expected: String): Boolean =
        actual == expected || actual.endsWith(".$expected")
}
