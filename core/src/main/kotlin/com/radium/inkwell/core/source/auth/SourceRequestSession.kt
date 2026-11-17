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
    suspend fun flush()

    companion object {
        private val current = ThreadLocal<SourceRequestSession?>()
        fun current(): SourceRequestSession? = current.get()
        fun binding(session: SourceRequestSession?): CoroutineContext = current.asContextElement(session)
    }
}

fun interface SourceSessionProvider {
    suspend fun open(scope: SourceAuthScope): SourceRequestSession
}
