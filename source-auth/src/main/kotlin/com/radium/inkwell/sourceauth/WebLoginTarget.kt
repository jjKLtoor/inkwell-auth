package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope

import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast.FunctionCall
import org.mozilla.javascript.ast.Name
import org.mozilla.javascript.ast.PropertyGet
import org.mozilla.javascript.ast.StringLiteral
import java.net.URI

/** 仅提取声明的网页，不执行登录脚本、表单按钮或购买操作。 */
class WebLoginTarget(val url: String, val partial: Boolean, val upgraded: Boolean = false, val home: Boolean = false)

private class ParsedTarget(val rule: BookSourceRule, val targets: List<WebLoginTarget>)
private val parsedTargets = java.util.concurrent.ConcurrentHashMap<String, ParsedTarget>()

fun webLoginTarget(source: BookSourceRule): WebLoginTarget? = webLoginTargets(source).firstOrNull()

/** 仅列出规则明确声明的静态入口，不把资源域名当作登录网页。 */
fun webLoginTargets(source: BookSourceRule): List<WebLoginTarget> {
    parsedTargets[source.id]?.takeIf {
        it.rule.baseUrl == source.baseUrl && it.rule.loginUrl == source.loginUrl && it.rule.loginUi == source.loginUi
    }?.let { return it.targets }
    val target = parseWebLoginTargets(source)
    if (parsedTargets.size >= 256) parsedTargets.clear()
    parsedTargets[source.id] = ParsedTarget(source, target)
    return target
}

private fun parseWebLoginTargets(source: BookSourceRule): List<WebLoginTarget> {
    val value = source.loginUrl?.trim().orEmpty()
    if (value.isEmpty()) {
        val home = source.baseUrl.toHttpUrlOrNull() ?: return emptyList()
        return if (source.loginUi.isNullOrBlank() && home.isHttps && home.username.isEmpty() && home.password.isEmpty() && !isPrivateNetworkLiteral(home.host))
            listOf(WebLoginTarget(home.toString(), partial = true, home = true)) else emptyList()
    }
    if (value.length > 256 * 1024) return emptyList()
    val direct = runCatching { URI(value) }.getOrNull()?.let { uri ->
        if (uri.isAbsolute || value.startsWith("/") || value.startsWith("./") || value.startsWith("../")) value else null
    }
    val declared = direct?.let(::listOf) ?: runCatching {
        val code = value.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")
        val environment = CompilerEnvirons().apply { languageVersion = Context.VERSION_ES6 }
        val tree = Parser(environment).parse(code, "login", 1)
        val targets = linkedSetOf<String>()
        tree.visit { node ->
            if (node is FunctionCall) {
                val method = node.target as? PropertyGet
                if ((method?.target as? Name)?.identifier == "java" &&
                    method.property.identifier in setOf("startBrowser", "startBrowserAwait")) {
                    (node.arguments.firstOrNull() as? StringLiteral)?.value?.let(targets::add)
                }
            }
            true
        }
        targets.toList()
    }.getOrNull() ?: return emptyList()
    if (declared.size !in 1..8) return emptyList()
    val targets = declared.mapNotNull { normalizeTarget(source, it, direct == null) }
    val base = source.baseUrl.toHttpUrlOrNull() ?: return emptyList()
    val primary = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    val foreign = targets.map { it.url.toHttpUrlOrNull()!!.newBuilder().encodedPath("/").query(null).fragment(null).build().toString() }.filter { it != primary }.distinct()
    // 候选项可有多条路径，但不能悄悄突破来源＋一个声明登录站点的认证边界。
    return if (foreign.size <= 1) targets.distinctBy { it.url } else emptyList()
}

private fun normalizeTarget(source: BookSourceRule, declared: String, scripted: Boolean): WebLoginTarget? {
    val declaredUri = runCatching { URI(declared) }.getOrNull() ?: return null
    if (declaredUri.isAbsolute && declaredUri.rawAuthority.isNullOrEmpty()) return null
    val base = source.baseUrl.toHttpUrlOrNull() ?: return null
    if (base.username.isNotEmpty() || base.password.isNotEmpty()) return null
    val original = base.resolve(declared) ?: return null
    // 旧规则的 HTTP 登录只尝试同主机标准 HTTPS，不允许明文认证，也不自动改用户规则。
    val upgraded = !original.isHttps && original.scheme == "http" && original.port == 80
    val url = if (upgraded) original.newBuilder().scheme("https").port(443).build() else original
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || isPrivateNetworkLiteral(url.host)) return null
    if (url.toString().contains("{{") || url.toString().contains("}}") || declared.contains("{{")) return null
    return WebLoginTarget(url.toString(), scripted || !source.loginUi.isNullOrBlank() || upgraded, upgraded = upgraded)
}

/** 来源规则改域或登录站点变化时得到新作用域，旧身份不会自动迁移。 */
fun sourceAuthScope(source: BookSourceRule): SourceAuthScope? {
    val targets = webLoginTargets(source)
    val base = source.baseUrl.toHttpUrlOrNull() ?: return null
    val primary = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString()
    val origins = targets.map { it.url.toHttpUrlOrNull()!!.newBuilder().encodedPath("/").query(null).fragment(null).build().toString() }.distinct().filter { it != primary }
    return SourceAuthScope.create(source.id, source.baseUrl, origins)
}
