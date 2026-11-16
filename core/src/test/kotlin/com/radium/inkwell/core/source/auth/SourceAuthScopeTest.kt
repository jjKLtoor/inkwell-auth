package com.radium.inkwell.core.source.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceAuthScopeTest {
    @Test
    fun `作用域按规范化 origin 匹配而不是字符串前缀`() {
        val scope = assertNotNull(SourceAuthScope.create("source#one", "https://EXAMPLE.com:443/base#one"))
        assertTrue(scope.permits("https://example.com/chapter?q=1"))
        listOf(
            "https://example.com.evil.test/", "https://sub.example.com/", "https://example.com:444/",
            "http://example.com/", "https://user:secret@example.com/", "file:///example.com/", "invalid",
        ).forEach { assertFalse(scope.permits(it), it) }
    }

    @Test
    fun `同域多来源与来源换域保留不同身份`() {
        val first = SourceAuthScope.create("source#one", "https://example.com/a")
        assertEquals(first, SourceAuthScope.create("source#one", "https://example.com/b?q=2"))
        assertNotEquals(first, SourceAuthScope.create("source#two", "https://example.com/a"))
        assertNotEquals(first, SourceAuthScope.create("source#one", "https://new.example.com/a"))
    }

    @Test
    fun `无效来源及明文凭据地址不能创建作用域`() {
        assertNull(SourceAuthScope.create(" ", "https://example.com/"))
        listOf("invalid", "http://example.com/", "https://user:password@example.com/", "file:///tmp/a")
            .forEach { assertNull(SourceAuthScope.create("source", it), it) }
    }

    @Test
    fun `无认证实现不产生任何会话`() {
        val scope = assertNotNull(SourceAuthScope.create("source", "https://example.com/"))
        assertNull(SourceAuthContext.NONE.snapshot(scope, "https://example.com/"))
    }

    @Test
    fun `快照复制请求头且默认输出不含凭据`() {
        val headers = mutableMapOf("Authorization" to "secret-token")
        val snapshot = SourceAuthSnapshot(loginInfo = "secret-password", loginHeaders = headers)
        headers.clear()
        assertEquals("secret-token", snapshot.loginHeaders["Authorization"])
        assertFailsWith<UnsupportedOperationException> {
            (snapshot.loginHeaders as MutableMap<String, String>)["Authorization"] = "changed"
        }
        assertEquals(SourceAuthStatus.UNKNOWN, snapshot.status)
        assertFalse(snapshot.toString().contains("secret"))
        val scope = assertNotNull(SourceAuthScope.create("secret-source", "https://example.com/"))
        assertFalse(scope.toString().contains("secret"))
    }
}
