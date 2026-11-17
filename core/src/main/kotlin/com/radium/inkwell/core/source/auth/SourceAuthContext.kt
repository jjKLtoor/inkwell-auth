package com.radium.inkwell.core.source.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Collections

/**
 * 来源身份与获准的 HTTPS origin 一起标识会话。保留 sourceId 的 # 后缀，避免同站多来源合并。
 * 这里只做作用域匹配；真正请求仍须经过现有 DNS / 私网防护，每一跳重定向都要重新取快照。
 */
class SourceAuthScope private constructor(val sourceId: String, private val origin: HttpUrl, private val additional: Set<HttpUrl>) {
    val originUrl: String get() = origin.toString()
    val originUrls: List<String> get() = (listOf(origin) + additional.sortedBy { it.toString() }).distinct().map { it.toString() }
    fun permitsPrimary(requestUrl: String): Boolean = authenticatedUrl(requestUrl)?.let { sameOrigin(it, origin) } == true
    private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
    fun permits(requestUrl: String): Boolean {
        val target = authenticatedUrl(requestUrl) ?: return false
        return sameOrigin(target, origin) || additional.any { sameOrigin(target, it) }
    }

    override fun equals(other: Any?): Boolean =
        other is SourceAuthScope && sourceId == other.sourceId && origin == other.origin && additional == other.additional

    override fun hashCode(): Int = 31 * (31 * sourceId.hashCode() + origin.hashCode()) + additional.hashCode()

    // sourceId 可能包含用户自定义内容，不进入默认调试输出。
    override fun toString(): String = "SourceAuthScope(redacted)"

    companion object {
        fun create(sourceId: String, sourceUrl: String, additionalOrigins: List<String> = emptyList()): SourceAuthScope? {
            if (sourceId.isBlank()) return null
            val url = authenticatedUrl(sourceUrl) ?: return null
            val origin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
            if (additionalOrigins.size > 1) return null
            val extra = additionalOrigins.map { value ->
                val approved = authenticatedUrl(value) ?: return null
                approved.newBuilder().encodedPath("/").query(null).fragment(null).build()
            }.filter { it != origin }.toSet()
            return SourceAuthScope(sourceId, origin, extra)
        }

        private fun authenticatedUrl(value: String): HttpUrl? = value.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty()
        }
    }
}

enum class SourceAuthStatus {
    NOT_CONFIGURED, LOGGED_OUT, AUTHENTICATING, VERIFIED, EXPIRED, UNKNOWN, ERROR,
}

/**
 * 只读内存快照，不实现 Serializable，也不使用会自动打印凭据的 data class。
 * UNKNOWN 表示有会话材料但没有登录校验证据；Cookie 存在或 HTTP 200 不能提升为 VERIFIED。
 */
class SourceAuthSnapshot(
    val status: SourceAuthStatus = SourceAuthStatus.UNKNOWN,
    val loginInfo: String? = null,
    loginHeaders: Map<String, String> = emptyMap(),
) {
    val loginHeaders: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(loginHeaders))

    override fun toString(): String = "SourceAuthSnapshot(status=$status, credentials=redacted)"
}

/**
 * 同步调用仅允许读内存，供 Rhino 与 HTTP 统一适配；磁盘恢复由 Android 层异步完成。
 * 调用方必须提供当前来源地址；不能只按域名猜测账号，也不能复用首跳快照处理后续跨域请求。
 * 这是状态快照契约；引擎 Cookie 与 Header 传输由 SourceRequestSession 承担。
 */
fun interface SourceAuthContext {
    fun snapshot(scope: SourceAuthScope, requestUrl: String): SourceAuthSnapshot?

    companion object {
        val NONE = SourceAuthContext { _, _ -> null }
    }
}
