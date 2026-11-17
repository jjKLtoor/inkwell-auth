package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import kotlin.test.*

class WebLoginTargetTest {
    @Test fun 只提取真实语法树中的唯一静态浏览器地址() {
        val source = BookSourceRule("https://example.com", loginUrl = """
            // java.startBrowserAwait("https://ignored.test/")
            function login() { java.startBrowserAwait("https://example.com/login", "登录"); }
        """.trimIndent(), loginUi = "[{name:'登录',type:'button',action:'login()'}]")
        val page = assertNotNull(webLoginTarget(source))
        assertTrue(page.partial)
        assertEquals("https://example.com/login", page.url)
        assertNull(webLoginTarget(source.copy(loginUrl = "java.startBrowserAwait(computeUrl())")))
        assertNull(webLoginTarget(source.copy(loginUrl = "java.startBrowser('https://one.test');java.startBrowser('https://two.test')")))
    }

    @Test fun 仅增加声明的登录origin且域名变化产生不同身份() {
        val source = BookSourceRule("https://reader.example.com", loginUrl = "https://login.example.com/member")
        val scope = assertNotNull(sourceAuthScope(source))
        assertTrue(scope.permits("https://login.example.com/path"))
        assertTrue(scope.permitsPrimary("https://reader.example.com/path"))
        assertFalse(scope.permitsPrimary("https://login.example.com/"))
        assertFalse(scope.permits("https://other.example.com/"))
        assertFalse(scope.permits("https://login.example.com:444/"))
        assertNotEquals(scope, sourceAuthScope(source.copy(loginUrl = "https://different.example.com/")))
    }
}
