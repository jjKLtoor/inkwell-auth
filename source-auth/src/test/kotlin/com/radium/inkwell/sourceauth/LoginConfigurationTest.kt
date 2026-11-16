package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class LoginConfigurationTest {
    private fun resolve(value: String?, ui: String? = null, base: String = "https://example.com/base/index#one") =
        resolveLoginConfiguration(BookSourceRule(bookSourceUrl = base, loginUrl = value, loginUi = ui))

    @Test
    fun `未配置登录不影响普通来源`() {
        listOf(null, "", "  ").forEach { assertEquals(LoginConfiguration.NotConfigured, resolve(it)) }
    }

    @Test
    fun `识别同 origin 的 HTTPS 页面与显式相对路径`() {
        mapOf(
            "https://example.com/login" to "https://example.com/login",
            "/login" to "https://example.com/login",
            "./login" to "https://example.com/base/login",
            "../login" to "https://example.com/login",
        ).forEach { (input, expected) ->
            assertEquals(expected, assertIs<LoginConfiguration.WebPage>(resolve(input)).url)
        }
    }

    @Test
    fun `脚本与动态规则不被当成网页路径`() {
        listOf("@js: result", "<js>login()</js>", "javascript:login()", "login()", "function login(){}",
            "https://example.com/{{token}}", "login.html").forEach {
            assertIs<LoginConfiguration.Unsupported>(resolve(it), it)
        }
    }

    @Test
    fun `自定义表单不被网页配置覆盖`() {
        assertEquals(
            LoginConfiguration.Unsupported(LoginConfiguration.Reason.CUSTOM_UI),
            resolve("https://example.com/login", "[]"),
        )
    }

    @Test
    fun `危险协议跨站端口用户信息及私网被拒绝`() {
        listOf("http://example.com/login", "file:///tmp/login", "intent://login", "data:text/html,login",
            "https://user:pass@example.com/", "//evil.test/login", "https://example.com.evil.test/",
            "https://sub.example.com/login", "https://example.com:444/login", "/a\\login",
            "/login page", "https:example.com/login", "https://127.0.0.1/", "https://[::1]/").forEach {
            assertIs<LoginConfiguration.Unsupported>(resolve(it), it)
        }
        assertIs<LoginConfiguration.Unsupported>(resolve("/login", base = "https://localhost/"))
        assertIs<LoginConfiguration.Unsupported>(resolve("/login", base = "https://192.168.1.1/"))
        assertIs<LoginConfiguration.Unsupported>(resolve("https://example.com/login", base = "https://user:pass@example.com/"))
    }

    @Test
    fun `网页登录配置默认输出不暴露 URL 中的敏感参数`() {
        val config = assertIs<LoginConfiguration.WebPage>(resolve("/login?token=fixture-secret"))
        assertFalse(config.toString().contains("fixture-secret"))
    }
}
