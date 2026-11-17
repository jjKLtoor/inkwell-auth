package com.radium.inkwell.data.source

import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.sourceauth.*
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test

/** 真正执行 Chromium 的 Cookie 恢复与 DOM 搜索，响应为本地构造，不使用站点账号。 */
class AuthenticatedRenderingTest {
    @Test fun 同站POST子请求被阻止但不使已取得的主文档整体失败() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "render-post-fixture-" + UUID.randomUUID())
        val sessions = PersistentSourceSessions(EncryptedSessionStore(root, KeystoreSessionCipher()))
        val source = BookSourceRule.fromJson("""{"bookSourceUrl":"https://example.com/#post-fixture","searchUrl":"/search,{\"webView\":true}","ruleSearch":{"bookList":"li","name":"a@text","bookUrl":"a@href"}}""")
        val scope = requireNotNull(sourceAuthScope(source))
        val postRequests = java.util.concurrent.atomic.AtomicInteger()
        val html = """<html><body><li><a href="/book">fixture</a></li><script>
            fetch('/telemetry', {method:'POST', body:'fixture'}).catch(function(){});
            </script></body></html>"""
        val renderer = WebViewPageRenderer(context, authenticatedGuard = { { request ->
            when (request.url.path) {
                "/search" -> WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray()))
                "/telemetry" -> { postRequests.incrementAndGet(); null }
                else -> WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
        } })
        try {
            sessions.replace(scope, CookieSession(listOf(Cookie.Builder().name("sid").value("fixture")
                .hostOnlyDomain("example.com").path("/").secure().expiresAt(System.currentTimeMillis() + 3600000).build())))
            val engine = BookSourceEngine(SourceHttpClient(), renderer = renderer, sessions = sessions)
            assertEquals("fixture", engine.search(source, "fixture").items.single().title)
            assertTrue("夹具必须实际发起 POST，不能只测静态 DOM", postRequests.get() > 0)

            // 只区分主文档与子请求，不放开 POST、跨来源跳转或凭据作用域。
            withContext(Dispatchers.Main) {
                AuthenticatedWebViewProfile.use(context, sessions.open(scope), null, { null }) { isolated ->
                    isolated.prepare()
                    fun request(main: Boolean, url: String = "https://example.com/telemetry") = object : android.webkit.WebResourceRequest {
                        override fun getUrl() = android.net.Uri.parse(url)
                        override fun isForMainFrame() = main
                        override fun isRedirect() = false
                        override fun hasGesture() = false
                        override fun getMethod() = "POST"
                        override fun getRequestHeaders() = emptyMap<String, String>()
                    }
                    assertEquals(403, isolated.intercept(request(false))!!.statusCode)
                    assertNull(isolated.failure)
                    assertTrue(isolated.blocksNavigation(request(false, "https://outside.example/")))
                    assertNull(isolated.failure)
                    assertEquals(403, isolated.intercept(request(true))!!.statusCode)
                    assertTrue(isolated.failure.orEmpty().contains("请求方式"))
                }
            }
        } finally { sessions.logout(scope); root.delete() }
    }

    @Test fun 保存后搜索动态页面且同域来源之间和默认浏览器隔离() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "render-fixture-" + UUID.randomUUID())
        val store = EncryptedSessionStore(root, KeystoreSessionCipher())
        val sessions = PersistentSourceSessions(store)
        val scopes = mutableListOf<SourceAuthScope>()
        val html = """<html><body><ul id="results"></ul><script>
            var match = document.cookie.match(/(?:^|; )sid=([^;]*)/);
            if(match)document.getElementById('results').innerHTML='<li><a href="/book">'+match[1]+'</a></li>';
            document.cookie='rotated=yes; Path=/; Secure; Max-Age=3600';
            </script></body></html>"""
        val renderer = WebViewPageRenderer(context, authenticatedGuard = { { request ->
            if(request.url.host == "example.com") WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(html.toByteArray())) else null
        } })
        val engine = BookSourceEngine(SourceHttpClient(), renderer = renderer, sessions = sessions)
        val defaultBefore = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie("https://example.com/") }
        try {
            for (id in listOf("accountA", "accountB", "accountA")) {
                val source = BookSourceRule.fromJson("""{"bookSourceUrl":"https://example.com/#$id","searchUrl":"/search,{\"webView\":true}","ruleSearch":{"bookList":"li","name":"a@text","bookUrl":"a@href"}}""")
                val scope = requireNotNull(sourceAuthScope(source))
                scopes.add(scope)
                if (sessions.savedSnapshot(scope) == null) sessions.replace(scope, CookieSession(listOf(
                    Cookie.Builder().name("sid").value(id).hostOnlyDomain("example.com").path("/").secure().expiresAt(System.currentTimeMillis()+3600000).build())))
                assertEquals(id, engine.search(source, "fixture").items.single().title)
                assertEquals("yes", store.load(scope)!!.cookies.single { it.name == "rotated" }.value)
                assertEquals(id, store.load(scope)!!.cookies.single { it.name == "sid" }.value)
            }
            val defaultAfter = withContext(Dispatchers.Main) { CookieManager.getInstance().getCookie("https://example.com/") }
            assertEquals(defaultBefore, defaultAfter)
            val fresh = PersistentSourceSessions(store)
            assertEquals("accountB", fresh.open(scopes[1]).cookiesForRendering().single { it.name == "sid" }.value)
        } finally {
            scopes.forEach { sessions.logout(it) }
            root.delete()
        }
    }

    @Test fun 未实现的认证Header渲染明确报错() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "render-header-" + UUID.randomUUID())
        val sessions = PersistentSourceSessions(EncryptedSessionStore(root, KeystoreSessionCipher()))
        val scope = requireNotNull(SourceAuthScope.create("header-fixture", "https://example.com/"))
        val session = sessions.open(scope)
        session.loginData["\$loginHeader"] = "{\"X-Identity\":\"fixture\"}"
        try {
            try {
                WebViewPageRenderer(context).renderAuthenticated("https://example.com/", emptyMap(), null, session)
                fail("认证 Header 不应悄悄进入普通网页渲染")
            } catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("认证 Header")) }
        } finally { sessions.logout(scope); root.delete() }
    }
}
