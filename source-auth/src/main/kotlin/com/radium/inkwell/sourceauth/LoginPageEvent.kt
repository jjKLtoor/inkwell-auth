package com.radium.inkwell.sourceauth

/** 只传状态和经过裁剪的 origin，不把完整网址、查询参数或网页内容写入日志。 */
sealed interface LoginPageEvent {
    data object Loading : LoginPageEvent
    data object Visible : LoginPageEvent
    data class Failed(val reason: String) : LoginPageEvent
    data class Blocked(val origin: String?, val mainFrame: Boolean, val kind: LoginResourceKind = if (mainFrame) LoginResourceKind.NAVIGATION else LoginResourceKind.OTHER) : LoginPageEvent
}
