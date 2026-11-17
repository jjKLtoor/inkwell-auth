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
    @Test fun 点击登录后实际加载网页且WebView有可见尺寸() = checkPage(false)
    @Test fun 额外网页资源先拦截经原生确认后登录内容可见() = checkPage(true)

    @Test fun 样式资源受限经确认后正确显示登录内容() = checkPage(true, true)

    private fun checkPage(dependency: Boolean, stylesheet: Boolean = false) {
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
                                LoginWebSession(ctx, auth, { request ->
                                    requests.incrementAndGet()
                                    val script = request.url.host == "cdn.example.test"
                                    val body = if (script && stylesheet) "#login{display:block!important}#waiting{display:none}"
                                        else if (script) "document.getElementById('login').innerText='受控登录页面';"
                                        else if (stylesheet) "<html><meta name='viewport' content='width=device-width,initial-scale=1'><style>#login{display:none}</style><link rel='stylesheet' href='https://cdn.example.test/app.css'><body><div id='waiting'>等待页面资源</div><div id='login'>受控登录页面</div></body></html>"
                                        else if (dependency) "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><div id='login'>等待页面资源</div><script src='https://cdn.example.test/app.js'></script></body></html>"
                                        else "<html><body>受控登录页面</body></html>"
                                    WebResourceResponse(if (script && stylesheet) "text/css" else if (script) "application/javascript" else "text/html", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                                }, onError).also { web = it.view }
                            },
                        )
                    }
                }
            }
            val device = UiDevice.getInstance(instrumentation)
            val login = device.wait(Until.findObject(By.text("登录")), 10000)
            assertNotNull("登录按钮未出现", login)
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-before.png"))
            login.click()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (requests.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("点击后没有加载网页", requests.get() > 0)
            if (dependency) {
                assertTrue(device.wait(Until.hasObject(By.text("等待页面资源")), 10000))
                if (stylesheet) assertTrue(device.hasObject(By.text("网页样式未完整加载")))
                val review = device.wait(Until.findObject(By.text("核对站点")), 10000)
                assertNotNull("必须先显示原生站点确认入口", review)
                review.click()
                val allow = device.wait(Until.findObject(By.text("本次允许")), 10000)
                assertNotNull(allow)
                allow.click()
            }
            assertTrue("网页内容必须实际可见", device.wait(Until.hasObject(By.text("受控登录页面")), 10000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-after.png"))
            instrumentation.runOnMainSync { assertTrue("WebView 高度必须大于零", requireNotNull(web).height > 0)
                assertTrue(requireNotNull(web).settings.useWideViewPort)
                assertTrue(requireNotNull(web).settings.loadWithOverviewMode) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
