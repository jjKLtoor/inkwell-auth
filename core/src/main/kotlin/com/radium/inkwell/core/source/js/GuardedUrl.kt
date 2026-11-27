package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 兼容脚本读写 HTTPS 字节流。请求仍通过既有来源 HTTP 客户端发出。 */
class GuardedUrl internal constructor(private val http: EngineJsHttp, address: String) {
    private val owner = checkNotNull(EngineJsHttp.currentInvocation())
    private val address = requireNotNull(address.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && !isPrivateNetworkLiteral(it.host)
    }) { "来源连接只支持公网 HTTPS 地址" }.toString()

    fun openConnection(): GuardedUrlConnection {
        check(owner === EngineJsHttp.currentInvocation())
        return GuardedUrlConnection(http, address, owner)
    }
}

class GuardedUrlConnection internal constructor(
    private val http: EngineJsHttp,
    private val address: String,
    private val owner: Any,
) {
    private val output = MemoryOutputShim(32)
    private val headers = linkedMapOf<String, String>()
    private var method = "GET"
    private var timeoutMs = 45_000L
    private var attempted = false
    private var response: StrResponse? = null

    private fun configurable() {
        check(owner === EngineJsHttp.currentInvocation() && !attempted)
    }

    fun setRequestMethod(value: String) {
        configurable()
        val requested = value.uppercase()
        require(requested == "GET" || requested == "POST")
        method = requested
    }

    fun setDoOutput(value: Boolean) {
        configurable()
        if (value) method = "POST"
    }

    fun setRequestProperty(name: String, value: String) {
        configurable()
        if (name.lowercase() in MANAGED_HEADERS) return
        require(name.length in 1..256 && value.length <= 8192 && (headers.size < 64 || name in headers))
        headers[name] = value
    }

    fun setConnectTimeout(value: Int) = setTimeout(value)

    fun setReadTimeout(value: Int) = setTimeout(value)

    private fun setTimeout(value: Int) {
        configurable()
        require(value >= 0)
        if (value > 0) timeoutMs = minOf(timeoutMs, value.toLong())
    }

    fun getOutputStream(): MemoryOutputShim {
        configurable()
        require(method == "POST")
        return output
    }

    private fun result(): StrResponse {
        // 缓存只属于发起这次脚本调用。失败时，读取响应字段也不能重新发请求。
        check(owner === EngineJsHttp.currentInvocation())
        response?.let { return it }
        if (attempted) throw IOException("来源连接未取得响应")
        attempted = true
        val body = if (method == "POST") {
            if (headers.keys.none { it.equals("Content-Type", true) }) headers["Content-Type"] = "application/octet-stream"
            output.toByteArray()
        } else null
        val received = http.fetchBinary(address, method, body, headers, timeoutMs)
            ?: throw IOException("来源连接未取得响应")
        require(received.bodyText.length <= MAX_SCRIPT_BUFFER) { "来源连接响应超过大小限制" }
        response = received
        return received
    }

    fun getResponseCode(): Int = result().status

    fun getHeaderField(name: String): String? = result().header(name)

    fun getInputStream(): MemoryInputShim = MemoryInputShim(result().bodyText.toByteArray(Charsets.ISO_8859_1))

    fun getErrorStream(): MemoryInputShim? = if (result().status >= 400) getInputStream() else null

    private companion object {
        val MANAGED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")
    }
}
