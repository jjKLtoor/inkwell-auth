package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlin.test.*

class LoginNavigationPolicyTest {
    private val scope = requireNotNull(SourceAuthScope.create("fixture", "https://example.com/"))

    @Test fun 公开HTTPS网页和资源自动加载但不扩展引擎认证范围() {
        val policy = LoginNavigationPolicy(scope)
        listOf("https://cdn.example.test/a.js", "https://login.example.test/member", "https://login.example.test:444/member").forEach {
            assertTrue(policy.permits(it))
            assertFalse(scope.permits(it))
        }
        assertTrue(scope.permits("https://example.com/member"))
    }

    @Test fun 明文私网危险协议及含用户信息的地址不允许自动访问() {
        val policy = LoginNavigationPolicy(scope)
        listOf("http://example.test/", "https://localhost/", "https://127.0.0.1/", "https://192.168.1.2/", "file:///tmp/a", "https://user:pass@example.test/").forEach {
            assertFalse(policy.permits(it))
            assertNull(policy.origin(it))
            assertFalse(policy.restore(it))
        }
        assertEquals("https://example.test/", policy.origin("https://example.test/login?token=fixture"))
    }

    @Test fun 旧版保存字段仅校验格式不再控制当前访问() {
        val policy = LoginNavigationPolicy(scope)
        assertTrue(policy.restore("https://old.example.test/"))
        assertFalse(policy.restore("https://old.example.test/path?token=fixture"))
        assertTrue(policy.permits("https://new.example.test/login"))
        assertFalse(scope.permits("https://old.example.test/"))
    }

    @Test fun 识别样式和脚本并避免把完整URL作为诊断信息() {
        assertEquals(LoginResourceKind.STYLE, loginResourceKind("https://cdn.example.test/a.css?token=redacted", false))
        assertEquals(LoginResourceKind.SCRIPT, loginResourceKind("https://cdn.example.test/a.js", false))
        assertEquals(LoginResourceKind.NAVIGATION, loginResourceKind("https://example.test/", true))
    }
}
