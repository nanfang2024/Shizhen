package com.framepick.app.domain.parser

import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

private val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")

/**
 * NewPipeExtractor 的网络层适配：把抽取器的 HTTP 请求接到项目统一的 OkHttp 客户端上，
 * 让 YouTube 抽取器与站内其他平台共享代理、超时与 TLS 配置。
 */
class NewPipeOkHttpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val rawBody = request.dataToSend()
        val requiresBody = request.httpMethod() in BODY_REQUIRED_METHODS
        val body = when {
            rawBody != null -> rawBody.toRequestBody()
            requiresBody -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.header(name, value) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("YouTube 要求人机验证（429），请稍后重试。", response.request.url.toString())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}
