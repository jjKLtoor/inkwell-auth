package com.radium.inkwell.core.source.auth

import kotlinx.coroutines.asContextElement
import okhttp3.CookieJar
import kotlin.coroutines.CoroutineContext

/** 身份随协程迁移，HTTP 与同步脚本使用同一来源会话，禁止在线程间残留身份。 */
interface SourceRequestSession : CookieJar {
    val scope: SourceAuthScope
    val active: Boolean
    val hasCredentials: Boolean
    val loginData: MutableMap<String, String>
    /** 仅供隔离渲染器恢复完整 Cookie 元数据，默认不授权旧实现导出身份。 */
    fun cookiesForRendering(): List<okhttp3.Cookie> = emptyList()
    /** 隔离渲染结束后的乐观合并；实现须拒绝覆盖并发更新或已撤销的身份。 */
    fun mergeRenderedCookies(original: List<okhttp3.Cookie>, current: List<okhttp3.Cookie>, observed: List<okhttp3.HttpUrl>) {}
    suspend fun flush()

    companion object {
        private val current = ThreadLocal<SourceRequestSession?>()
        fun current(): SourceRequestSession? = current.get()
        fun binding(session: SourceRequestSession?): CoroutineContext = current.asContextElement(session)
    }
}

fun interface SourceSessionProvider {
    suspend fun open(scope: SourceAuthScope): SourceRequestSession

    suspend fun forSource(source: com.radium.inkwell.core.source.BookSourceRule): SourceRequestSession? =
        SourceAuthScope.create(source.id, source.baseUrl)?.let { open(it) }
}
