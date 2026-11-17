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

private class ParsedTarget(val rule: BookSourceRule, val target: WebLoginTarget?)
private val parsedTargets = java.util.concurrent.ConcurrentHashMap<String, ParsedTarget>()

fun webLoginTarget(source: BookSourceRule): WebLoginTarget? {
    parsedTargets[source.id]?.takeIf {
        it.rule.baseUrl == source.baseUrl && it.rule.loginUrl == source.loginUrl && it.rule.loginUi == source.loginUi
    }?.let { return it.target }
    val target = parseWebLoginTarget(source)
    if (parsedTargets.size >= 256) parsedTargets.clear()
    parsedTargets[source.id] = ParsedTarget(source, target)
    return target
}

private fun parseWebLoginTarget(source: BookSourceRule): WebLoginTarget? {
    val value = source.loginUrl?.trim().orEmpty()
    if (value.isEmpty()) {
        val home = source.baseUrl.toHttpUrlOrNull() ?: return null
        return if (source.loginUi.isNullOrBlank() && home.isHttps && home.username.isEmpty() && home.password.isEmpty() && !isPrivateNetworkLiteral(home.host))
            WebLoginTarget(home.toString(), partial = true, home = true) else null
    }
    if (value.length > 256 * 1024) return null
    val direct = runCatching { URI(value) }.getOrNull()?.let { uri ->
        if (uri.isAbsolute || value.startsWith("/") || value.startsWith("./") || value.startsWith("../")) value else null
    }
    val declared = direct ?: runCatching {
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
        targets.singleOrNull()
    }.getOrNull() ?: return null
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
    return WebLoginTarget(url.toString(), direct == null || !source.loginUi.isNullOrBlank() || upgraded, upgraded = upgraded)
}

/** 来源规则改域或登录站点变化时得到新作用域，旧身份不会自动迁移。 */
fun sourceAuthScope(source: BookSourceRule): SourceAuthScope? {
    val target = webLoginTarget(source)
    return SourceAuthScope.create(source.id, source.baseUrl, listOfNotNull(target?.url))
}
