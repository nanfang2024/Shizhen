package com.example.mediaextractor.util

import java.net.URI

object UrlExtractor {
    private val candidateRegex = Regex(
        // A URI accepted by java.net.URI has an ASCII wire representation. Restricting the
        // share-text candidate to visible ASCII also gives us a reliable boundary when an app
        // emits "https://short.url/code/点击链接" without inserting whitespace.
        "https?://[\\u0021-\\u007E&&[^<>\\\"']]+",
        RegexOption.IGNORE_CASE,
    )
    private val trailingPunctuation = setOf(
        '.', ',', ';', ':', '!', '?',
        '，', '。', '；', '：', '！', '？', '、',
        ')', ']', '}', '）', '】', '》', '」', '』', '”', '’',
    )

    fun extractFirst(text: String): String? = candidateRegex
        .findAll(text)
        .map { it.value.trimTrailingPunctuation() }
        .firstOrNull(::isValidHttpUrl)

    fun isValidHttpUrl(value: String): Boolean {
        if (value.isBlank() || value.any(Char::isWhitespace)) return false
        return runCatching {
            val uri = URI(value)
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }

    private fun String.trimTrailingPunctuation(): String =
        trimEnd { it in trailingPunctuation }
}
