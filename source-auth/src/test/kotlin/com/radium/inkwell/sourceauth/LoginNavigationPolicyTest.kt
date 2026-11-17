package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlin.test.*

class LoginNavigationPolicyTest {
    @Test fun 公开资源模式只允许HTTPS子资源且不放行登录跳转或私网() {
        val policy = LoginNavigationPolicy(scope)
        assertFalse(policy.permits("https://cdn.example.test/a.js", false))
        policy.allowPublicResources = true
        assertTrue(policy.permits("https://cdn.example.test/a.js", false))
        assertFalse(policy.permits("https://cdn.example.test/login", true))
        listOf("http://cdn.example.test/a.js", "https://127.0.0.1/a.js", "https://192.168.1.1/", "https://user:pass@example.test/").forEach {
            assertFalse(policy.permits(it, false))
        }
        assertFalse(scope.permits("https://cdn.example.test/"))
        policy.clear()
        assertFalse(policy.permits("https://cdn.example.test/a.js", false))
    }

    private val scope = requireNotNull(SourceAuthScope.create("fixture", "https://example.com/"))

    @Test fun 识别样式和脚本并优先提示缺失样式而非图片() {
        assertEquals(LoginResourceKind.STYLE, loginResourceKind("https://cdn.example.test/a.css?token=redacted", false))
        assertEquals(LoginResourceKind.STYLE, loginResourceKind("https://cdn.example.test/bundle", false, mapOf("Sec-Fetch-Dest" to "style")))
        assertEquals(LoginResourceKind.SCRIPT, loginResourceKind("https://cdn.example.test/a.js", false))
        assertEquals(LoginResourceKind.NAVIGATION, loginResourceKind("https://example.test/", true))
        assertTrue(LoginResourceKind.STYLE.priority < LoginResourceKind.IMAGE.priority)
    }

    @Test fun 仅可逐个批准已拦截的精确HTTPS站点且不改变认证scope() {
        val policy = LoginNavigationPolicy(scope)
        assertFalse(policy.approve("https://cdn.example.test/"))
        assertEquals("https://cdn.example.test/", policy.recordBlocked("https://cdn.example.test/a.js?token=redacted"))
        assertFalse(policy.permits("https://cdn.example.test/a.js"))
        assertTrue(policy.approve("https://cdn.example.test/"))
        assertTrue(policy.permits("https://cdn.example.test/next"))
        assertFalse(policy.permits("https://cdn.example.test:444/next"))
        assertFalse(scope.permits("https://cdn.example.test/"))
        policy.clear()
        assertFalse(policy.permits("https://cdn.example.test/next"))
        assertFalse(LoginNavigationPolicy(scope).permits("https://cdn.example.test/next"))
    }

    @Test fun 私网明文危险协议和含用户信息的地址不能临时批准() {
        val policy = LoginNavigationPolicy(scope)
        listOf("http://example.test/", "https://localhost/", "https://127.0.0.1/", "https://192.168.1.2/", "file:///tmp/a", "https://user:pass@example.test/").forEach {
            assertNull(policy.recordBlocked(it))
            assertFalse(policy.approve(it))
        }
    }
}
