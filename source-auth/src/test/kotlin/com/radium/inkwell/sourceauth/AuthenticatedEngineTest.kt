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

    @Test fun 删除Cookie后旧请求头副本不能补回且匿名访问与重新登录可用() = runBlocking {
        val serverCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(serverCertificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(serverCertificate.certificate).build()
        val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
        val http = SourceHttpClient(client, allowPrivateNetwork = true)
        val key = KeyGenerator.getInstance("AES").generateKey()
        val root = temp.newFolder()
        fun newStore() = EncryptedSessionStore(root, AesGcmSessionCipher { key })

        MockWebServer().use { site ->
            site.useHttps(tls.sslSocketFactory(), false)
            site.start()
            val source = BookSourceRule(site.url("/").toString(), searchUrl = "/search")
            val scope = requireNotNull(SourceAuthScope.create(source.id, source.baseUrl))
            val headerKey = "\$loginHeader"
            fun cookie(value: String) = requireNotNull(Cookie.parse(site.url("/"), "sid=$value; Path=/; Secure; Max-Age=3600"))
            val sessions = PersistentSourceSessions(newStore())
            val oldCookie = cookie("fixture-old")
            sessions.replace(scope, CookieSession(listOf(oldCookie), mapOf(
                headerKey to """{"cOoKiE":"sid=fixture-old; preference=kept","Authorization":"Bearer independent-fixture"}""",
                "\$loginInfo" to """{"manualToken":"fixture-old","other":"kept"}""",
            )))

            suspend fun request(provider: PersistentSourceSessions): String? {
                site.enqueue(MockResponse().setBody("public fixture"))
                assertEquals("public fixture", BookSourceEngine(http, sessions = provider).fetchSearchPage(source, "test").bodyText)
                return site.takeRequest().getHeader("Cookie")
            }

            assertEquals("sid=fixture-old", request(sessions))
            val previous = sessions.open(scope)
            sessions.editCookie(scope, oldCookie, null)
            previous.saveFromResponse(site.url("/"), listOf(oldCookie))
            previous.flush()
            assertFalse(previous.active)
            assertEquals("""{"other":"kept"}""", sessions.open(scope).loginData["\$loginInfo"])
            val persistedHeader = sessions.savedSnapshot(scope)?.loginData?.get(headerKey).orEmpty()
            assertContains(persistedHeader, "Bearer independent-fixture")
            assertFalse(persistedHeader.contains("fixture-old"))
            assertEquals("preference=kept", request(sessions))
            val restarted = PersistentSourceSessions(newStore())
            assertEquals("preference=kept", request(restarted))
            restarted.logout(scope)
            assertNull(request(restarted))
            restarted.replace(scope, CookieSession(listOf(cookie("fixture-new"))))
            assertEquals("sid=fixture-new", request(restarted))
        }
    }

    @Test fun v2旧会话仍可读取并按v3保存且不删除其他文件() = runBlocking {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val cipher = AesGcmSessionCipher { key }
        val root = temp.newFolder()
        val scope = requireNotNull(SourceAuthScope.create("legacy-fixture", "https://example.com/"))
        val binding = java.security.MessageDigest.getInstance("SHA-256").digest((scope.sourceId + "\u0000" + scope.originUrl).toByteArray())
        val cookie = requireNotNull(Cookie.parse("https://example.com/".toHttpUrl(), "sid=legacy; Secure; HttpOnly; Path=/; Max-Age=3600"))
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(2); out.writeInt(1); out.writeUTF(cookie.toString()); out.writeInt(0)
        }
        java.io.File(root, binding.joinToString("") { "%02x".format(it) } + ".auth").writeBytes(cipher.encrypt(bytes.toByteArray(), binding))
        val other = java.io.File(root, "unrelated-data").apply { writeText("keep") }
        val store = EncryptedSessionStore(root, cipher)
        val restored = requireNotNull(store.load(scope))
        assertEquals("legacy", restored.cookies.single().value)
        store.save(scope, restored)
        assertEquals("legacy", requireNotNull(store.load(scope)).cookies.single().value)
        assertEquals("keep", other.readText())
    }

    @Test fun 脚本裸域名Cookie调用仅补全当前获准的HTTPS站点() = runBlocking {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key })
        val scope = requireNotNull(SourceAuthScope.create("fixture", "https://example.com/"))
        val sessions = PersistentSourceSessions(store)
        val cookie = Cookie.Builder().name("sid").value("fixture").hostOnlyDomain("example.com").path("/").secure().build()
        val session = sessions.open(scope)
        session.saveFromResponse("https://example.com/".toHttpUrl(), listOf(cookie))
        withContext(SourceRequestSession.binding(session)) {
            val bridge = com.radium.inkwell.core.source.js.CookieBridge(com.radium.inkwell.core.source.js.EngineJsHttp(SourceHttpClient()))
            assertEquals("fixture", bridge.getKey("example.com", "sid"))
            assertEquals("", bridge.getCookie("outside.test"))
            bridge.removeCookie("outside.test")
            assertEquals("fixture", bridge.getKey("example.com", "sid"))
        }
    }

    @Test fun 双站点Cookie恢复保留hostOnly且Header只发原站点() = runBlocking {
        val key = KeyGenerator.getInstance("AES").generateKey()
        val store = EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key })
        val scope = requireNotNull(SourceAuthScope.create("fixture", "https://reader.example.com/", listOf("https://login.example.com/")))
        val cookie = Cookie.Builder().name("login").value("fixture").hostOnlyDomain("login.example.com")
            .path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 3600000).build()
        store.save(scope, CookieSession(listOf(cookie)))
        val restored = requireNotNull(store.load(scope)).cookies.single()
        assertEquals("login.example.com", restored.domain)
        assertTrue(restored.hostOnly)
        val jar = SessionCookieJar(scope, CookieSession(listOf(restored)))
        assertTrue(jar.loadForRequest(okhttp3.HttpUrl.Companion.run { "https://reader.example.com/".toHttpUrl() }).isEmpty())

        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { source -> MockWebServer().use { login ->
            source.useHttps(tls.sslSocketFactory(), false); source.start()
            login.useHttps(tls.sslSocketFactory(), false); login.start()
            val rule = BookSourceRule(source.url("/").toString(), searchUrl = "/search", loginUrl = login.url("/").toString())
            // 生产配置拒绝私网登录；受控测试显式构造两个获准的本机 origin。
            val approved = requireNotNull(SourceAuthScope.create(rule.id, rule.baseUrl, listOf(login.url("/").toString())))
            val sessions = PersistentSourceSessions(store)
            sessions.open(approved).loginData["\$loginHeader"] = "{\"X-Identity\":\"secret-fixture\"}"
            val provider = object : SourceSessionProvider {
                override suspend fun open(scope: SourceAuthScope): SourceRequestSession = sessions.open(approved)
                override suspend fun forSource(source: BookSourceRule): SourceRequestSession = sessions.open(approved)
            }
            val http = SourceHttpClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build(), allowPrivateNetwork = true)
            val engine = BookSourceEngine(http, sessions = provider)
            source.enqueue(MockResponse().setResponseCode(302).setHeader("Location", login.url("/member")))
            login.enqueue(MockResponse().setBody("fixture"))
            engine.fetchSearchPage(rule, "fixture")
            assertEquals("secret-fixture", source.takeRequest().getHeader("X-Identity"))
            assertNull(login.takeRequest().getHeader("X-Identity"))
        } }
    }

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
