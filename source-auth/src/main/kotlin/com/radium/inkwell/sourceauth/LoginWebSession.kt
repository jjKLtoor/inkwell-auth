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
 * 只收集已访问 URL 可见的完整 Cookie，localStorage 只在临时 profile 使用，不导出或跨进程恢复；额外 SSO / 第三方 Cookie 不自动放行。
 */
@SuppressLint("RequiresFeature", "SetJavaScriptEnabled")
class LoginWebSession(
    context: Context,
    private val scope: SourceAuthScope,
    private val intercept: (WebResourceRequest) -> WebResourceResponse?,
    private val onError: () -> Unit,
) {
    var onPageEvent: (LoginPageEvent) -> Unit = {}
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun event(value: LoginPageEvent) { main.post { if (!closed) onPageEvent(value) } }
    private fun pageFailure(reason: String) { main.post { if (!closed) { onError(); onPageEvent(LoginPageEvent.Failed(reason)) } } }
    private val navigation = LoginNavigationPolicy(scope)
    private val reportedBlocks = ConcurrentHashMap.newKeySet<String>()
    private fun blockedEvent(url: String, mainFrame: Boolean, kind: LoginResourceKind = loginResourceKind(url, mainFrame)) {
        val origin = navigation.recordBlocked(url)
        if (reportedBlocks.size < 64 && reportedBlocks.add(origin.orEmpty() + mainFrame + kind)) event(LoginPageEvent.Blocked(origin, mainFrame, kind))
    }

    /** 用户逐个确认后只在本次网页登录生效；私网/DNS 防护和阅读引擎认证边界保持原样。 */
    suspend fun approveTemporaryOrigin(origin: String): Boolean {
        val url = origin.toHttpUrlOrNull() ?: return false
        if (withContext(Dispatchers.IO) { com.radium.inkwell.core.source.isPrivateNetworkHost(url.host) }) return false
        return withContext(Dispatchers.Main) { !closed && navigation.approve(origin) }
    }

    private val profileName: String
    private val visited = ConcurrentHashMap.newKeySet<String>()
    private val cookies: android.webkit.CookieManager
    private val storage: android.webkit.WebStorage
    val view: WebView
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
        try {
            WebViewCompat.setProfile(view, profileName)
            val profile = WebViewCompat.getProfile(view)
            cookies = profile.cookieManager
            storage = profile.webStorage
            visited.add(scope.originUrl)
            profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                blockNetworkLoads = true
                allowContentAccess = false
                allowFileAccess = false
            }
            view.settings.apply {
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
                // 使用当前系统 WebView 的真实标识，避免旧版固定 Chrome 标识与内核不一致。
                userAgentString = WebSettings.getDefaultUserAgent(context)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            cookies.setAcceptThirdPartyCookies(view, false)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    if (closed || !navigation.permits(url)) { blockedEvent(url, request.isForMainFrame, loginResourceKind(url, request.isForMainFrame, request.requestHeaders)); return blocked() }
                    if (visited.size < 256) visited.add(url)
                    return intercept(request).also { response ->
                        if (request.isForMainFrame && response != null && response.statusCode >= 400) pageFailure("网络保护阻止了登录页面，或页面返回 HTTP " + response.statusCode)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val denied = !navigation.permits(request.url.toString())
                    if (denied) blockedEvent(request.url.toString(), request.isForMainFrame)
                    return denied
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { event(LoginPageEvent.Loading) }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (navigation.permits(url)) event(LoginPageEvent.Visible)
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
            active = true
        } catch (error: Exception) {
            view.destroy()
            runCatching { store.deleteProfile(profileName) }
            throw error
        }
    }

    suspend fun start(url: String, restored: CookieSession?) = withContext(Dispatchers.Main) {
        check(!closed && scope.permits(url))
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
        visited.add(scope.originUrl)
        visited.add(url)
        check(!closed)
        event(LoginPageEvent.Loading)
        view.loadUrl(url)
    }

    suspend fun capture(): CookieSession = withContext(Dispatchers.Main) {
        check(!closed)
        val records = visited.flatMap { url ->
            CookieManagerCompat.getCookieInfo(cookies, url).mapNotNull { parseWebViewCookie(url.toHttpUrl(), it) }
        }.distinctBy { "${it.name}\u0000${it.domain}\u0000${it.path}" }
        // 临时允许的网站可能持有自己的账号 Cookie，不能顺手导出到原来源的持久认证。
        CookieSession(records.filter { cookie -> scope.originUrls.any { cookie.matches(it.toHttpUrl().newBuilder().encodedPath(cookie.path).build()) } })
    }

    /** 当前进程不能删除已加载的 profile，先销毁视图并等待其全部浏览数据清理完成。 */
    fun close(): Deferred<Unit> {
        if (closed) return cleanup
        closed = true
        navigation.clear()
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
