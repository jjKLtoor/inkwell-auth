package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.Collections

/** 保留 Cookie 元数据，不把会话简化为无作用域字符串。没有任何自动 VERIFIED 判断。 */
class CookieSession(cookies: List<Cookie>) {
    val cookies: List<Cookie> = Collections.unmodifiableList(cookies.toList())
    override fun toString(): String = "CookieSession(redacted)"
}

/** 阶段 B 的受控 HTTP 适配，尚未连接阅读引擎；每一跳由 OkHttp 再按 origin 和 Cookie.matches 筛选。 */
class SessionCookieJar(private val scope: SourceAuthScope, session: CookieSession) : CookieJar {
    private val cookies = session.cookies.associateByTo(LinkedHashMap()) { key(it) }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        if (!scope.permits(url.toString())) emptyList() else cookies.values.filter {
            it.expiresAt > System.currentTimeMillis() && it.matches(url)
        }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!scope.permits(url.toString())) return
        for (cookie in cookies) {
            if (cookie.expiresAt <= System.currentTimeMillis()) this.cookies.remove(key(cookie))
            else if (this.cookies.size < 256 || key(cookie) in this.cookies) this.cookies[key(cookie)] = cookie
        }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun key(cookie: Cookie) = "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"
}
