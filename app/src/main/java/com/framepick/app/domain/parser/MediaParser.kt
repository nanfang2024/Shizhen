package com.framepick.app.domain.parser

import com.framepick.app.domain.model.ParsedMedia

interface MediaParser {
    fun canHandle(url: String): Boolean

    suspend fun parse(url: String): Result<ParsedMedia>
}

class MediaParseException(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason {
        ACCESS_RESTRICTED,
        NETWORK,
        EXPIRED,
        UNSUPPORTED,
        NO_MEDIA,
        NO_CLEAN_SOURCE,
        INVALID_RESPONSE,
    }
}

object ParserMessages {
    const val ACCESS_RESTRICTED =
        "该资源需要登录或存在访问限制，拾帧不支持提取。"
    const val NETWORK_UNAVAILABLE = "网络连接失败，请检查网络后重试。"
    const val NO_MEDIA = "暂时无法解析这个链接，可能是平台规则发生变化。"
    const val NO_CLEAN_SOURCE =
        "未找到可验证的公开原始源；平台可能只提供带水印版本或没有返回水印标记。"
    const val PREVIEW_ONLY =
        "该歌曲的公开页面只提供试听片段；完整音频需要登录、购买或客户端授权，拾帧不支持绕过。"
}
