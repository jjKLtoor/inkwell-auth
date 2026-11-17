package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import android.webkit.WebResourceResponse
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.radium.inkwell.MainActivity
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.sourceauth.LoginConfiguration
import com.radium.inkwell.sourceauth.LoginWebSession
import com.radium.inkwell.ui.theme.InkwellTheme
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SourceLoginScreenTest {
    @Test fun 点击登录后实际加载网页且WebView有可见尺寸() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { assumeTrue(LoginWebSession.supported()) }
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = requireNotNull(SourceAuthScope.create("ui-fixture", "https://example.com/"))
        val state = mutableStateOf(SourceLoginState(loaded = true, scope = scope, page = LoginConfiguration.WebPage("https://example.com/login"), message = "未登录"))
        val requests = AtomicInteger()
        var web: android.webkit.WebView? = null
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    InkwellTheme {
                        SourceLoginContent(
                            state.value, {}, { state.value = state.value.copy(browsing = true, message = "登录中") },
                            { state.value = state.value.copy(browsing = false) }, { fail(it) }, {}, {},
                            sessionFactory = { ctx, auth, onError ->
                                LoginWebSession(ctx, auth, {
                                    requests.incrementAndGet()
                                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<html><body>受控登录页面</body></html>".toByteArray()))
                                }, onError).also { web = it.view }
                            },
                        )
                    }
                }
            }
            val device = UiDevice.getInstance(instrumentation)
            val login = device.wait(Until.findObject(By.text("登录")), 10000)
            assertNotNull("登录按钮未出现", login)
            login.click()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (requests.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("点击后没有加载网页", requests.get() > 0)
            assertTrue("网页内容必须实际可见", device.wait(Until.hasObject(By.text("受控登录页面")), 10000))
            instrumentation.runOnMainSync { assertTrue("WebView 高度必须大于零", requireNotNull(web).height > 0) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
