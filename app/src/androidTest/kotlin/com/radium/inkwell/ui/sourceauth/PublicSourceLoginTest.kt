package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.radium.inkwell.MainActivity
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.sourceauth.*
import com.radium.inkwell.ui.theme.InkwellTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 仅显式传入快照才访问公开登录页；不填账号、不抓正文、不自动批准额外域名。 */
class PublicSourceLoginTest {
    @Test fun 逐条记录已授权的公开页面访问结果而非登录成功() {
        val args = InstrumentationRegistry.getArguments()
        val snapshot = args.getString("publicSnapshot")
        assumeTrue("需显式授权并传入开发快照", snapshot != null)
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val sourceFilter = args.getString("sourceFilter")?.split(",")?.toSet()
        val approvedOrigins = args.getString("confirmOrigins")?.split(",")?.toSet().orEmpty()
        val rows = Json.parseToJsonElement(File(requireNotNull(snapshot)).readText()).jsonArray.filter {
            it.jsonObject["bookSourceGroup"]?.jsonPrimitive?.content.orEmpty().contains("大厂正版") &&
                (sourceFilter == null || it.jsonObject["bookSourceName"]?.jsonPrimitive?.content in sourceFilter)
        }
        require(rows.isNotEmpty()) { "没有匹配的测试来源" }
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val device = UiDevice.getInstance(inst)
        val results = mutableListOf<JsonObject>()
        try {
            for (row in rows) {
                val source = BookSourceRule.fromJson(row.toString())
                val page = resolveLoginConfiguration(source) as? LoginConfiguration.WebPage
                val scope = sourceAuthScope(source)
                val events = ConcurrentLinkedQueue<LoginPageEvent>()
                var owned: LoginWebSession? = null
                var result = "NOT_SUPPORTED"
                var confirmation = "NOT_ATTEMPTED"
                var dom = ""
                val granted = mutableListOf<String>()
                val priorEvents = mutableListOf<LoginPageEvent>()
                try {
                    if (page != null && scope != null) {
                        val state = mutableStateOf(SourceLoginState(loaded = true, name = source.name, scope = scope, page = page, message = "未登录"))
                        inst.runOnMainSync {
                            activity.setContent {
                                InkwellTheme { key(source.id) {
                                    SourceLoginContent(state.value, {}, { state.value = state.value.copy(browsing = true) },
                                        { state.value = state.value.copy(browsing = false) }, { result = "APP_ERROR" }, {}, {},
                                        sessionFactory = { ctx, auth, error -> LoginWebSession(ctx, auth, WebViewNetworkGuard()::intercept, error).also { owned = it } },
                                        onDiagnosticEvent = { events.add(it) })
                                } }
                            }
                        }
                        val login = device.wait(Until.findObject(By.text("登录")), 10000)
                        if (login == null) result = "ENTRY_UNAVAILABLE" else {
                            login.click()
                            val deadline = android.os.SystemClock.elapsedRealtime() + 20000
                            while (android.os.SystemClock.elapsedRealtime() < deadline && events.none {
                                it is LoginPageEvent.Visible || it is LoginPageEvent.Failed || it is LoginPageEvent.Blocked && it.mainFrame
                            }) Thread.sleep(100)
                            val navigation = events.filterIsInstance<LoginPageEvent.Blocked>().firstOrNull { it.mainFrame && it.origin in approvedOrigins }
                            val navigationOrigin = navigation?.origin
                            if (navigationOrigin != null) {
                                confirmation = "REVIEW_MISSING"
                                val review = device.wait(Until.findObject(By.text("核对站点")), 5000)
                                if (review != null) {
                                    review.click()
                                    confirmation = "DIALOG_NOT_READY"
                                    val dialog = device.wait(Until.findObject(By.textContains(navigationOrigin)), 5000)
                                    val allow = device.wait(Until.findObject(By.text("本次允许")), 5000)
                                    if (dialog != null && allow != null) {
                                        priorEvents.addAll(events); events.clear()
                                        allow.click()
                                        confirmation = "CONFIRMED"
                                        granted.add(navigationOrigin)
                                        val secondDeadline = android.os.SystemClock.elapsedRealtime() + 20000
                                        while (android.os.SystemClock.elapsedRealtime() < secondDeadline && events.none {
                                            it is LoginPageEvent.Visible || it is LoginPageEvent.Failed || it is LoginPageEvent.Blocked && it.mainFrame
                                        }) Thread.sleep(100)
                                    }
                                }
                            }
                            Thread.sleep(2000)
                            device.takeScreenshot(File(context.getExternalFilesDir(null), "public-confirm-" + results.size + ".png"))
                            result = when {
                                events.any { it is LoginPageEvent.Failed } -> "PAGE_ERROR"
                                events.any { it is LoginPageEvent.Blocked && it.mainFrame } -> "BLOCKED_NAVIGATION"
                                events.any { it is LoginPageEvent.Visible } -> "PAGE_VISIBLE"
                                else -> "TIMEOUT"
                            }
                            // 只记录布尔诊断和字数，不输出网页正文、输入值、Cookie 或完整请求地址。
                            val latch = CountDownLatch(1)
                            inst.runOnMainSync {
                                owned?.view?.evaluateJavascript("(function(){var t=document.body?document.body.innerText:'';return JSON.stringify({textLength:t.length,environmentError:/环境异常|访问环境|访问异常/.test(t),hasPassword:!!document.querySelector('input[type=password]')});})()") { value -> dom = value; latch.countDown() }
                            }
                            latch.await(3, TimeUnit.SECONDS)
                        }
                    }
                } catch (_: Exception) { result = "TEST_ERROR" }
                finally { runBlocking { withContext(Dispatchers.Main) { owned?.close()?.await() } } }
                results.add(buildJsonObject {
                    put("name", source.name); put("source", source.id); put("public_page", result)
                    put("dom_diagnostic", dom)
                    put("confirmation_step", confirmation)
                    put("confirmed_origins", JsonArray(granted.map(::JsonPrimitive)))
                    put("errors", JsonArray(events.filterIsInstance<LoginPageEvent.Failed>().map { JsonPrimitive(it.reason) }))
                    put("blocked_origins", JsonArray((priorEvents + events).filterIsInstance<LoginPageEvent.Blocked>().mapNotNull { it.origin }.distinct().map(::JsonPrimitive)))
                    put("login", "NOT_TESTED"); put("search", "NOT_TESTED"); put("toc", "NOT_TESTED"); put("content", "NOT_TESTED")
                })
                // 每站只加载一次，不重试 403、不自动点登录或购买按钮。
                Thread.sleep(3000)
            }
        } finally {
            File(context.getExternalFilesDir(null), "public-login-report.json").writeText(Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(results)))
            inst.runOnMainSync { activity.finish() }
        }
    }
}
