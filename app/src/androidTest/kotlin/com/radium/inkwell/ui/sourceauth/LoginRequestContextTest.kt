package com.radium.inkwell.ui.sourceauth

import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.sourceauth.LoginWebSession
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 本地构造响应，核对真实 Chromium 发出的登录请求；不登录真实网站。 */
class LoginRequestContextTest {
    @Test fun 跨站认证框的Cookie仅在临时浏览器内可用() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = requireNotNull(SourceAuthScope.create("cross-site-fixture", "https://example.com/"))
            val frameUrl = "https://auth.example.test/"
            fun newSession() = LoginWebSession(context, scope, { request ->
                val body = if (request.url.host == "auth.example.test")
                    "<html><script>document.cookie='fixture=value; Secure; SameSite=None; Path=/; Max-Age=60';parent.postMessage('ready','https://example.com')</script></html>"
                else "<html><script>addEventListener('message',e=>{if(e.origin==='https://auth.example.test')document.body.dataset.frameReady='true'})</script><iframe src='$frameUrl'></iframe></html>"
                WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(body.toByteArray()))
            }, {})
            suspend fun waitForFrame(session: LoginWebSession) = withTimeout(10000) {
                while (true) {
                    val htmlReady = suspendCancellableCoroutine { cont ->
                        session.view.evaluateJavascript("document.body?.dataset.frameReady==='true'") {
                            if (cont.isActive) cont.resumeWith(Result.success(it == "true"))
                        }
                    }
                    if (htmlReady) break
                    delay(80)
                }
            }
            val owned = newSession()
            val manager = WebViewCompat.getProfile(owned.view).cookieManager
            fun frameHasCookie() = CookieManagerCompat.getCookieInfo(manager, frameUrl).any { it.startsWith("fixture=value") }
            try {
                assertTrue(manager.acceptThirdPartyCookies(owned.view))
                owned.start(scope.originUrl, null)
                waitForFrame(owned)
                assertTrue(frameHasCookie())
                assertTrue(owned.capture().cookies.isEmpty())
            } finally { owned.close().await() }
            assertFalse(frameHasCookie())

            val next = newSession()
            try { assertTrue(CookieManagerCompat.getCookieInfo(WebViewCompat.getProfile(next.view).cookieManager, frameUrl).isEmpty()) }
            finally { next.close().await() }
        }
    }

    @Test fun 登录请求沿用来源声明且未声明时保留系统标识() = runBlocking {
        withContext(Dispatchers.Main) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = requireNotNull(SourceAuthScope.create("ua-fixture", "https://example.com/"))
            for (declared in listOf("Inkwell-Fixture-UA", null)) {
                val actual = AtomicReference<String>()
                val owned = LoginWebSession(context, scope, { request ->
                    actual.set(request.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value)
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<html>fixture</html>".toByteArray()))
                }, {})
                try {
                    owned.setSourceUserAgent(declared)
                    owned.start(scope.originUrl, null)
                    withTimeout(10000) { while (actual.get() == null) delay(50) }
                    assertEquals(declared ?: WebSettings.getDefaultUserAgent(context), actual.get())
                    try { owned.setSourceUserAgent("changed"); fail("不能在同一登录会话中切换请求标识") }
                    catch (_: IllegalStateException) { }
                } finally { owned.close().await() }
            }
        }
    }
}
