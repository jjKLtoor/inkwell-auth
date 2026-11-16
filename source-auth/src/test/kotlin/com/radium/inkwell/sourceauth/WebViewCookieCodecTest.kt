package com.radium.inkwell.sourceauth

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewCookieCodecTest {
    @Test fun `Chromium规范化Domain不会扩大host-only且保留HttpOnly等属性`() {
        val url = "https://example.com/".toHttpUrl()
        val cookie = assertNotNull(parseWebViewCookie(url, "sid=fixture; domain=example.com; path=/member; secure; httponly"))
        assertTrue(cookie.hostOnly && cookie.httpOnly && cookie.secure)
        assertFalse(cookie.matches("https://sub.example.com/member".toHttpUrl()))
        assertTrue(cookie.matches("https://example.com/member".toHttpUrl()))
        assertNull(parseWebViewCookie(url, "sid=fixture; domain=evil.test; path=/"))
    }

    @Test fun `整域与分区Cookie明确区分`() {
        val url = "https://example.com/".toHttpUrl()
        val cookie = assertNotNull(parseWebViewCookie(url, "sid=fixture; domain=.example.com; path=/; secure"))
        assertFalse(cookie.hostOnly)
        assertNull(parseWebViewCookie(url, "sid=fixture; domain=example.com; path=/; secure; partitioned"))
    }
}
