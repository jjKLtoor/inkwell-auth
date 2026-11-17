package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.auth.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import kotlin.test.*

class RenderingSessionTest {
    @get:Rule val temp = TemporaryFolder()
    private val scope = requireNotNull(SourceAuthScope.create("fixture", "https://example.com/"))
    private val url = "https://example.com/member".toHttpUrl()
    private fun cookie(value: String, path: String = "/") = Cookie.Builder().name("sid").value(value)
        .hostOnlyDomain("example.com").path(path).secure().expiresAt(System.currentTimeMillis() + 3600000).build()
    private fun sessions(): PersistentSourceSessions {
        val key = KeyGenerator.getInstance("AES").generateKey()
        return PersistentSourceSessions(EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key }))
    }

    @Test fun 相同身份后值覆盖且不同路径保留并正确删除() {
        val old = cookie("anonymous")
        val newest = cookie("member")
        val nested = cookie("nested", "/member")
        val jar = SessionCookieJar(scope, CookieSession(listOf(old, nested, newest)))
        assertEquals(setOf("member", "nested"), jar.loadForRequest(url).map { it.value }.toSet())
        jar.saveFromResponse(url, listOf(cookie("rotated")))
        assertEquals(setOf("rotated", "nested"), jar.loadForRequest(url).map { it.value }.toSet())
        jar.saveFromResponse(url, listOf(newest.newBuilder().expiresAt(0).build()))
        assertEquals("nested", jar.loadForRequest(url).single().value)
    }

    @Test fun 渲染更新和删除可合并且不覆盖并发请求与退出() = runBlocking {
        val sessions = sessions()
        sessions.replace(scope, CookieSession(listOf(cookie("old"), cookie("nested", "/member"))))
        val session = sessions.open(scope)
        val before = session.cookiesForRendering()
        session.mergeRenderedCookies(before, listOf(cookie("rendered")), listOf(url))
        assertEquals("rendered", session.loadForRequest(url).single().value)
        val second = session.cookiesForRendering()
        session.saveFromResponse(url, listOf(cookie("parallel")))
        session.mergeRenderedCookies(second, listOf(cookie("late")), listOf(url))
        assertEquals("parallel", session.loadForRequest(url).single().value)
        session.flush()
        assertEquals("parallel", sessions.savedSnapshot(scope)!!.cookies.single().value)
        sessions.logout(scope)
        session.mergeRenderedCookies(second, listOf(cookie("resurrect")), listOf(url))
        assertTrue(session.cookiesForRendering().isEmpty())
        assertFalse(sessions.open(scope).hasCredentials)
    }

    @Test fun 匿名资源不复制任何身份头() {
        assertEquals(mapOf("Accept" to "text/css"), anonymousResourceHeaders(mapOf(
            "Cookie" to "fixture", "Authorization" to "fixture", "X-Token" to "fixture", "Accept" to "text/css")))
    }

    @Test fun 声明动态网页的搜索有凭据时调用隔离渲染() = runBlocking {
        val source = BookSourceRule.fromJson("""{"bookSourceUrl":"https://example.com/","searchUrl":"/search,{\"webView\":true}","ruleSearch":{"bookList":"li","name":"a@text","bookUrl":"a@href"}}""")
        val sessions = sessions()
        val sourceScope = requireNotNull(sourceAuthScope(source))
        sessions.replace(sourceScope, CookieSession(listOf(cookie("identity"))))
        var authenticated = false
        val renderer = object : AuthenticatedPageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?): FetchedPage? = error("身份不能进入普通渲染器")
            override suspend fun renderAuthenticated(url: String, headers: Map<String, String>, userAgent: String?, session: SourceRequestSession): FetchedPage {
                authenticated = true
                assertEquals("identity", session.loadForRequest(url.toHttpUrl()).single().value)
                return FetchedPage(url, 200, "<ul><li><a href='/book'>fixture</a></li></ul>", "utf-8")
            }
        }
        val result = BookSourceEngine(SourceHttpClient(), renderer = renderer, sessions = sessions).search(source, "fixture")
        assertTrue(authenticated)
        assertEquals("fixture", result.items.single().title)
    }
}
