package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.radium.inkwell.MainActivity
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.sourceauth.*
import com.radium.inkwell.ui.theme.InkwellTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test

class SourceCookieScreenTest {
    @Test fun Cookie默认隐藏可查看编辑加密保存并逐条删除() = runBlocking {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val root = File(context.noBackupFilesDir, "cookie-ui-" + UUID.randomUUID())
        val scope = requireNotNull(SourceAuthScope.create("cookie-ui", "https://example.com/"))
        val store = EncryptedSessionStore(root, KeystoreSessionCipher())
        val sessions = PersistentSourceSessions(store)
        val cookie = Cookie.Builder().name("sid").value("fixture-secret-old").hostOnlyDomain("example.com").path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 3600000).build()
        sessions.replace(scope, CookieSession(listOf(cookie)))
        val edits = java.util.concurrent.atomic.AtomicInteger()
        val state = mutableStateOf(SourceLoginState(loaded = true, scope = scope, restored = store.load(scope), message = "本地 Cookie，登录状态未知"))
        val activity = inst.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            inst.runOnMainSync { activity.setContent { InkwellTheme {
                SourceCookieScreen(state.value, {}, {}, { selected, value -> activity.lifecycleScope.launch {
                    state.value = state.value.copy(restored = sessions.editCookie(scope, selected, value), message = "已保存")
                    edits.incrementAndGet()
                } })
            } } }
            val device = UiDevice.getInstance(inst)
            if (!device.wait(Until.hasObject(By.text("显示值")), 2000)) androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollTextIntoView("显示值")
            assertNotNull(device.wait(Until.findObject(By.text("显示值")), 10000))
            assertFalse(device.hasObject(By.text("fixture-secret-old")))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "cookieui-masked.png"))
            device.findObject(By.text("显示值")).click()
            assertTrue(device.wait(Until.hasObject(By.text("fixture-secret-old")), 10000))
            device.findObject(By.text("隐藏值")).click()
            device.findObject(By.text("编辑值")).click()
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10000)
            assertNotNull(input)
            input.text = "fixture-secret-new"
            device.findObject(By.text("保存")).click()
            kotlinx.coroutines.withTimeout(10000) { while (edits.get() < 1) kotlinx.coroutines.delay(100) }
            assertEquals("fixture-secret-new", store.load(scope)!!.cookies.single().value)
            assertTrue(store.load(scope)!!.cookies.single().hostOnly)
            device.wait(Until.findObject(By.text("删除")), 10000).click()
            assertNotNull(device.wait(Until.findObject(By.text("删除此 Cookie？")), 10000))
            device.findObject(By.text("删除")).click()
            kotlinx.coroutines.withTimeout(10000) { while (edits.get() < 2) kotlinx.coroutines.delay(100) }
            if (!device.hasObject(By.text("暂无已保存的 Cookie"))) androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollTextIntoView("暂无已保存的 Cookie")
            assertTrue(device.wait(Until.hasObject(By.text("暂无已保存的 Cookie")), 10000))
            assertNull(store.load(scope))
        } finally {
            inst.runOnMainSync { activity.finish() }
            sessions.logout(scope)
            root.delete()
        }
    }
}
