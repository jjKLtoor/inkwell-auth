package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class CookieEditingTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = requireNotNull(SourceAuthScope.create("edit-fixture", "https://example.com/"))
    private val key = KeyGenerator.getInstance("AES").generateKey()
    private val cipher = AesGcmSessionCipher { key }
    private fun cookie(name: String = "sid", value: String = "fixture-old") = Cookie.Builder().name(name).value(value)
        .hostOnlyDomain("example.com").path("/member").secure().httpOnly().expiresAt(System.currentTimeMillis() + 3600000).build()

    @Test fun 修改保留属性及其他Cookie且旧请求不能恢复旧值() = runBlocking {
        val store = EncryptedSessionStore(temporary.newFolder(), cipher)
        val sessions = PersistentSourceSessions(store)
        sessions.replace(scope, CookieSession(listOf(cookie(), cookie("other", "keep")), loginData = mapOf("\$loginHeader" to "fixture-header"), loginPageUrl = "https://example.com/member"))
        val old = sessions.open(scope)
        old.loginData["password"] = "memory-only-fixture"
        val expected = store.load(scope)!!.cookies.first { it.name == "sid" }
        val result = sessions.editCookie(scope, expected, "fixture-new")!!
        val edited = result.cookies.first { it.name == "sid" }
        assertEquals(expected.domain, edited.domain); assertEquals(expected.path, edited.path)
        assertEquals(expected.hostOnly, edited.hostOnly); assertEquals(expected.secure, edited.secure)
        assertEquals(expected.httpOnly, edited.httpOnly); assertTrue(edited.persistent)
        assertEquals("keep", result.cookies.single { it.name == "other" }.value)
        assertEquals("https://example.com/member", result.loginPageUrl)
        assertFalse(old.active)
        old.saveFromResponse("https://example.com/member".toHttpUrl(), listOf(cookie(value = "late"))); old.flush()
        assertEquals("fixture-new", store.load(scope)!!.cookies.first { it.name == "sid" }.value)
        assertEquals(expected.expiresAt, store.load(scope)!!.cookies.first { it.name == "sid" }.expiresAt)
        assertFalse(store.load(scope)!!.loginData.containsKey("password"))
        assertEquals("fixture-header", store.load(scope)!!.loginData["\$loginHeader"])
        assertEquals("fixture-new", sessions.open(scope).loadForRequest("https://example.com/member".toHttpUrl()).first { it.name == "sid" }.value)
        assertTrue(sessions.open(scope).loadForRequest("https://other.example.test/".toHttpUrl()).isEmpty())
    }

    @Test fun 拒绝分隔符注入和过期编辑快照且不撤销当前会话() = runBlocking {
        val store = EncryptedSessionStore(temporary.newFolder(), cipher)
        val sessions = PersistentSourceSessions(store)
        sessions.replace(scope, CookieSession(listOf(cookie())))
        val active = sessions.open(scope)
        val expected = store.load(scope)!!.cookies.single()
        for (value in listOf("a;b", "a\r\nCookie: x", "contains space", "a,b", "中文")) {
            assertFailsWith<IllegalArgumentException> { sessions.editCookie(scope, expected, value) }
            assertTrue(active.active)
        }
        active.saveFromResponse("https://example.com/member".toHttpUrl(), listOf(cookie(value = "rotated")))
        assertFailsWith<IllegalStateException> { sessions.editCookie(scope, expected, "stale-edit") }
        assertTrue(active.active)
        assertEquals("rotated", sessions.savedSnapshot(scope)!!.cookies.single().value)
    }

    @Test fun 写入失败保留旧密文及内存而迟到响应不能复活() = runBlocking {
        var failWrites = false
        val store = EncryptedSessionStore(temporary.newFolder(), object : SessionCipher {
            override fun encrypt(plain: ByteArray, binding: ByteArray): ByteArray { check(!failWrites); return cipher.encrypt(plain, binding) }
            override fun decrypt(encrypted: ByteArray, binding: ByteArray) = cipher.decrypt(encrypted, binding)
        })
        val sessions = PersistentSourceSessions(store)
        sessions.replace(scope, CookieSession(listOf(cookie())))
        val expected = store.load(scope)!!.cookies.single()
        val old = sessions.open(scope)
        failWrites = true
        assertFailsWith<IllegalStateException> { sessions.editCookie(scope, expected, "unsaved") }
        assertEquals("fixture-old", store.load(scope)!!.cookies.single().value)
        assertEquals("fixture-old", sessions.open(scope).loadForRequest("https://example.com/member".toHttpUrl()).single().value)
        old.saveFromResponse("https://example.com/member".toHttpUrl(), listOf(cookie(value = "late")))
        assertEquals("fixture-old", sessions.savedSnapshot(scope)!!.cookies.single().value)
    }

    @Test fun 删除最后Cookie仅清除当前会话而保留其他来源() = runBlocking {
        val store = EncryptedSessionStore(temporary.newFolder(), cipher)
        val sessions = PersistentSourceSessions(store)
        val other = requireNotNull(SourceAuthScope.create("other-fixture", "https://example.com/"))
        sessions.replace(scope, CookieSession(listOf(cookie())))
        sessions.replace(other, CookieSession(listOf(cookie(value = "other-account"))))
        assertNull(sessions.editCookie(scope, store.load(scope)!!.cookies.single(), null))
        assertNull(store.load(scope))
        assertFalse(sessions.open(scope).hasCredentials)
        assertEquals("other-account", store.load(other)!!.cookies.single().value)
    }
}
