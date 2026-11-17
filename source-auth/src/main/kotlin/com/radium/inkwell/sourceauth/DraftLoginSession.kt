package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceRequestSession
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

/** 登录按钮只修改草稿；取消或脚本失败不清除原有持久身份。草稿不写磁盘、不记录日志。 */
class DraftLoginSession(override val scope: SourceAuthScope, original: CookieSession?) : SourceRequestSession {
    private val jar = SessionCookieJar(scope, original ?: CookieSession(emptyList()))
    @Volatile override var active = true
        private set
    override val restrictNetworkToScope = true
    override val loginData: MutableMap<String, String> = ConcurrentHashMap(original?.loginData.orEmpty())
    override val hasCredentials get() = active && (jar.snapshot().isNotEmpty() || loginData.isNotEmpty())
    @Synchronized override fun loadForRequest(url: HttpUrl) = if (active) jar.loadForRequest(url) else emptyList()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { if (active) jar.saveFromResponse(url, cookies) }
    @Synchronized override fun cookiesForRendering() = if (active) jar.snapshot() else emptyList()
    override suspend fun flush() = Unit
    @Synchronized fun importCookies(value: CookieSession) {
        check(active)
        for (cookie in value.cookies) {
            val url = scope.originUrls.map { it.toHttpUrl().newBuilder().encodedPath(cookie.path).build() }.firstOrNull { cookie.matches(it) }
                ?: error("网页凭据超出来源认证范围")
            jar.saveFromResponse(url, listOf(cookie))
        }
    }
    @Synchronized fun snapshot(pageUrl: String?): CookieSession {
        check(active)
        return CookieSession(jar.snapshot(), loginData.toMap(), loginPageUrl = pageUrl)
    }
    @Synchronized fun revoke() { active = false; jar.clear(); loginData.clear() }
    override fun toString() = "DraftLoginSession(redacted)"
}
