package com.radium.inkwell.sourceauth

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JsResult
import android.widget.FrameLayout
import androidx.webkit.CookieManagerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebStorageCompat
import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 独立的短期 profile，不操作默认 CookieManager。平台原生 profile 在交互期间可能落盘，
 * 不宣称它被应用密钥加密；退出等待清理浏览数据，下次进程删除空 profile / 异常残留，且禁止系统备份。
 * 只收集已访问 URL 可见的完整 Cookie。公网 HTTPS 跳转正常跟随，独立登录窗口支持网站的跨站认证。
 * 临时网页存储在关闭时清理，读书会话仍按来源作用域导出，不接收额外站点的身份。
 */
@SuppressLint("RequiresFeature", "SetJavaScriptEnabled")
class LoginWebSession(
    context: Context,
    private val scope: SourceAuthScope,
    private val intercept: (WebResourceRequest) -> WebResourceResponse?,
    private val onError: () -> Unit,
) {
    var onPageEvent: (LoginPageEvent) -> Unit = {}
    var onDialog: ((LoginWebDialog) -> Unit)? = null
    private var pendingDialog: JsResult? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun event(value: LoginPageEvent) { main.post { if (!closed) onPageEvent(value) } }
    private fun pageFailure(reason: String) { main.post { if (!closed) { onError(); onPageEvent(LoginPageEvent.Failed(reason)) } } }
    private val navigation = LoginNavigationPolicy(scope)
    private val reportedBlocks = ConcurrentHashMap.newKeySet<String>()
    private fun blockedEvent(url: String, mainFrame: Boolean, kind: LoginResourceKind = loginResourceKind(url, mainFrame)) {
        val origin = navigation.origin(url)
        if (reportedBlocks.size < 64 && reportedBlocks.add(origin.orEmpty() + mainFrame + kind)) event(LoginPageEvent.Blocked(origin, mainFrame, kind))
    }

    private val profileName: String
    // 页面与资源分别记录。图片、脚本等请求再多，也不能挤掉登录回跳和章节入口的路径。
    private val visitedResources = ConcurrentHashMap.newKeySet<String>()
    private val visitedPages = linkedSetOf<String>() // 仅在 Main 线程读写。
    private val cookies: android.webkit.CookieManager
    private val storage: android.webkit.WebStorage
    val view: WebView
    val container: FrameLayout
    private var popup: WebView? = null
    private var sourceUserAgent: String? = null
    private var started = false
    @Volatile private var closed = false
    private var pendingCookieWrites = 0
    private var cleaning = false
    private val cleanup = CompletableDeferred<Unit>()

    init {
        check(supported())
        check(!active) // 全局仅允许一次交互登录，防止清理另一个仍在使用的短期 profile。
        cleanupOrphans()
        val store = ProfileStore.getInstance()
        profileName = PREFIX + UUID.randomUUID().toString()
        view = WebView(context)
        container = FrameLayout(context)
        try {
            WebViewCompat.setProfile(view, profileName)
            val profile = WebViewCompat.getProfile(view)
            cookies = profile.cookieManager
            storage = profile.webStorage
            profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                blockNetworkLoads = true
                allowContentAccess = false
                allowFileAccess = false
            }
            configure(view)
            container.addView(view, FrameLayout.LayoutParams(-1, -1))
            active = true
        } catch (error: Exception) {
            view.destroy()
            runCatching { store.deleteProfile(profileName) }
            throw error
        }
    }

    /** 主页面与用户主动打开的新窗口使用同一隔离 profile、域名策略和网络保护。 */
    private fun configure(browser: WebView) {
        browser.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            // 现代网页登录常依赖临时网页存储；只存在独立 profile，关闭时一并清理，不导出到引擎。
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 无来源声明时沿用系统标识；有明确声明则与后台取页保持一致，不另造浏览器配置。
            userAgentString = sourceUserAgent ?: WebSettings.getDefaultUserAgent(browser.context)
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
        }
        browser.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // 正常的内嵌认证框可能由其他域提供。仅给临时登录窗口保留浏览器的跨站 Cookie 行为，
        // 关闭时仍清理整个 profile，导出与阅读请求始终遵守已声明的来源范围。
        cookies.setAcceptThirdPartyCookies(browser, true)
        browser.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (closed || !isUserGesture || popup != null) {
                    event(LoginPageEvent.Notice("网页的新窗口未打开，请关闭当前子窗口后重试"))
                    return false
                }
                val child = WebView(view.context)
                WebViewCompat.setProfile(child, profileName)
                configure(child)
                popup = child
                this@LoginWebSession.view.visibility = View.GONE
                container.addView(child, FrameLayout.LayoutParams(-1, -1))
                event(LoginPageEvent.Loading)
                (resultMsg.obj as WebView.WebViewTransport).webView = child
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) { if (window === popup) closePopup() }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean = dialog(url, message, false, result)
            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean = dialog(url, message, true, result)
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (closed || !navigation.permits(url)) { blockedEvent(url, request.isForMainFrame, loginResourceKind(url, request.isForMainFrame, request.requestHeaders)); return blocked() }
                if (visitedResources.size < 256) visitedResources.add(url)
                return intercept(request).also { response ->
                    if (request.isForMainFrame && response != null && response.statusCode >= 400) pageFailure("网络保护阻止了登录页面，或页面返回 HTTP " + response.statusCode)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (view === popup && request.url.toString() == "about:blank") return false
                val denied = !navigation.permits(request.url.toString())
                if (denied) blockedEvent(request.url.toString(), request.isForMainFrame)
                return denied
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                rememberPage(url)
                if (browser !== (popup ?: this@LoginWebSession.view)) return
                event(LoginPageEvent.Loading)
                url.toHttpUrlOrNull()?.let { event(LoginPageEvent.Location(it.newBuilder().encodedPath("/").query(null).fragment(null).username("").password("").build().toString())) }
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                rememberPage(url)
                if (browser === (popup ?: this@LoginWebSession.view) && navigation.permits(url)) event(LoginPageEvent.Visible)
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) pageFailure("登录页面返回 HTTP " + response.statusCode)
            }
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                pageFailure("登录页面证书校验失败，已停止加载")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) pageFailure("登录页面网络错误（代码 " + error.errorCode + "），请检查网络后重试")
            }
        }
    }

    private fun dialog(url: String, message: String, confirmation: Boolean, result: JsResult): Boolean {
        val origin = url.toHttpUrlOrNull()?.newBuilder()?.encodedPath("/")?.query(null)?.fragment(null)?.username("")?.password("")?.build()?.toString()
        if (closed || origin == null || !navigation.permits(url) || pendingDialog != null || onDialog == null) { result.cancel(); return true }
        pendingDialog = result
        onDialog?.invoke(LoginWebDialog(origin, message.take(2000), confirmation) { accepted ->
            if (pendingDialog === result) {
                pendingDialog = null
                if (accepted && !closed) result.confirm() else result.cancel()
            }
        })
        return true
    }

    private fun closePopup() {
        pendingDialog?.cancel(); pendingDialog = null
        popup?.let { container.removeView(it); it.stopLoading(); it.destroy() }
        popup = null
        view.visibility = View.VISIBLE
        event(LoginPageEvent.Loading)
        view.url?.toHttpUrlOrNull()?.let { event(LoginPageEvent.Location(it.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())) }
        event(LoginPageEvent.Visible)
    }

    /** 返回先关闭登录子窗口，再返回父页历史，不把正常网页操作当成退出登录。 */
    fun goBack(): Boolean {
        if (closed) return false
        if (popup != null) { closePopup(); return true }
        if (view.canGoBack()) { view.goBack(); return true }
        return false
    }

    /** 只沿用来源明确声明的请求标识，避免登录和取页落在不同版本；不生成或修改其他浏览器指纹。 */
    fun setSourceUserAgent(value: String?) {
        check(!closed && !started && popup == null)
        require(value == null || value.length <= 2048 && value.all { it in ' '..'~' }) { "来源 User-Agent 格式不受支持" }
        sourceUserAgent = value?.takeIf { it.isNotBlank() }
        view.settings.userAgentString = sourceUserAgent ?: WebSettings.getDefaultUserAgent(view.context)
    }

    suspend fun start(url: String, restored: CookieSession?) = withContext(Dispatchers.Main) {
        check(!closed && scope.permits(url))
        started = true
        if (popup != null) closePopup()
        restored?.cookies?.forEach { cookie ->
            suspendCancellableCoroutine { continuation ->
                pendingCookieWrites++
                try {
                    val cookieOrigin = scope.originUrls.firstOrNull { cookie.matches(it.toHttpUrl().newBuilder().encodedPath(cookie.path).build()) } ?: error("Cookie origin not approved")
                    cookies.setCookie(cookieOrigin, cookie.toString()) { accepted ->
                        pendingCookieWrites--
                        if (continuation.isActive) continuation.resume(accepted)
                        if (closed) beginCleanup()
                    }
                } catch (error: Exception) {
                    pendingCookieWrites--
                    throw error
                }
            }.also { check(it) }
            check(!closed)
        }
        check(!closed)
        rememberPage(url)
        event(LoginPageEvent.Loading)
        view.loadUrl(url)
    }

    private fun rememberPage(url: String) {
        if (closed || !navigation.permits(url)) return
        visitedPages.remove(url)
        visitedPages.add(url)
        if (visitedPages.size > 64) visitedPages.remove(visitedPages.first())
    }

    suspend fun capture(): CookieSession = withContext(Dispatchers.Main) {
        check(!closed)
        val records = (visitedPages + scope.originUrl + visitedResources).flatMap { url ->
            CookieManagerCompat.getCookieInfo(cookies, url).mapNotNull { parseWebViewCookie(url.toHttpUrl(), it) }
        }
        // 临时允许的网站可能持有自己的账号 Cookie，不能顺手导出到原来源的持久认证。
        val retained = records.filter { cookie -> scope.originUrls.any { cookie.matches(it.toHttpUrl().newBuilder().encodedPath(cookie.path).build()) } }
        // 仅调试包记录保存前后的作用域数量；不记录 Cookie 名称、值、路径或页面地址。
        // 这样可以定位跳转到另一子域后材料被过滤的情况，而不扩大实际认证发送范围。
        if (view.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val report = org.json.JSONObject().apply {
                put("capturedAt", System.currentTimeMillis())
                put("capturedCount", records.size)
                put("retainedCount", retained.size)
                put("groups", org.json.JSONArray().apply {
                    records.groupBy { Triple(it.domain, it.hostOnly, it in retained) }.forEach { (key, rows) ->
                        put(org.json.JSONObject().apply {
                            put("domain", key.first); put("hostOnly", key.second)
                            put("retained", key.third); put("count", rows.size)
                        })
                    }
                })
            }
            val directory = view.context.noBackupFilesDir
            withContext(Dispatchers.IO) {
                try { java.io.File(directory, "auth-capture-scope-diagnostic.json").writeText(report.toString()) }
                catch (_: java.io.IOException) { /* 调试记录失败不能影响用户保存会话。 */ }
            }
        }
        CookieSession(retained, allowWebResources = true)
    }

    /** 当前进程不能删除已加载的 profile，先销毁视图并等待其全部浏览数据清理完成。 */
    fun close(): Deferred<Unit> {
        if (closed) return cleanup
        closed = true
        pendingDialog?.cancel(); pendingDialog = null; onDialog = null
        popup?.let { container.removeView(it); it.stopLoading(); it.destroy() }; popup = null
        reportedBlocks.clear()
        try {
            view.stopLoading()
            view.destroy()
            beginCleanup()
        } catch (error: Exception) {
            active = false
            cleanup.completeExceptionally(error)
            throw error
        }
        return cleanup
    }

    private fun beginCleanup() {
        // 恢复 Cookie 的异步回调可能晚于用户取消；必须等写入完成再清理，不能让迟到写入复活会话。
        if (cleaning || pendingCookieWrites != 0) return
        cleaning = true
        try {
            WebStorageCompat.deleteBrowsingData(storage) {
                cleanedProfiles.add(profileName)
                active = false
                cleanup.complete(Unit)
            }
        } catch (error: Exception) {
            active = false
            cleanup.completeExceptionally(error)
            onError()
        }
    }

    companion object {
        private const val PREFIX = "inkwell_auth_temp_"
        private var active = false // 所有读写均在 Main。
        private val cleanedProfiles = mutableSetOf<String>()
        suspend fun awaitIdle() {
            kotlinx.coroutines.withTimeout(5000) { while (active) kotlinx.coroutines.delay(25) }
        }

        fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        /** 进入会话页及退出时调用，清理上次进程意外终止的原生临时材料。只在 Main 调用。 */
        fun cleanupOrphans() {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            check(!active)
            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith(PREFIX) && it !in cleanedProfiles }.forEach { check(store.deleteProfile(it)) }
        }
        private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
    }
}
