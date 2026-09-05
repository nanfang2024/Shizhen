package com.framepick.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test
    fun extractsPureLink() {
        assertEquals("https://example.com/a", UrlExtractor.extractFirst("https://example.com/a"))
    }

    @Test
    fun extractsLinkFromShareText() {
        val text = "复制这段文字，打开某平台查看作品 https://example.com/abc123 这是分享内容"
        assertEquals("https://example.com/abc123", UrlExtractor.extractFirst(text))
    }

    @Test
    fun returnsFirstOfMultipleLinks() {
        val text = "先看 https://first.example/a 再看 https://second.example/b"
        assertEquals("https://first.example/a", UrlExtractor.extractFirst(text))
    }

    @Test
    fun returnsNullWhenThereIsNoLink() {
        assertNull(UrlExtractor.extractFirst("这里只是一段没有链接的文字"))
    }

    @Test
    fun skipsMalformedLink() {
        assertNull(UrlExtractor.extractFirst("错误链接 https:// 以及 http://?bad"))
    }

    @Test
    fun acceptsShortLinkDomain() {
        assertEquals("https://b23.tv/AbCd12", UrlExtractor.extractFirst("https://b23.tv/AbCd12"))
    }

    @Test
    fun removesChineseTrailingPunctuation() {
        val text = "作品地址：https://example.com/a?x=1，欢迎查看。"
        assertEquals("https://example.com/a?x=1", UrlExtractor.extractFirst(text))
    }

    @Test
    fun stopsBeforeChineseShareInstructionWithoutWhitespace() {
        val text = "https://v.douyin.com/N0oHncGQg4Y/点击链接直接打开"
        assertEquals(
            "https://v.douyin.com/N0oHncGQg4Y/",
            UrlExtractor.extractFirst(text),
        )
    }
}
