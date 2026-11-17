package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.auth.*
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.test.*

/** 通过真实引擎入口验证 HTTPS、同步脚本、二进制请求与撤销，不访问第三方网站。 */
class AuthenticatedEngineTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `阅读请求恢复身份且同站多来源与跨端口重定向隔离`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { site -> MockWebServer().use { other ->
            site.useHttps(tls.sslSocketFactory(), false); site.start()
            other.useHttps(tls.sslSocketFactory(), false); other.start()
            val key = KeyGenerator.getInstance("AES").generateKey()
            val store = EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key })
            val source = BookSourceRule(site.url("/").toString() + "#one", searchUrl = "/search")
            val scope = requireNotNull(SourceAuthScope.create(source.id, source.baseUrl))
            val cookie = requireNotNull(Cookie.parse(site.url("/"), "sid=one; Path=/; Secure; HttpOnly; Max-Age=3600"))
            store.save(scope, CookieSession(listOf(cookie)))
            val sessions = PersistentSourceSessions(store)
            sessions.open(scope).loginData["\$loginHeader"] = "{\"X-Identity\":\"fixture-header\"}"
            val http = SourceHttpClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build(), allowPrivateNetwork = true)
            val engine = BookSourceEngine(http, scriptRuntime = RhinoScriptRuntime(), sessions = sessions)

            site.enqueue(MockResponse().setBody("search fixture").setHeader("Set-Cookie", "sid=rotated; Path=/; Secure; HttpOnly; Max-Age=3600"))
            assertEquals("search fixture", engine.fetchSearchPage(source, "test").bodyText)
            val first = site.takeRequest()
            assertEquals("sid=one", first.getHeader("Cookie"))
            assertEquals("fixture-header", first.getHeader("X-Identity"))
            assertEquals("rotated", requireNotNull(store.load(scope)).cookies.single().value)
            site.enqueue(MockResponse().setBody("other source"))
            engine.fetchSearchPage(source.copy(bookSourceUrl = site.url("/").toString() + "#two"), "test")
            assertNull(site.takeRequest().getHeader("Cookie"))

            site.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/image")))
            other.enqueue(MockResponse().setBody("image"))
            assertEquals("image", engine.getImage(source, site.url("/image").toString()).toString(Charsets.UTF_8))
            assertEquals("sid=rotated", site.takeRequest().getHeader("Cookie"))
            val redirected = other.takeRequest()
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader("X-Identity"))

            site.enqueue(MockResponse().setBody("script")); site.enqueue(MockResponse().setBody("search"))
            engine.fetchSearchPage(source.copy(searchUrl = "@js:java.ajax(baseUrl + '/script'); baseUrl + '/search'"), "test")
            assertEquals("sid=rotated", site.takeRequest().getHeader("Cookie"))
            assertEquals("sid=rotated", site.takeRequest().getHeader("Cookie"))

            val old = sessions.open(scope)
            sessions.logout(scope)
            old.saveFromResponse(site.url("/"), listOf(cookie))
            old.loginData["\$loginHeader"] = "{\"Authorization\":\"late\"}"
            old.flush()
            assertTrue(old.loadForRequest(site.url("/")).isEmpty())
            assertNull(store.load(scope))
            site.enqueue(MockResponse().setBody("logged out"))
            engine.fetchSearchPage(source, "test")
            assertNull(site.takeRequest().getHeader("Cookie"))
        } }
    }

    @Test fun 密钥不可用不阻断无登录请求且保留原密文() = runBlocking {
        val root = temp.newFolder()
        val key = KeyGenerator.getInstance("AES").generateKey()
        val good = EncryptedSessionStore(root, AesGcmSessionCipher { key })
        val scope = requireNotNull(SourceAuthScope.create("fixture", "https://localhost/"))
        val cookie = requireNotNull(Cookie.parse(okhttp3.HttpUrl.Companion.run { scope.originUrl.toHttpUrl() }, "sid=original; Secure; Max-Age=3600"))
        good.save(scope, CookieSession(listOf(cookie)))
        val encrypted = root.listFiles()!!.single().readBytes()
        val broken = EncryptedSessionStore(root, object : SessionCipher {
            override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray = error("fixture key unavailable")
            override fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray = error("fixture key unavailable")
        })
        val sessions = PersistentSourceSessions(broken)
        val session = sessions.open(scope)
        assertTrue(session.loadForRequest(okhttp3.HttpUrl.Companion.run { scope.originUrl.toHttpUrl() }).isEmpty())
        session.flush()
        assertTrue(sessions.storageFailed(scope))
        assertContentEquals(encrypted, root.listFiles()!!.single().readBytes())
    }

    @Test fun `Header加密恢复且退出后的旧会话不能重写磁盘`() = runBlocking {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key })
        val scope = requireNotNull(SourceAuthScope.create("one", "https://example.com/"))
        val sessions = PersistentSourceSessions(store)
        val session = sessions.open(scope)
        session.loginData["\$loginHeader"] = "{\"Authorization\":\"fixture\"}"
        session.loginData["\$loginInfo"] = "password-must-not-persist"
        session.flush()
        val restored = PersistentSourceSessions(store).open(scope)
        assertEquals(session.loginData["\$loginHeader"], restored.loginData["\$loginHeader"])
        assertNull(restored.loginData["\$loginInfo"])
        sessions.logout(scope)
        session.flush()
        assertNull(store.load(scope))
    }
}
