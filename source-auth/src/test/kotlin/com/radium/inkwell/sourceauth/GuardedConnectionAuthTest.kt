package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.auth.*
import com.radium.inkwell.core.source.js.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.test.*

class GuardedConnectionAuthTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun URL替身仅向来源发送身份且登录草稿阻止越域请求() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { site -> MockWebServer().use { other ->
            site.useHttps(tls.sslSocketFactory(), false); other.useHttps(tls.sslSocketFactory(), false)
            site.start(); other.start()
            val scope = requireNotNull(SourceAuthScope.create("url-fixture", site.url("/").toString()))
            val key = KeyGenerator.getInstance("AES").generateKey()
            val sessions = PersistentSourceSessions(EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key }))
            val cookie = Cookie.Builder().name("sid").value("fixture").hostOnlyDomain("localhost").path("/").secure().expiresAt(System.currentTimeMillis()+3600000).build()
            val saved = CookieSession(listOf(cookie), mapOf("\$loginHeader" to "{\"X-Identity\":\"fixture\"}"))
            sessions.replace(scope, saved)
            val session = sessions.open(scope)
            val http = EngineJsHttp(SourceHttpClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build(), allowPrivateNetwork = true))
            var cached: GuardedUrlConnection? = null
            var retainedUrl: GuardedUrlShim? = null
            withContext(SourceRequestSession.binding(session)) {
                site.enqueue(MockResponse().setBody("fixture"))
                retainedUrl = JavaBridge(http, JsCache(), mutableMapOf()).guardedUrl(site.url("/").toString())
                cached = retainedUrl!!.openConnection()
                assertEquals(200, cached!!.getResponseCode())
                val first = site.takeRequest()
                assertEquals("sid=fixture", first.getHeader("Cookie"))
                assertEquals("fixture", first.getHeader("X-Identity"))
                other.enqueue(MockResponse().setBody("outside"))
                val bridge = JavaBridge(http, JsCache(), mutableMapOf())
                val connection = bridge.guardedUrl(other.url("/").toString()).openConnection()
                connection.setRequestProperty("Cookie", "sid=fixture")
                connection.setRequestProperty("X-Identity", "fixture")
                connection.setRequestProperty("Authorization", "Bearer fixture")
                assertEquals(200, connection.getResponseCode())
                val outside = other.takeRequest()
                assertNull(outside.getHeader("Cookie")); assertNull(outside.getHeader("X-Identity")); assertNull(outside.getHeader("Authorization"))
            }
            val sameSiteOtherSource = sessions.open(requireNotNull(SourceAuthScope.create("another-url-fixture", site.url("/").toString())))
            withContext(SourceRequestSession.binding(sameSiteOtherSource)) {
                assertFails { cached!!.getResponseCode() }
                assertFails { retainedUrl!!.openConnection() }
            }
            assertEquals(1, site.requestCount)
            val draft = DraftLoginSession(scope, saved)
            withContext(SourceRequestSession.binding(draft)) {
                assertFails { JavaBridge(http, JsCache(), mutableMapOf()).guardedUrl(other.url("/").toString()).openConnection().getResponseCode() }
            }
            assertEquals(1, other.requestCount)
            sessions.logout(scope)
            withContext(SourceRequestSession.binding(session)) {
                assertFails { cached!!.getResponseCode() }
            }
            withContext(SourceRequestSession.binding(sessions.open(scope))) {
                site.enqueue(MockResponse().setBody("anonymous"))
                assertEquals(200, GuardedUrlConnectionForTest.status(http, site.url("/")))
                val loggedOut = site.takeRequest()
                assertNull(loggedOut.getHeader("Cookie")); assertNull(loggedOut.getHeader("X-Identity"))
            }
        } }
    }
}

private object GuardedUrlConnectionForTest {
    fun status(http: JsHttp, url: HttpUrl) = JavaBridge(http, JsCache(), mutableMapOf()).guardedUrl(url.toString()).openConnection().getResponseCode()
}
