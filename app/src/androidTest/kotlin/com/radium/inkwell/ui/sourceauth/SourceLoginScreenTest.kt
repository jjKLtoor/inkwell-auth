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
    @Test fun 多个网页登录入口只能单选并打开所选页面() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { assumeTrue(LoginWebSession.supported()) }
        val context = inst.targetContext
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val pages = listOf(LoginConfiguration.WebPage("https://example.com/login"), LoginConfiguration.WebPage("https://example.com/mobile"))
        val scope = requireNotNull(SourceAuthScope.create("picker-ui", "https://example.com/"))
        val state = mutableStateOf(SourceLoginState(loaded = true, scope = scope, page = pages[0], pages = pages))
        try {
            inst.runOnMainSync { activity.setContent { InkwellTheme {
                SourceLoginContent(state.value, {}, { state.value = state.value.copy(browsing = true) }, {}, { fail(it) }, {}, {},
                    sessionFactory = { ctx, auth, error -> LoginWebSession(ctx, auth, { request ->
                        WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(("<html><meta name='viewport' content='width=device-width,initial-scale=1'><body>入口" + request.url.path + "</body></html>").toByteArray()))
                    }, error) }, onSelectPage = { url -> state.value = state.value.copy(page = pages.single { it.url == url }) })
            } } }
            val device = UiDevice.getInstance(inst)
            device.wait(Until.findObject(By.text("登录网页")), 10000).click()
            assertNotNull(device.wait(Until.findObject(By.text("登录入口 2")), 10000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-picker.png"))
            device.findObject(By.text("登录入口 2")).click()
            device.wait(Until.findObject(By.text("登录")), 10000).click()
            assertTrue(device.wait(Until.hasObject(By.text("入口/mobile")), 10000))
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun 已保存会话直接打开且网页确认与登录新窗口可以操作() {
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync { assumeTrue(LoginWebSession.supported()) }
        val context = inst.targetContext
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = requireNotNull(SourceAuthScope.create("restore-ui", "https://example.com/"))
        val cookie = requireNotNull(okhttp3.Cookie.parse(okhttp3.HttpUrl.Companion.run { "https://example.com/".toHttpUrl() }, "sid=fixture; Secure; Path=/; Max-Age=3600"))
        val restored = com.radium.inkwell.sourceauth.CookieSession(listOf(cookie), webOrigins = listOf("https://cdn.example.test/"), allowWebResources = true)
        val state = mutableStateOf(SourceLoginState(loaded = true, scope = scope, page = LoginConfiguration.WebPage("https://example.com/login"), browsing = true, restored = restored))
        val saved = AtomicInteger()
        try {
            inst.runOnMainSync {
                activity.setContent { InkwellTheme {
                    SourceLoginContent(state.value, {}, {}, { state.value = state.value.copy(browsing = false) }, { fail(it) },
                        { assertEquals(listOf("https://cdn.example.test/"), it.webOrigins); assertTrue(it.allowWebResources); saved.incrementAndGet(); state.value = state.value.copy(message = "会话已保存，登录状态未知") }, {},
                        sessionFactory = { ctx, auth, error -> LoginWebSession(ctx, auth, { request ->
                            val body = if (request.url.host == "cdn.example.test") "document.getElementById('permit').innerText='站点许可已恢复';"
                                else if (request.url.path == "/popup") "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body>受控登录子窗口<button onclick='window.close()'>关闭子窗口</button></body></html>"
                                else """<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><div id='status'></div><div id='permit'></div><script src='https://cdn.example.test/app.js'></script><button onclick="if(confirm('继续受控登录？'))document.getElementById('status').innerText='确认按钮可用'">验证登录按钮</button><a href='/popup' target='_blank'>新窗口登录</a><script>document.getElementById('status').innerText=document.cookie.indexOf('sid=fixture')>=0?'Cookie已恢复':'Cookie未恢复';</script></body></html>"""
                            WebResourceResponse(if (request.url.host == "cdn.example.test") "application/javascript" else "text/html", "UTF-8", ByteArrayInputStream(body.toByteArray()))
                        }, error) })
                } }
            }
            val device = UiDevice.getInstance(inst)
            assertTrue(device.wait(Until.hasObject(By.text("Cookie已恢复")), 10000))
            assertTrue(device.wait(Until.hasObject(By.text("站点许可已恢复")), 10000))
            assertFalse(device.hasObject(By.text("登录")))
            device.findObject(By.text("验证登录按钮")).click()
            assertNotNull(device.wait(Until.findObject(By.textContains("继续受控登录？")), 10000))
            device.findObject(By.text("确定")).click()
            assertTrue(device.wait(Until.hasObject(By.text("确认按钮可用")), 10000))
            device.findObject(By.text("新窗口登录")).click()
            assertTrue(device.wait(Until.hasObject(By.text("受控登录子窗口")), 10000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-popup.png"))
            device.findObject(By.text("关闭子窗口")).click()
            assertTrue(device.wait(Until.hasObject(By.text("确认按钮可用")), 10000))
            device.findObject(By.text("保存会话")).click()
            assertTrue(device.wait(Until.hasObject(By.text("会话已保存，登录状态未知")), 10000))
            assertEquals(1, saved.get())
            assertTrue("保存后仍应留在当前网页", device.hasObject(By.text("确认按钮可用")))
        } finally { inst.runOnMainSync { activity.finish() } }
    }

    @Test fun 点击登录后实际加载网页且WebView有可见尺寸() = checkPage(false)
    @Test fun 额外网页资源先拦截经原生确认后登录内容可见() = checkPage(true)

    @Test fun 样式资源受限经确认后正确显示登录内容() = checkPage(true, true)

    @Test fun 一次允许网页资源后多个域名无需逐个确认且只重载一次() = checkPage(true, true, true)

    @Test fun 额外主页面跳转仍须单独确认() = checkPage(true, redirect = true)

    private fun checkPage(dependency: Boolean, stylesheet: Boolean = false, batch: Boolean = false, redirect: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { assumeTrue(LoginWebSession.supported()) }
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope = requireNotNull(SourceAuthScope.create("ui-fixture", "https://example.com/"))
        val state = mutableStateOf(SourceLoginState(loaded = true, scope = scope, page = LoginConfiguration.WebPage("https://example.com/login"), message = "未登录"))
        val requests = AtomicInteger()
        val mainRequests = AtomicInteger()
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
                                    val script = request.url.host == "cdn.example.test" || request.url.host == "script.example.test"
                                    if (request.isForMainFrame) mainRequests.incrementAndGet()
                                    val body = if (request.url.host == "mobile.example.test") "<html><body>受控登录页面</body></html>"
                                        else if (redirect) "<html><body>等待页面资源<script>location.href='https://mobile.example.test/member?code=fixture'</script></body></html>"
                                        else if (request.url.host == "script.example.test") "window.scriptReady=true;"
                                        else if (script && stylesheet) "#login{display:block!important}#waiting{display:none}"
                                        else if (script) "document.getElementById('login').innerText='受控登录页面';"
                                        else if (stylesheet) "<html><meta name='viewport' content='width=device-width,initial-scale=1'><style>#login{display:none}</style><link rel='stylesheet' href='https://cdn.example.test/app.css'>${if (batch) "<script src='https://script.example.test/app.js'></script>" else ""}<body><div id='waiting'>等待页面资源</div><div id='login'>受控登录页面</div></body></html>"
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
            if (dependency) {
                val resources = device.wait(Until.findObject(By.text("允许公开网页资源")), 10000)
                if (resources == null) androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollTextIntoView("允许公开网页资源")
                val setting = device.findObject(By.text("允许公开网页资源"))
                assertNotNull(setting)
                setting.click() // 仅为验证关闭后的提示与一次恢复，正常默认允许。
                if (resources == null) {
                    androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToBeginning(3)
                    device.waitForIdle()
                }
            }
            val login = device.wait(Until.findObject(By.text("登录")), 10000)
            assertNotNull("登录按钮未出现", login)
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-before.png"))
            login.click()
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (requests.get() == 0 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue("点击后没有加载网页", requests.get() > 0)
            if (dependency) {
                assertTrue(device.wait(Until.hasObject(By.text("等待页面资源")), 10000))
                if (stylesheet) assertTrue(device.hasObject(By.text("网页资源未完整加载")))
                val review = device.wait(Until.findObject(By.text(if (redirect) "选择登录站点" else "加载网页资源")), 10000)
                assertNotNull("应提供统一资源设置，不逐域名选择", review)
                review.click()
                device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-resources.png"))
                val allow = device.wait(Until.findObject(By.text(if (redirect) "允许并继续" else "允许加载资源")), 10000)
                assertNotNull(allow)
                allow.click()
            }
            assertTrue("网页内容必须实际可见", device.wait(Until.hasObject(By.text("受控登录页面")), 10000))
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "loginui-after.png"))
            if (batch) assertEquals("一次资源确认只应重载一次", 2, mainRequests.get())
            instrumentation.runOnMainSync { assertTrue("WebView 高度必须大于零", requireNotNull(web).height > 0)
                assertTrue(requireNotNull(web).settings.useWideViewPort)
                assertTrue(requireNotNull(web).settings.loadWithOverviewMode) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
