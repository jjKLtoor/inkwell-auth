package com.radium.inkwell.sourceauth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.CookieManagerCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import com.radium.inkwell.core.source.auth.SourceAuthScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 使用实际 Chromium profile 和完整 Cookie API；只做本地夹具，不访问真实账号或网站。 */
@RunWith(AndroidJUnit4::class)
class LoginWebSessionTest {
    @Test fun 临时profile与默认profile隔离且Cookie属性在采集后保留() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = requireNotNull(SourceAuthScope.create("fixture-one", "https://example.com/"))
        withContext(Dispatchers.Main) {
            assumeTrue(LoginWebSession.supported())
            var session: LoginWebSession? = null
            try {
                session = LoginWebSession(instrumentation.targetContext, scope, { null }, { error("unexpected page error") })
                val profile = WebViewCompat.getProfile(session.view)
                val profileName = profile.name
                // 原生 Cookie API 写入测试材料，不依赖外部网络；用 suspend 回调确保写入完成。
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    profile.cookieManager.setCookie(scope.originUrl, "auth_fixture=local-only; Path=/; Secure; HttpOnly; Max-Age=3600") {
                        continuation.resumeWith(Result.success(Unit))
                    }
                }
                val info = CookieManagerCompat.getCookieInfo(profile.cookieManager, scope.originUrl)
                assertEquals(1, info.size)
                assertTrue(info.single().contains("HttpOnly", ignoreCase = true))
                assertTrue(requireNotNull(parseWebViewCookie(scope.originUrl.toHttpUrl(), info.single())).hostOnly)
                val captured = session.capture().cookies.single()
                assertTrue(captured.hostOnly && captured.httpOnly && captured.secure && captured.persistent)
                assertFalse(android.webkit.CookieManager.getInstance().getCookie(scope.originUrl).orEmpty().contains("auth_fixture"))
                session.close().await()
                assertTrue(CookieManagerCompat.getCookieInfo(profile.cookieManager, scope.originUrl).isEmpty())
                // 当前进程保留已清空的 profile，重启后才能删除；不能把销毁视图等同于删除数据。
                assertTrue(ProfileStore.getInstance().allProfileNames.contains(profileName))
                LoginWebSession.cleanupOrphans()
                session = null
            } finally { session?.close() }
        }
    }
}
