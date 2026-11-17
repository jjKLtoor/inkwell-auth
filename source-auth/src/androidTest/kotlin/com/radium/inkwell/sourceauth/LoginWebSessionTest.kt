package com.radium.inkwell.sourceauth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.CookieManagerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用实际 Chromium profile 和完整 Cookie API；只做本地夹具，不访问真实账号或网站。 */
@RunWith(AndroidJUnit4::class)
class LoginWebSessionTest {
    @Test fun 跨站页面自动加载但额外站点自己的Cookie不导出且关闭后清理() = runBlocking {
        withContext(Dispatchers.Main) {
            assumeTrue(LoginWebSession.supported())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = requireNotNull(SourceAuthScope.create("temporary-fixture", "https://example.com/"))
            val extra = "https://cdn.example.test/"
            val events = java.util.concurrent.ConcurrentLinkedQueue<LoginPageEvent>()
            val session = LoginWebSession(context, scope, { request ->
                val body = if (request.url.host == "cdn.example.test") "<html><script>document.cookie='third=fixture; Path=/; Secure; Max-Age=3600'</script>temporary site</html>" else "<html>fixture</html>"
                android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(body.toByteArray()))
            }, {})
            session.onPageEvent = { events.add(it) }
            val profile = WebViewCompat.getProfile(session.view)
            try {
                session.view.loadUrl(extra)
                kotlinx.coroutines.withTimeout(10000) {
                    while (CookieManagerCompat.getCookieInfo(profile.cookieManager, extra).none { it.startsWith("third=") }) kotlinx.coroutines.delay(100)
                }
                assertTrue(events.none { it is LoginPageEvent.Blocked })
                assertTrue(session.capture().cookies.none { it.name == "third" })
                assertFalse(scope.permits(extra))
                session.close().await()
                assertTrue(CookieManagerCompat.getCookieInfo(profile.cookieManager, extra).isEmpty())
            } finally { session.close().await() }
        }
    }

    @Test fun 主页面HTTP错误与危险私网跳转均提供明确事件() = runBlocking {
        withContext(Dispatchers.Main) {
            assumeTrue(LoginWebSession.supported())
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = requireNotNull(SourceAuthScope.create("error-fixture", "https://example.com/"))
            val events = java.util.concurrent.ConcurrentLinkedQueue<LoginPageEvent>()
            val session = LoginWebSession(context, scope, { request ->
                if (request.url.path == "/error") android.webkit.WebResourceResponse("text/html", "UTF-8", 503, "Unavailable", emptyMap(), java.io.ByteArrayInputStream("fixture error".toByteArray()))
                else android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream("<html><script>location.href='https://127.0.0.1/member?token=fixture-secret'</script></html>".toByteArray()))
            }, {})
            session.onPageEvent = { events.add(it) }
            try {
                session.start("https://example.com/error", null)
                kotlinx.coroutines.withTimeout(10000) {
                    while (events.none { it is LoginPageEvent.Failed && it.reason.contains("503") }) kotlinx.coroutines.delay(100)
                }
                session.start("https://example.com/redirect", null)
                kotlinx.coroutines.withTimeout(10000) {
                    while (events.none { it is LoginPageEvent.Blocked && it.mainFrame }) kotlinx.coroutines.delay(100)
                }
                val blocked = events.filterIsInstance<LoginPageEvent.Blocked>().first()
                assertEquals(null, blocked.origin)
                assertFalse(events.toString().contains("fixture-secret"))
            } finally { session.close().await() }
        }
    }

    @Test fun 网页表单提交后采集加密恢复并退出清理() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = "https://localhost:18443/"
        val scope = requireNotNull(SourceAuthScope.create(origin + "#form-fixture", origin))
        val store = EncryptedSessionStore(java.io.File(context.noBackupFilesDir, "form-fixture"), KeystoreSessionCipher())
        val phase = InstrumentationRegistry.getArguments().getString("authRestart")
        val pidFile = java.io.File(context.noBackupFilesDir, "form-fixture-process")
        if (phase == "restore") {
            assertTrue(pidFile.readText() != android.os.Process.myPid().toString())
            val cert = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
            val tls = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(cert).build()
            val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            okhttp3.mockwebserver.MockWebServer().use { server ->
                server.useHttps(tls.sslSocketFactory(), false)
                server.start(18443)
                val client = okhttp3.OkHttpClient.Builder()
                    .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
                val sessions = PersistentSourceSessions(store)
                val engine = com.radium.inkwell.core.source.BookSourceEngine(
                    com.radium.inkwell.core.source.SourceHttpClient(client, allowPrivateNetwork = true),
                    sessions = sessions,
                )
                val source = com.radium.inkwell.core.source.BookSourceRule(scope.sourceId, searchUrl = "/authorized")
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("authorized fixture"))
                assertEquals("authorized fixture", engine.fetchSearchPage(source, "fixture").bodyText)
                assertEquals("form_session=fixture", server.takeRequest().getHeader("Cookie"))
                sessions.logout(scope)
                server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("logged out"))
                engine.fetchSearchPage(source, "fixture")
                assertEquals(null, server.takeRequest().getHeader("Cookie"))
                assertEquals(null, store.load(scope))
                pidFile.delete()
            }
            return@runBlocking
        }
        store.clear(scope)
        withContext(Dispatchers.Main) {
            assumeTrue(LoginWebSession.supported())
            val session = LoginWebSession(context, scope, { request ->
                // 由拦截器提供受控 HTTPS 页面，不访问真实账号或站点。
                val html = if (request.url.path == "/signed") {
                    "<html><script>document.cookie='form_session=fixture; Path=/; Secure; Max-Age=3600'</script>signed</html>"
                } else {
                    "<html><form action='/signed'><input name='fixture' value='local'><button type='submit'>登录</button></form></html>"
                }
                android.webkit.WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(html.toByteArray()))
            }, { error("fixture page failed") })
            try {
                session.start(origin + "login", null)
                kotlinx.coroutines.withTimeout(15000) {
                    while (true) {
                        val ready = kotlinx.coroutines.suspendCancellableCoroutine<String> { continuation ->
                            session.view.evaluateJavascript("document.querySelector('form') != null") { continuation.resumeWith(Result.success(it)) }
                        }
                        if (ready == "true") break
                        kotlinx.coroutines.delay(100)
                    }
                }
                session.view.evaluateJavascript("document.querySelector('form').submit()", null)
                val captured = kotlinx.coroutines.withTimeout(15000) {
                    var value = session.capture()
                    while (value.cookies.none { it.name == "form_session" }) {
                        kotlinx.coroutines.delay(100)
                        value = session.capture()
                    }
                    value
                }
                store.save(scope, captured)
                val restored = requireNotNull(store.load(scope))
                assertTrue(restored.cookies.any { it.name == "form_session" && it.value == "fixture" })
                session.close().await()
                if (phase == "prepare") pidFile.writeText(android.os.Process.myPid().toString())
                else { store.clear(scope); assertEquals(null, store.load(scope)) }
            } finally { session.close().await(); if (phase != "prepare") store.clear(scope) }
        }
    }

    @Test fun 临时profile与默认profile隔离且Cookie属性在采集后保留() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = requireNotNull(SourceAuthScope.create("fixture-one", "https://example.com/"))
        withContext(Dispatchers.Main) {
            assumeTrue(LoginWebSession.supported())
            var session: LoginWebSession? = null
            try {
                session = LoginWebSession(instrumentation.targetContext, scope, { null }, { error("unexpected page error") })
                val profile = WebViewCompat.getProfile(session.view)
                val profileName = profile.name
                // 原生 Cookie API 写入测试材料，不依赖外部网络；用 suspend 回调确保写入完成。
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    profile.cookieManager.setCookie(scope.originUrl, "auth_fixture=local-only; Path=/; Secure; HttpOnly; Max-Age=3600") {
                        continuation.resumeWith(Result.success(Unit))
                    }
                }
                val info = CookieManagerCompat.getCookieInfo(profile.cookieManager, scope.originUrl)
                assertEquals(1, info.size)
                assertTrue(info.single().contains("HttpOnly", ignoreCase = true))
                assertTrue(requireNotNull(parseWebViewCookie(scope.originUrl.toHttpUrl(), info.single())).hostOnly)
                val captured = session.capture().cookies.single()
                assertTrue(captured.hostOnly && captured.httpOnly && captured.secure && captured.persistent)
                assertFalse(android.webkit.CookieManager.getInstance().getCookie(scope.originUrl).orEmpty().contains("auth_fixture"))
                session.close().await()
                assertTrue(CookieManagerCompat.getCookieInfo(profile.cookieManager, scope.originUrl).isEmpty())
                // 当前进程保留已清空的 profile，重启后才能删除；不能把销毁视图等同于删除数据。
                assertTrue(ProfileStore.getInstance().allProfileNames.contains(profileName))
                LoginWebSession.cleanupOrphans()
                session = null
            } finally { session?.close() }
        }
    }
}
