package com.radium.inkwell.core.source.js

/** 仅显式来源登录动作可获得的交互出口；后台阅读默认没有此能力，平台实现必须再校验来源作用域。 */
interface SourceScriptInteraction {
    fun browser(url: String, title: String?, await: Boolean): String
    fun notice(message: String)
}
