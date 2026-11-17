package com.radium.inkwell.sourceauth

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 优先展示影响布局的资源；仅分析类型，不记录完整请求地址或查询参数。 */
enum class LoginResourceKind(val priority: Int, val label: String) {
    NAVIGATION(0, "页面跳转"), STYLE(1, "网页样式"), SCRIPT(2, "页面脚本"),
    IMAGE(3, "网页图片"), OTHER(4, "其他资源"),
}

fun loginResourceKind(url: String, mainFrame: Boolean, headers: Map<String, String> = emptyMap()): LoginResourceKind {
    if (mainFrame) return LoginResourceKind.NAVIGATION
    val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
    val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
    return when {
        destination == "style" || path.endsWith(".css") -> LoginResourceKind.STYLE
        destination == "script" || path.endsWith(".js") -> LoginResourceKind.SCRIPT
        destination == "image" || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".webp") -> LoginResourceKind.IMAGE
        else -> LoginResourceKind.OTHER
    }
}
