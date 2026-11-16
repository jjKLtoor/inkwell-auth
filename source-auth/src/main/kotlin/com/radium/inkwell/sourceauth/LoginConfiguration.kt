package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

sealed interface LoginConfiguration {
    data object NotConfigured : LoginConfiguration

    /** 仅代表配置识别通过，不代表登录 UI、网络请求或会话验证已经实现。 */
    class WebPage(val url: String) : LoginConfiguration {
        override fun toString(): String = "WebPage(url=redacted)"
    }

    data class Unsupported(val reason: Reason) : LoginConfiguration

    enum class Reason { CUSTOM_UI, SCRIPT_OR_DYNAMIC, INVALID_URL, UNSAFE_URL }
}

/** 不执行来源脚本、不做 DNS / 网络 IO；真正打开页面前仍须通过 WebViewNetworkGuard。 */
fun resolveLoginConfiguration(source: BookSourceRule): LoginConfiguration {
    // 有表单配置就不能假装只开 loginUrl 能完成该来源的自定义登录。
    if (!source.loginUi.isNullOrBlank()) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.CUSTOM_UI)
    }
    val value = source.loginUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return LoginConfiguration.NotConfigured
    if (value.startsWith("@js:", true) || value.startsWith("<js>", true) ||
        value.startsWith("javascript:", true) || value.contains("{{") || value.contains("}}")) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.SCRIPT_OR_DYNAMIC)
    }
    // 严格 URI 校验拒绝空格、反斜杠、脚本块；相对地址只接受显式路径，避免把 JS 当路径。
    val uri = runCatching { URI(value) }.getOrNull()
        ?: return LoginConfiguration.Unsupported(LoginConfiguration.Reason.INVALID_URL)
    val absolute = uri.isAbsolute
    if (absolute && (!uri.scheme.equals("https", true) || uri.rawAuthority.isNullOrEmpty())) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.UNSAFE_URL)
    }
    if (!absolute && !(value.startsWith("/") || value.startsWith("./") || value.startsWith("../"))) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.SCRIPT_OR_DYNAMIC)
    }
    val base = source.baseUrl.toHttpUrlOrNull()
        ?: return LoginConfiguration.Unsupported(LoginConfiguration.Reason.INVALID_URL)
    if (base.username.isNotEmpty() || base.password.isNotEmpty()) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.UNSAFE_URL)
    }
    val url = if (absolute) value.toHttpUrlOrNull() else base.resolve(value)
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        isPrivateNetworkLiteral(url.host)) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.UNSAFE_URL)
    }
    // 初期只接受与来源完全同 origin 的页面；跨站 SSO 需后续明确授权域与导航策略。
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) {
        return LoginConfiguration.Unsupported(LoginConfiguration.Reason.UNSAFE_URL)
    }
    return LoginConfiguration.WebPage(url.toString())
}
