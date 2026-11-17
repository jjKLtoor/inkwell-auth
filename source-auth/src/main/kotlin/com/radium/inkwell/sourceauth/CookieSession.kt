package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.Collections

/** 保留 Cookie 元数据，不把会话简化为无作用域字符串。没有任何自动 VERIFIED 判断。 */
class CookieSession(cookies: List<Cookie>, loginData: Map<String, String> = emptyMap(), webOrigins: List<String> = emptyList(), val allowWebResources: Boolean = false, val loginPageUrl: String? = null) {
    val loginData: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(loginData))
    val cookies: List<Cookie> = Collections.unmodifiableList(cookies.toList())
    /** 用户明确选择记住的网页登录站点；绝不加入阅读引擎的认证作用域。 */
    val webOrigins: List<String> = Collections.unmodifiableList(webOrigins.distinct())
    override fun toString(): String = "CookieSession(redacted)"
}

/** 来源会话 Cookie 适配，由 PersistentSourceSessions 接入阅读引擎；每一跳由 OkHttp 再按 origin 和 Cookie.matches 筛选。 */
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

    @Synchronized
    fun snapshot(): List<Cookie> = cookies.values.toList()

    private fun key(cookie: Cookie) = "${cookie.name}\u0000${cookie.domain}\u0000${cookie.path}"
}
