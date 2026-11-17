package com.radium.inkwell.sourceauth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.auth.SourceRequestSession
import com.radium.inkwell.core.source.isPrivateNetworkLiteral
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

/** 后台取页的专用 profile，串行使用并在前后清空；绝不操作默认或交互登录 profile。 */
@SuppressLint("RequiresFeature", "SetJavaScriptEnabled")
class AuthenticatedWebViewProfile private constructor(
    context: Context,
    private val session: SourceRequestSession,
    userAgent: String?,
    private val guard: (WebResourceRequest) -> WebResourceResponse?,
) {
    val view = WebView(context)
    private val profile = try {
        check(LoginWebSession.supported())
        WebViewCompat.setProfile(view, PROFILE)
        WebViewCompat.getProfile(view)
    } catch (error: Exception) { view.destroy(); throw error }
    private val cookies = profile.cookieManager
    private val visited = ConcurrentHashMap.newKeySet<String>()
    private var original: List<okhttp3.Cookie> = emptyList()
    private var pendingWrites = 0
    private val proxyJob = SupervisorJob()
    private val anonymous = SourceHttpClient(OkHttpClient.Builder().followSslRedirects(false).build())
    @Volatile private var closed = false
    @Volatile var failure: String? = null
        private set

    init {
        profile.serviceWorkerController.serviceWorkerWebSettings.apply {
            blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
        }
        cookies.setAcceptThirdPartyCookies(view, false)
        view.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            // Chromium 的重定向不会逐跳经过拦截器；全部网络经受控客户端，禁止原生直连。
            blockNetworkLoads = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadsImagesAutomatically = false; blockNetworkImage = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
            userAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
        }
    }

    suspend fun prepare() {
        try {
            // 写 Cookie 的平台回调完成后才能清理，不能让取消后的迟到写入污染下一来源。
            withContext(NonCancellable) { withTimeout(5000) {
                clearData()
                original = session.cookiesForRendering()
                for (cookie in original) {
                    if (cookie.expiresAt <= System.currentTimeMillis()) continue
                    val origin = session.scope.originUrls.firstOrNull { cookie.matches(it.toHttpUrl().newBuilder().encodedPath(cookie.path).build()) }
                        ?: throw IOException("渲染 Cookie 超出来源认证范围")
                    suspendCancellableCoroutine<Unit> { cont -> cookies.setCookie(origin, cookie.toString()) { ok ->
                        if (cont.isActive) { if (ok) cont.resume(Unit) else cont.cancel(IOException("无法恢复渲染会话")) }
                    } }
                }
            } }
        } catch (error: Exception) { poisoned = true; throw error }
        currentCoroutineContext().ensureActive()
        check(session.active)
    }

    /** 只有获准 origin 使用 Chromium 的 Cookie；其他站点的 GET 资源通过无身份客户端获取。 */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (closed || !session.active) return denied("来源会话已退出")
        if (session.scope.permits(url)) {
            if (visited.size < 256) visited.add(url)
            guard(request)?.let { return it }
        }
        val approved = session.scope.permits(url)
        if (!approved && request.isForMainFrame) return denied("页面跳转超出来源认证范围")
        val parsed = url.toHttpUrlOrNull()
        val destination = request.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        if (parsed == null || !parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || isPrivateNetworkLiteral(parsed.host) ||
            (request.method != "GET" && request.method != "HEAD") || (!approved && destination in setOf("iframe", "frame", "document", "font"))) return denied(if (approved) "隔离渲染暂不支持此网页请求方式" else null)
        return try {
            // 明确移除协程身份，不复制 Cookie、Authorization 或来源自定义认证头，不记录资源完整地址。
            val page = runBlocking(proxyJob + SourceRequestSession.binding(null)) {
                val headers = anonymousResourceHeaders(request.requestHeaders).toMutableMap()
                // 空 Cookie 头也必须显式给出，避免受控客户端的临时 CookieJar 代为补充身份。
                headers["Cookie"] = if (approved) cookies.getCookie(url).orEmpty() else ""
                val response = anonymous.fetch(url, method = request.method, headers = headers, throwOnHttpError = false, followRedirects = false)
                if (approved) {
                    for (value in response.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }) {
                        val parsedCookie = okhttp3.Cookie.parse(parsed, value) ?: continue
                        withContext(Dispatchers.Main) {
                            if (!closed && session.active) suspendCancellableCoroutine<Unit> { cont ->
                                pendingWrites++
                                cookies.setCookie(url, parsedCookie.toString()) {
                                    pendingWrites--
                                    if (cont.isActive) cont.resume(Unit)
                                }
                            }
                        }
                    }
                }
                response
            }
            if (page.statusCode in 300..399) return denied(if (approved) "隔离渲染暂不支持服务器重定向，请核对来源页面地址" else null)
            val type = page.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.firstOrNull()?.substringBefore(';') ?: "text/plain"
            if (!(type.startsWith("text/") || type.contains("javascript") || type.contains("json") || type.contains("xml"))) return denied()
            val headers = page.headers.filterKeys { it.lowercase() !in setOf("set-cookie", "content-length", "content-encoding") }.mapValues { it.value.joinToString(",") }
            WebResourceResponse(type, "UTF-8", page.statusCode, "Resource", headers, ByteArrayInputStream(page.bodyText.toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) { denied() }
    }

    fun blocksNavigation(request: WebResourceRequest): Boolean {
        if (closed || !session.active || !session.scope.permits(request.url.toString())) {
            failure = "页面跳转超出来源认证范围"
            return true
        }
        return false
    }

    suspend fun capture() {
        if (!session.active) throw IOException("来源会话已退出")
        val current = visited.flatMap { url ->
            val parsed = url.toHttpUrl()
            CookieManagerCompat.getCookieInfo(cookies, url).mapNotNull { parseWebViewCookie(parsed, it) }
        }
        session.mergeRenderedCookies(original, current, visited.map { it.toHttpUrl() })
    }

    private fun denied(reason: String? = null): WebResourceResponse {
        if (reason != null) failure = reason
        return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    private suspend fun clearData() = suspendCancellableCoroutine<Unit> { cont ->
        WebStorageCompat.deleteBrowsingData(profile.webStorage) { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun close() = withContext(NonCancellable) {
        closed = true; proxyJob.cancel(); view.stopLoading(); view.destroy()
        try { withTimeout(5000) { while (pendingWrites > 0) delay(10); clearData() } }
        catch (error: Exception) { poisoned = true; throw IOException("隔离渲染清理未完成，请重启应用后重试", error) }
    }

    companion object {
        private const val PROFILE = "inkwell_auth_render_v1"
        private val mutex = Mutex()
        // 清理或平台 Cookie 写入超时后不再复用此进程的 profile，避免迟到写入混入下一来源。
        @Volatile private var poisoned = false
        suspend fun <T> use(context: Context, session: SourceRequestSession, userAgent: String?, guard: (WebResourceRequest) -> WebResourceResponse?, block: suspend (AuthenticatedWebViewProfile) -> T): T = mutex.withLock {
            if (poisoned) throw IOException("隔离渲染环境未完成清理，请重启应用后重试")
            withContext(Dispatchers.Main) {
                val isolated = AuthenticatedWebViewProfile(context, session, userAgent, guard)
                try { block(isolated) } finally { isolated.close() }
            }
        }
    }
}

/** 公网子资源仅带浏览器的普通协商信息，不扩展来源凭据发送范围。 */
fun anonymousResourceHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
    it.lowercase() in setOf("user-agent", "accept", "accept-language", "referer", "origin", "cache-control")
}
