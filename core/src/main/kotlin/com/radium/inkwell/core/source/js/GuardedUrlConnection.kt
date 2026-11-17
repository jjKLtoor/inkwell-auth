package com.radium.inkwell.core.source.js

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.radium.inkwell.core.source.auth.SourceRequestSession

private fun checkConnectionOwner(owner: SourceRequestSession?) {
    checkMemoryOperation()
    check(SourceRequestSession.current() === owner && (owner == null || owner.active)) { "来源连接所属会话已变化或退出" }
}

/** URL 只是受控出口的地址描述；不创建 java.net.URL 或原生连接。 */
class GuardedUrlShim internal constructor(private val http: JsHttp?, private val address: String) {
    private val owner = SourceRequestSession.current()
    init {
        val parsed = requireNotNull(address.toHttpUrlOrNull()) { "来源连接地址无效" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "来源连接地址不能包含账号信息" }
    }
    fun openConnection(): GuardedUrlConnection {
        checkConnectionOwner(owner)
        return GuardedUrlConnection(http, address)
    }
    override fun toString(): String = address
}

/** 缓冲请求体只提交一次；网络、认证、DNS、重定向和取消均由现有 JsHttp / SourceHttpClient 把关。 */
class GuardedUrlConnection internal constructor(private val http: JsHttp?, private val address: String) {
    private val owner = SourceRequestSession.current()
    private var method = "GET"
    private var timeoutMs = 45_000L
    private var attempted = false
    private var closed = false
    private var response: StrResponse? = null
    private var input: MemoryInputStreamShim? = null
    private var output: MemoryOutputStreamShim? = null
    private val headers = linkedMapOf<String, String>()
    private fun configurable() { checkConnectionOwner(owner); check(!attempted && !closed) { "来源连接已提交或关闭" } }
    fun setRequestMethod(value: String) {
        configurable()
        require(value.uppercase() in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) { "来源连接请求方式不受支持" }
        method = value.uppercase()
    }
    fun setDoOutput(value: Boolean) {
        configurable()
        if (value) {
            if (method == "GET") method = "POST"
            if (output == null) output = MemoryOutputStreamShim()
        } else output = null
    }
    fun setRequestProperty(name: String, value: String) {
        configurable()
        require(name.length in 1..256 && value.length <= 8192 && (headers.size < 64 || name in headers)) { "来源连接请求头超过限制" }
        headers[name] = value
    }
    fun setConnectTimeout(value: Int) = setTimeout(value)
    fun setReadTimeout(value: Int) = setTimeout(value)
    private fun setTimeout(value: Int) {
        configurable(); require(value >= 0)
        // 兼容调用方更短的时限，但不允许超出引擎上限；此处采用较严格的整次请求预算。
        if (value > 0) timeoutMs = minOf(timeoutMs, value.toLong())
    }
    fun setInstanceFollowRedirects(value: Boolean) {
        configurable(); require(!value) { "来源连接需显式处理服务器重定向" }
    }
    fun getOutputStream(): MemoryOutputStreamShim {
        configurable()
        if (output == null) setDoOutput(true)
        return requireNotNull(output)
    }
    private fun result(): StrResponse {
        // 即使脚本库保存了 URL / 响应对象，也不能在下一来源或退出后继续复用旧身份句柄。
        checkConnectionOwner(owner)
        check(!closed) { "来源连接已关闭" }
        response?.let { return it }
        // 失败后也不能因再次读状态或响应体自动重发，尤其不能重放 POST。
        if (attempted) throw IOException("来源连接未完成，请核对原请求结果")
        attempted = true
        val body = output?.toByteArray()
        if (body != null && headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/octet-stream"
        return (http?.fetchBinary(address, method, body, headers.toMap(), timeoutMs)
            ?: throw IOException("来源连接未取得响应")).also {
            require(it.bodyText.length <= SCRIPT_BUFFER_LIMIT) { "来源连接响应超过大小限制" }
            response = it
        }
    }
    fun getResponseCode(): Int = result().status
    fun getHeaderField(name: String): String? = result().header(name)
    private fun body(): MemoryInputStreamShim = input ?: MemoryInputStreamShim(result().bodyText.toByteArray(Charsets.ISO_8859_1)).also { input = it }
    fun getInputStream(): MemoryInputStreamShim {
        if (result().status >= 400) throw IOException("来源连接返回错误状态")
        return body()
    }
    fun getErrorStream(): MemoryInputStreamShim? = if (result().status >= 400) body() else null
    fun connect() { result() }
    fun disconnect() { closed = true; input?.close(); input = null; output = null; response = null }
}
