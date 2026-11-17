package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.auth.SourceRequestSession

/** 登录的阻塞网页交互会切换调度器；同一次脚本的桥固定到草稿，不能恢复后读到匿名或其他来源身份。 */
internal class SessionBoundJsHttp(private val delegate: JsHttp, private val session: SourceRequestSession, private val context: kotlin.coroutines.CoroutineContext) : JsHttp {
    private fun <T> bound(block: () -> T): T = kotlinx.coroutines.runBlocking(context + SourceRequestSession.binding(session)) { block() }
    override fun fetch(url: String, method: String, body: String?, headers: Map<String,String>, followRedirects: Boolean) =
        bound { delegate.fetch(url,method,body,headers,followRedirects) }
    override fun fetchAll(requests: List<JsHttpRequest>) = bound { delegate.fetchAll(requests) }
    override fun fetchBinary(url: String, method: String, body: ByteArray?, headers: Map<String,String>, timeoutMs: Long) =
        bound { delegate.fetchBinary(url, method, body, headers, timeoutMs) }
    override fun cookieOf(url: String) = bound { delegate.cookieOf(url) }
    override fun setCookie(url: String, cookie: String) = bound { delegate.setCookie(url,cookie) }
    override fun removeCookie(url: String) = bound { delegate.removeCookie(url) }
}
