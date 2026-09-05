package com.framepick.app.domain.parser

object PlatformMediaClassifier {
    fun isTwitterAnimatedGif(platform: String, urls: Iterable<String?>): Boolean =
        platform == "X / Twitter" && urls.any {
            it.orEmpty().contains("/tweet_video/", ignoreCase = true)
        }
}
