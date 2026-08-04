package com.example.mediaextractor.util

import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Stable, public URL shapes understood by the bundled extractors. */
object InternationalMediaUrlCanonicalizer {
    fun canonicalize(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val host = parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
        return when {
            isYouTubeHost(host) -> canonicalizeYouTube(parsed)
            isInstagramHost(host) -> canonicalizeInstagram(parsed)
            isFacebookHost(host) -> canonicalizeFacebook(parsed)
            else -> url
        }
    }

    fun needsNetworkExpansion(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase(Locale.ROOT).removePrefix("www.")
        val path = parsed.encodedPath.lowercase(Locale.ROOT)
        return host == "fb.watch" || host == "fb.com" || host == "instagr.am" ||
            (isInstagramHost(host) && path.startsWith("/share/")) ||
            (isFacebookHost(host) && path.startsWith("/share/"))
    }

    private fun canonicalizeYouTube(url: HttpUrl): String {
        val host = url.host.lowercase(Locale.ROOT).removePrefix("www.")
        val segments = url.pathSegments.filter(String::isNotBlank)
        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            segments.firstOrNull() in setOf("shorts", "embed", "live", "v") ->
                segments.getOrNull(1)
            else -> url.queryParameter("v")
        }
        val videoId = candidate?.takeIf { it.matches(YOUTUBE_VIDEO_ID) }
            ?: return url.newBuilder().scheme("https").fragment(null).build().toString()
        return "https://www.youtube.com/watch?v=$videoId"
    }

    private fun canonicalizeInstagram(url: HttpUrl): String {
        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.firstOrNull() == "share") {
            return url.newBuilder()
                .scheme("https")
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        val mediaIndex = segments.indexOfFirst { it in INSTAGRAM_MEDIA_ROUTES }
        if (mediaIndex >= 0 && mediaIndex + 1 < segments.size) {
            val route = if (segments[mediaIndex] == "reels") "reel" else segments[mediaIndex]
            val shortcode = segments[mediaIndex + 1].takeIf { it.matches(MEDIA_CODE) }
            if (shortcode != null) return "https://www.instagram.com/$route/$shortcode/"
        }
        return url.newBuilder()
            .scheme("https")
            .host(if (url.host == "instagr.am") "www.instagram.com" else url.host)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun canonicalizeFacebook(url: HttpUrl): String {
        val originalQuery = url.queryParameterNames.associateWith(url::queryParameter)
        return url.newBuilder()
            .scheme("https")
            .host(
                if (url.host == "facebook.com" || url.host.endsWith(".facebook.com")) {
                    "www.facebook.com"
                } else {
                    url.host
                },
            )
            .query(null)
            .apply {
                FACEBOOK_REQUIRED_QUERY_KEYS.forEach { key ->
                    originalQuery[key]?.let { addQueryParameter(key, it) }
                }
            }
            .fragment(null)
            .build()
            .toString()
    }

    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "youtu.be"

    private fun isInstagramHost(host: String): Boolean =
        host == "instagram.com" || host.endsWith(".instagram.com") || host == "instagr.am"

    private fun isFacebookHost(host: String): Boolean =
        host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.watch" || host == "fb.com"

    private val YOUTUBE_VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val MEDIA_CODE = Regex("[A-Za-z0-9_-]+")
    private val INSTAGRAM_MEDIA_ROUTES = setOf("p", "reel", "reels", "tv")
    private val FACEBOOK_REQUIRED_QUERY_KEYS = listOf("v", "video_id", "story_fbid", "id")
}

/** Maps current anonymous-access failures without exposing extractor internals to users. */
object InternationalPlatformFailurePolicy {
    fun accessRestrictionMessage(url: String, errorText: String): String? {
        val lowered = errorText.lowercase(Locale.ROOT)
        if (ACCESS_MARKERS.none(lowered::contains)) return null
        return when (PlatformRecognizer.recognize(url)?.displayName) {
            "Instagram" ->
                "Instagram 没有向未登录访问公开此内容，或匿名访问频率受限。拾帧不导入 Cookie，请稍后重试并确认该链接在未登录浏览器中可以打开。"
            "Facebook" ->
                "Facebook 没有向未登录访问公开此内容，或该帖子存在地区、年龄或账号限制。拾帧不导入 Cookie，请确认它是无需登录即可播放的公开资源。"
            "YouTube" ->
                "请更换节点重试"
            else -> null
        }
    }

    private val ACCESS_MARKERS = setOf(
        "login",
        "log in",
        "logged-in",
        "sign in",
        "cookie",
        "private",
        "age-restricted",
        "age restricted",
        "authentication",
        "registered users",
        "not a bot",
        "rate-limit",
        "rate limit",
        "restricted video",
        "members-only",
        "premium",
        "po token",
    )
}
