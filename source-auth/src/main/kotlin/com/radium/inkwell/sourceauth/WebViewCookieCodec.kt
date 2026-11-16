package com.radium.inkwell.sourceauth

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * Chromium 的 GetCookieInfo 是规范化的属性行，host-only 也带 domain=host；域 Cookie 带前导点。
 * 因此不能直接当服务端 Set-Cookie 解析，否则 host-only 会扩大为子域可用，__Host- 前缀也会失效。
 * 仅用于平台 Cookie 信息，不用于解析 HTTP 响应；不支持的分区 Cookie 拒绝转换。
 */
internal fun parseWebViewCookie(url: HttpUrl, attributes: String): Cookie? {
    val parts = attributes.split(';')
    if (parts.drop(1).any { it.trim().equals("partitioned", true) }) return null
    val domainPart = parts.drop(1).firstOrNull { it.substringBefore('=').trim().equals("domain", true) }
    val domain = domainPart?.substringAfter('=')?.trim()
    val normalized = if (domain != null && !domain.startsWith('.')) {
        if (!domain.equals(url.host, true)) return null
        parts.filterIndexed { index, part -> index == 0 || !part.substringBefore('=').trim().equals("domain", true) }.joinToString(";")
    } else attributes
    return Cookie.parse(url, normalized)
}
