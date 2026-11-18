package com.radium.inkwell.ui.sourceauth

import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.sourceauth.LoginWebSession
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 本地构造响应，核对真实 Chromium 发出的登录请求；不登录真实网站。 */
class LoginRequestContextTest {
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
