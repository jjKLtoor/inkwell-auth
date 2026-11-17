package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import org.mozilla.javascript.CompilerEnvirons
import org.mozilla.javascript.Context
import org.mozilla.javascript.Parser
import org.mozilla.javascript.ast.*
import java.net.URI

class SourceLoginControl(val name: String, val type: String, val action: String?, val initial: String)
class SourceLoginForm(val controls: List<SourceLoginControl>, val script: String, val hasSubmit: Boolean)

/** 只读语法树中的字面量，不通过 eval 解析界面，配置里的表达式不能在进页时执行。 */
fun parseSourceLoginForm(source: BookSourceRule): SourceLoginForm? {
    val raw = source.loginUi?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    require(raw.length <= 256 * 1024) { "登录表单过大" }
    val env = CompilerEnvirons().apply { languageVersion = Context.VERSION_ES6 }
    val root = Parser(env).parse("(" + raw + ")", "login-ui", 1)
    val expression = (root.firstChild as? ExpressionStatement)?.expression
    val array = ((expression as? ParenthesizedExpression)?.expression ?: expression) as? ArrayLiteral
        ?: error("暂不支持动态登录表单")
    require(array.elements.size in 1..100) { "登录表单数量不受支持" }
    fun text(node: AstNode?): String? = when (node) { is StringLiteral -> node.value; else -> null }
    val controls = array.elements.map { node ->
        val obj = node as? ObjectLiteral ?: error("登录控件必须为静态配置")
        val properties: Map<String, AstNode> = obj.elements.associate { element ->
            val prop = element as? ObjectProperty ?: error("不支持动态登录属性")
            require(!prop.isMethod) { "不支持登录控件方法定义" }
            val key = when (val left = prop.key) { is Name -> left.identifier; is StringLiteral -> left.value; else -> error("登录属性不受支持") }
            key to prop.value
        }
        val name = text(properties["name"]) ?: error("登录控件缺少名称")
        val type = text(properties["type"])?.lowercase() ?: "edit"
        require(name.length <= 256 && type in setOf("button", "text", "edit", "password")) { "登录控件类型暂不支持" }
        val action = properties["action"]?.let { text(it) ?: error("暂不支持动态按钮动作") }
        require((action?.length ?: 0) <= 32768) { "登录动作过大" }
        SourceLoginControl(name, type, action, text(properties["default"]).orEmpty())
    }
    val login = source.loginUrl?.trim().orEmpty()
    val direct = runCatching { URI(login) }.getOrNull()?.let { it.scheme in setOf("http", "https") } == true || login.startsWith("/")
    val script = if (direct) "" else login.removePrefix("@js:").removePrefix("<js>").removeSuffix("</js>")
    require(script.length <= 256 * 1024) { "登录脚本过大" }
    var submit = false
    if (script.isNotBlank()) Parser(env).parse(script, "login-action", 1).visit { node ->
        if (node is FunctionNode && node.name == "login" && node.enclosingFunction == null) submit = true
        true
    }
    return SourceLoginForm(controls, script, submit)
}
