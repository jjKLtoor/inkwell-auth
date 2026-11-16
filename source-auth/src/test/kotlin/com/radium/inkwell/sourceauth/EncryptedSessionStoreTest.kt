package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncryptedSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = AesGcmSessionCipher { key }
    private val scope = requireNotNull(SourceAuthScope.create("https://example.com/#one", "https://example.com/"))
    private fun persistent(value: String = "fixture-secret", path: String = "/") = requireNotNull(
        Cookie.parse("https://example.com/".toHttpUrl(), "sid=$value; Path=$path; Secure; HttpOnly; Max-Age=3600"),
    )

    @Test fun `密文重启恢复保留属性且文件和默认输出不含凭据`() = runBlocking {
        val root = temporary.newFolder()
        EncryptedSessionStore(root, cipher).save(scope, CookieSession(listOf(persistent(path = "/member"))))
        val files = requireNotNull(root.listFiles())
        assertEquals(1, files.size)
        assertFalse(files.single().readBytes().toString(Charsets.ISO_8859_1).contains("fixture-secret"))
        assertFalse(files.single().name.contains("example"))
        val restored = assertNotNull(EncryptedSessionStore(root, cipher).load(scope))
        val cookie = restored.cookies.single()
        assertTrue(cookie.hostOnly && cookie.secure && cookie.httpOnly && cookie.persistent)
        assertEquals("/member", cookie.path)
        assertEquals("fixture-secret", cookie.value)
        assertFalse(restored.toString().contains("fixture-secret"))
    }

    @Test fun `同域来源之间不能互换密文`() = runBlocking {
        val root = temporary.newFolder()
        val store = EncryptedSessionStore(root, cipher)
        val other = requireNotNull(SourceAuthScope.create("https://example.com/#two", "https://example.com/"))
        store.save(scope, CookieSession(listOf(persistent("one"))))
        val first = root.listFiles()!!.single()
        store.save(other, CookieSession(listOf(persistent("two"))))
        val second = root.listFiles()!!.single { it != first }
        first.copyTo(second, overwrite = true)
        assertFailsWith<Exception> { store.load(other) }
        assertEquals("one", store.load(scope)!!.cookies.single().value)
    }

    @Test fun `损坏或错误密钥不会回退明文或删除已有文件`() = runBlocking {
        val root = temporary.newFolder()
        val store = EncryptedSessionStore(root, cipher)
        store.save(scope, CookieSession(listOf(persistent())))
        val wrongKey = KeyGenerator.getInstance("AES").generateKey()
        assertFailsWith<Exception> { EncryptedSessionStore(root, AesGcmSessionCipher { wrongKey }).load(scope) }
        val file = root.listFiles()!!.single()
        val data = file.readBytes()
        data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
        file.writeBytes(data)
        assertFailsWith<Exception> { store.load(scope) }
        assertTrue(file.exists())
    }

    @Test fun `取消不写入以及加密失败都保留旧会话`() = runBlocking {
        val root = temporary.newFolder()
        val store = EncryptedSessionStore(root, cipher)
        store.save(scope, CookieSession(listOf(persistent("old"))))
        // 取消流程不调用 save；新登录的内存材料不会覆盖已有文件。
        CookieSession(listOf(persistent("cancelled")))
        val unavailableCipher = object : SessionCipher {
            override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray = error("fixture key unavailable")
            override fun decrypt(encrypted: ByteArray, binding: ByteArray): ByteArray = error("fixture key unavailable")
        }
        assertFailsWith<Exception> { EncryptedSessionStore(root, unavailableCipher).save(scope, CookieSession(listOf(persistent("new")))) }
        assertEquals("old", store.load(scope)!!.cookies.single().value)
        assertEquals(1, root.listFiles()!!.size)
        assertFailsWith<IllegalArgumentException> { store.save(scope, CookieSession(emptyList())) }
        assertEquals("old", store.load(scope)!!.cookies.single().value)
    }

    @Test fun `session cookie与过期Cookie不跨进程保存且退出不影响其他来源`() = runBlocking {
        val root = temporary.newFolder()
        val store = EncryptedSessionStore(root, cipher)
        val sessionCookie = requireNotNull(Cookie.parse(scope.originUrl.toHttpUrl(), "temp=fixture; Path=/"))
        val expired = requireNotNull(Cookie.parse(scope.originUrl.toHttpUrl(), "old=fixture; Max-Age=0"))
        val other = requireNotNull(SourceAuthScope.create("two", "https://example.com/"))
        store.save(scope, CookieSession(listOf(persistent(), sessionCookie, expired)))
        store.save(other, CookieSession(listOf(persistent("other"))))
        assertEquals(1, store.load(scope)!!.cookies.size)
        val sentinel = File(root.parentFile, "books-fixture.txt").apply { writeText("reading progress") }
        store.clear(scope)
        store.clear(scope)
        assertNull(store.load(scope))
        assertEquals("other", store.load(other)!!.cookies.single().value)
        assertEquals("reading progress", sentinel.readText())
    }

    @Test fun `越界域名Cookie不能保存`() = runBlocking {
        val root = temporary.newFolder()
        val cookie = Cookie.Builder().name("sid").value("fixture").domain("evil.test").expiresAt(Long.MAX_VALUE).build()
        assertFailsWith<IllegalArgumentException> { EncryptedSessionStore(root, cipher).save(scope, CookieSession(listOf(cookie))) }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
