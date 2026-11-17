package com.radium.inkwell.sourceauth

/** 只在内存传递网页交互请求，文案可能含私密信息，不输出到日志。 */
class LoginWebDialog(val origin: String, val message: String, val confirmation: Boolean, val respond: (Boolean) -> Unit) {
    override fun toString(): String = "LoginWebDialog(redacted)"
}
