package com.radium.inkwell.sourceauth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 实际 Android Keystore 的落盘 / 新实例恢复测试；不能由 JVM AES 测试代替。 */
@RunWith(AndroidJUnit4::class)
class KeystoreSessionStoreTest {
    @Test fun keystore会话可恢复且退出仅删除指定密文() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "auth-test-${UUID.randomUUID()}")
        val scope = requireNotNull(SourceAuthScope.create("fixture", "https://example.com/"))
        try {
            val cookie = requireNotNull(Cookie.parse(scope.originUrl.toHttpUrl(), "sid=fixture-only; Secure; HttpOnly; Max-Age=3600"))
            EncryptedSessionStore(root, KeystoreSessionCipher()).save(scope, CookieSession(listOf(cookie)))
            assertFalse(root.listFiles()!!.single().readBytes().toString(Charsets.ISO_8859_1).contains("fixture-only"))
            val restored = EncryptedSessionStore(root, KeystoreSessionCipher())
            assertEquals("fixture-only", restored.load(scope)!!.cookies.single().value)
            restored.clear(scope)
            assertNull(restored.load(scope))
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
}
