package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCookieJarTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `路径Secure过期域名端口和hostOnly规则均参与请求匹配`() {
        val scope = requireNotNull(SourceAuthScope.create("one", "https://www.example.com/"))
        val url = scope.originUrl.toHttpUrl()
        val jar = SessionCookieJar(scope, CookieSession(listOf(
            requireNotNull(Cookie.parse(url, "sid=fixture; Path=/member; Secure; HttpOnly")),
            // Android 的纯 JVM 测试没有 PSL assets 上下文；这里验证 CookieJar 匹配，解析由 core 与设备测试覆盖。
            Cookie.Builder().name("parent").value("fixture").domain("example.com").path("/").build(),
        )))
        assertEquals(2, jar.loadForRequest("https://www.example.com/member/x".toHttpUrl()).size)
        assertEquals(listOf("parent"), jar.loadForRequest("https://www.example.com/member2".toHttpUrl()).map { it.name })
        listOf("http://www.example.com/member", "https://sub.example.com/member", "https://www.example.com:444/member", "https://evil.test/")
            .forEach { assertTrue(jar.loadForRequest(it.toHttpUrl()).isEmpty()) }
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("parent").value("deleted").domain("example.com").path("/").expiresAt(0).build()))
        assertTrue(jar.loadForRequest("https://www.example.com/".toHttpUrl()).isEmpty())
        jar.clear()
        assertTrue(jar.loadForRequest("https://www.example.com/member".toHttpUrl()).isEmpty())
    }

    @Test fun `受控HTTPS请求经加密恢复后携带身份且跨端口重定向和退出后不携带`() = runBlocking {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server ->
            MockWebServer().use { redirected ->
                server.useHttps(serverTls.sslSocketFactory(), false)
                redirected.useHttps(serverTls.sslSocketFactory(), false)
                server.start()
                redirected.start()
                val scope = requireNotNull(SourceAuthScope.create("fixture", server.url("/").toString()))
                val key = KeyGenerator.getInstance("AES").generateKey()
                val cipher = AesGcmSessionCipher { key }
                val root = temporary.newFolder()
                val store = EncryptedSessionStore(root, cipher)
                store.save(scope, CookieSession(listOf(requireNotNull(Cookie.parse(server.url("/"), "sid=fixture-session; Secure; HttpOnly; Path=/; Max-Age=3600")))))
                val restored = requireNotNull(EncryptedSessionStore(root, cipher).load(scope))
                val jar = SessionCookieJar(scope, restored)
                val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).cookieJar(jar).build()
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected.url("/member")))
                redirected.enqueue(MockResponse().setBody("fixture authorized endpoint"))
                client.newCall(Request.Builder().url(server.url("/member")).build()).execute().use { assertEquals(200, it.code) }
                assertEquals("sid=fixture-session", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertNull(redirected.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                jar.clear()
                store.clear(scope)
                server.enqueue(MockResponse().setBody("logged out fixture"))
                client.newCall(Request.Builder().url(server.url("/member")).build()).execute().close()
                assertNull(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
                assertNull(EncryptedSessionStore(root, cipher).load(scope))
            }
        }
    }
}
