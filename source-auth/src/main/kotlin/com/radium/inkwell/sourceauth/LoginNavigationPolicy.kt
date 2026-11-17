package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 网页按普通浏览器跟随公开 HTTPS 地址；阅读凭据仍由原有 SourceAuthScope 单独约束。 */
class LoginNavigationPolicy(private val scope: SourceAuthScope) {
    private val legacyOrigins = mutableSetOf<String>()

    // 已声明来源还须经过现有网络保护；保留可注入本地 HTTPS 夹具的测试边界。
    fun permits(url: String): Boolean = scope.permits(url) || origin(url) != null

    fun origin(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && !isPrivateNetworkLiteral(it.host)
    }?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()

    /** 仅校验旧版密文中的站点字段，读取它不再控制当前网页登录，也不扩大阅读认证。 */
    fun restore(site: String): Boolean {
        if (origin(site) != site || legacyOrigins.size >= 8) return false
        legacyOrigins.add(site)
        return true
    }
}
