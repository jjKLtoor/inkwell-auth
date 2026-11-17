package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/** 仅管理一次交互页的显式临时许可，不修改阅读认证作用域或持久化数据。 */
class LoginNavigationPolicy(private val scope: SourceAuthScope) {
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val temporary = ConcurrentHashMap.newKeySet<String>()

    fun permits(url: String): Boolean = scope.permits(url) || origin(url)?.let { it in temporary } == true

    fun recordBlocked(url: String): String? {
        val origin = origin(url) ?: return null
        if (pending.size < 16) pending.add(origin)
        return origin
    }

    /** 调用前须由原生确认弹窗征得用户许可，并通过现有 DNS/私网检查。 */
    fun approve(site: String): Boolean {
        val normalized = origin(site) ?: return false
        if (normalized !in pending || temporary.size >= 8) return false
        temporary.add(normalized)
        pending.remove(normalized)
        return true
    }

    fun clear() { pending.clear(); temporary.clear() }

    private fun origin(value: String): String? = value.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && !isPrivateNetworkLiteral(it.host)
    }?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.build()?.toString()
}
