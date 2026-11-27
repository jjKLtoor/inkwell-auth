package com.radium.inkwell.data.source

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.SourceSessionKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SourceLoginSessionsTest {
    @Test
    fun changingSourceSettingsForgetsTheLastSearchOrigin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val sessions = SourceLoginSessions(context)
        val origin = "https://books.example/"
        try {
            sessions.write(id, SourceSessionKey.VARIABLE, "server-1")
            sessions.write(id, SourceSessionKey.SEARCH_ORIGIN, origin)
            sessions.write(id, SourceSessionKey.VARIABLE, "server-1")
            assertEquals(origin, SourceLoginSessions(context).read(id, SourceSessionKey.SEARCH_ORIGIN))
            sessions.write(id, SourceSessionKey.VARIABLE, "server-2")
            assertNull(SourceLoginSessions(context).read(id, SourceSessionKey.SEARCH_ORIGIN))
        } finally {
            sessions.writeAll(id, mapOf(SourceSessionKey.VARIABLE to null, SourceSessionKey.SEARCH_ORIGIN to null))
        }
    }

    @Test
    @SuppressLint("SetJavaScriptEnabled")
    fun webLoginCookieIsAvailableOnlyToTheSourceThatFinishedLogin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sessions = SourceLoginSessions(context)
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val url = "https://login.example.test/signin".toHttpUrl()
        val firstProfile = loginProfileName(first)
        val secondProfile = loginProfileName(second)
        val loaded = CompletableDeferred<Unit>()
        val webView = withContext(Dispatchers.Main) {
            assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            WebView(context).apply {
                WebViewCompat.setProfile(this, firstProfile)
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        if (url == "https://login.example.test/signin") loaded.complete(Unit)
                    }
                }
                loadDataWithBaseURL(url.toString(), """
                    <button id="login" onclick="document.cookie='session=fixture; Path=/; Secure'">登录</button>
                """.trimIndent(), "text/html", "UTF-8", url.toString())
            }
        }

        try {
            withTimeout(10_000) { loaded.await() }
            val clicked = CompletableDeferred<String?>()
            withContext(Dispatchers.Main) {
                ProfileStore.getInstance().getOrCreateProfile(secondProfile)
                webView.evaluateJavascript("document.querySelector('#login').click(); document.cookie") {
                    clicked.complete(it)
                }
            }
            assertEquals("\"session=fixture\"", withTimeout(5_000) { clicked.await() })

            val http = SourceHttpClient(sessionStore = sessions, webCookies = sessions::webCookies,
                updateWebCookies = sessions::updateWebCookies, clearWebCookies = sessions::clearWebCookies)
            assertEquals("", http.cookieOf(url.toString(), first))
            assertTrue(sessions.rememberWebLogin(first, url, url, "fixture UA"))
            assertFalse(sessions.rememberWebLogin(second, url, url, "fixture UA"))
            assertEquals("session=fixture", http.cookieOf(url.toString(), first))
            assertEquals("", http.cookieOf(url.toString(), second))
            http.removeCookie(url.toString(), first)
            assertEquals("", http.cookieOf(url.toString(), first))
            assertFalse(SourceLoginSessions(context).hasWebLogin(first, url))

            // 重新保存后只能读到新值，另一来源继续维持原状态。
            val newCookie = CompletableDeferred<String?>()
            withContext(Dispatchers.Main) {
                webView.evaluateJavascript("document.cookie='session=renewed; Path=/; Secure'; document.cookie") {
                    newCookie.complete(it)
                }
            }
            assertEquals("\"session=renewed\"", withTimeout(5_000) { newCookie.await() })
            assertTrue(sessions.rememberWebLogin(first, url, url, "fixture UA"))
            assertEquals("session=renewed", http.cookieOf(url.toString(), first))
            assertEquals("", http.cookieOf(url.toString(), second))
        } finally {
            sessions.clearWebCookies(first, url)
            withContext(Dispatchers.Main) {
                webView.destroy()
                val profiles = ProfileStore.getInstance()
                for (name in listOf(firstProfile, secondProfile)) {
                    try {
                        profiles.deleteProfile(name)
                    } catch (error: IllegalStateException) {
                        // Chromium 可能保留已加载 profile 到进程退出。合成会话已上面清空，保留调用断言结果。
                        if (!error.message.orEmpty().contains("in-use profile")) throw error
                    }
                }
            }
        }
    }
}
