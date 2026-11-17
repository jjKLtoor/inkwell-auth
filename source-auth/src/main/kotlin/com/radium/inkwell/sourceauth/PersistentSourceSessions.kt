package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceRequestSession
import com.radium.inkwell.core.source.auth.SourceSessionProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** 磁盘操作与撤销串行；旧请求即使迟到也不能重新写回已经退出的身份。 */
class PersistentSourceSessions(private val store: EncryptedSessionStore) : SourceSessionProvider {
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<SourceAuthScope, Session>()

    fun storageFailed(scope: SourceAuthScope): Boolean = sessions[scope]?.storageFailed == true

    override suspend fun forSource(source: com.radium.inkwell.core.source.BookSourceRule): SourceRequestSession? =
        sourceAuthScope(source)?.let { open(it) }

    override suspend fun open(scope: SourceAuthScope): SourceRequestSession = mutex.withLock {
        sessions[scope] ?: run {
            var readable = true
            val restored = try { store.load(scope) } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // 密钥损坏不能阻断普通来源，也不能用空会话覆盖尚可人工恢复的密文。
                readable = false
                null
            }
            Session(scope, restored ?: CookieSession(emptyList()), readable).also { sessions[scope] = it }
        }
    }

    suspend fun replace(scope: SourceAuthScope, cookies: CookieSession) = mutex.withLock {
        store.save(scope, cookies)
        sessions.remove(scope)?.revoke()
        sessions[scope] = Session(scope, cookies)
    }

    suspend fun logout(scope: SourceAuthScope) = mutex.withLock {
        sessions.remove(scope)?.revoke()
        store.clear(scope)
    }

    suspend fun savedSnapshot(scope: SourceAuthScope): CookieSession? = mutex.withLock {
        val saved = sessions[scope]?.snapshot()?.let(::persistentSnapshot) ?: store.load(scope)
        saved?.takeIf { it.cookies.isNotEmpty() || it.loginData.isNotEmpty() }
    }

    /** 编辑只改选中 Cookie 的值或删除该条，原属性、其他 Cookie 与认证 Header 保持原样。 */
    suspend fun editCookie(scope: SourceAuthScope, expected: Cookie, value: String?): CookieSession? = withContext(NonCancellable) { mutex.withLock {
        val old = sessions[scope]
        // 先校验，失败不撤销现有会话；冻结后迟到的网络响应不能把旧值写回。
        val plan = old?.beginEdit(expected, value) ?: (store.load(scope) ?: error("没有可编辑的会话")).let {
            CookieEdit(it, changedCookie(it, expected, value))
        }
        val changed = plan.after
        val saved = persistentSnapshot(changed)
        try {
            if (saved.cookies.isEmpty() && saved.loginData.isEmpty()) store.clear(scope) else store.save(scope, saved)
            sessions[scope] = Session(scope, changed)
            saved.takeIf { it.cookies.isNotEmpty() || it.loginData.isNotEmpty() }
        } catch (error: Exception) {
            // 原子写失败保留旧密文，并恢复编辑前的内存快照；旧请求引用仍保持撤销。
            if (old != null) sessions[scope] = Session(scope, plan.before)
            throw error
        }
    } }

    private class CookieEdit(val before: CookieSession, val after: CookieSession)

    private fun persistentSnapshot(value: CookieSession) = CookieSession(
        value.cookies.filter { it.persistent && it.expiresAt > System.currentTimeMillis() },
        value.loginData.filterKeys { it == "\$loginHeader" }, value.webOrigins, value.allowWebResources, value.loginPageUrl,
    )

    private fun changedCookie(session: CookieSession, expected: Cookie, value: String?): CookieSession {
        require(value == null || value.length <= 8192 && validCookieValue(value)) { "Cookie 值格式不正确" }
        val current = session.cookies.firstOrNull { it.name == expected.name && it.domain == expected.domain && it.path == expected.path }
        check(current != null && current.value == expected.value && current.expiresAt > System.currentTimeMillis()) { "Cookie 已变化，请刷新后重试" }
        val replacement = value?.let {
            Cookie.Builder().name(current.name).value(it).path(current.path).apply {
                if (current.hostOnly) hostOnlyDomain(current.domain) else domain(current.domain)
                if (current.persistent) expiresAt(current.expiresAt)
                if (current.secure) secure()
                if (current.httpOnly) httpOnly()
            }.build()
        }
        return CookieSession(session.cookies.mapNotNull { if (it === current) replacement else it },
            session.loginData, session.webOrigins, session.allowWebResources, session.loginPageUrl)
    }

    private fun validCookieValue(value: String): Boolean {
        val body = if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.lastIndex) else value
        return body.all { it == '!' || it in '#'..'+' || it in '-'..':' || it in '<'..'[' || it in ']'..'~' }
    }

    private inner class Session(override val scope: SourceAuthScope, initial: CookieSession, private val readable: Boolean = true) : SourceRequestSession {
        @Volatile override var active = true
            private set
        private val jar = SessionCookieJar(scope, initial)
        private val webOrigins = initial.webOrigins
        private val allowWebResources = initial.allowWebResources
        private val loginPageUrl = initial.loginPageUrl
        override val loginData: MutableMap<String, String> = ConcurrentHashMap(initial.loginData)
        override val hasCredentials: Boolean
            get() = active && (loginData.isNotEmpty() || jar.snapshot().any { it.expiresAt > System.currentTimeMillis() })
        @Volatile var storageFailed = !readable
            private set
        private var persisted = fingerprint(initial)

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (active) jar.loadForRequest(url) else emptyList()

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (active) jar.saveFromResponse(url, cookies)
        }

        @Synchronized override fun cookiesForRendering(): List<Cookie> =
            if (active) jar.snapshot().filter { it.expiresAt > System.currentTimeMillis() } else emptyList()

        @Synchronized override fun mergeRenderedCookies(original: List<Cookie>, current: List<Cookie>, observed: List<HttpUrl>) {
            if (!active) return
            val before = original.associateBy(::cookieIdentity)
            val after = current.associateBy(::cookieIdentity)
            val live = jar.snapshot().associateBy(::cookieIdentity)
            for (id in before.keys + after.keys) {
                // 网络请求已刷新或用户已手动编辑时，渲染器的旧副本不能反向覆盖。
                if (live[id] != before[id]) continue
                val next = after[id]
                val old = before[id]
                val updated = next ?: old?.takeIf { cookie -> observed.any { cookie.matches(it) } }?.newBuilder()?.expiresAt(0)?.build() ?: continue
                val origin = observed.firstOrNull { sessionUrl -> scope.permits(sessionUrl.toString()) && updated.matches(sessionUrl) } ?: continue
                jar.saveFromResponse(origin, listOf(updated))
            }
        }

        @Synchronized fun revoke() { active = false; jar.clear(); loginData.clear() }

        @Synchronized fun snapshot() = CookieSession(jar.snapshot(), loginData.toMap(), webOrigins, allowWebResources, loginPageUrl)

        @Synchronized fun beginEdit(expected: Cookie, value: String?): CookieEdit {
            val before = snapshot()
            val result = CookieEdit(before, changedCookie(before, expected, value))
            revoke()
            return result
        }

        override suspend fun flush() = mutex.withLock {
            if (!readable || !active || sessions[scope] !== this) return@withLock
            // 登录信息可能含密码，只在内存使用；仅持久化认证 Header 与持久 Cookie。
            val snapshot = CookieSession(jar.snapshot(), loginData.filterKeys { it == "\$loginHeader" }, webOrigins, allowWebResources, loginPageUrl)
            val next = fingerprint(snapshot)
            if (next != persisted) {
                try {
                    if (snapshot.cookies.none { it.persistent && it.expiresAt > System.currentTimeMillis() } && snapshot.loginData.isEmpty()) store.clear(scope)
                    else store.save(scope, snapshot)
                    persisted = next
                    storageFailed = false
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // 会话落盘失败不阻断普通阅读；保留旧密文，登录页展示保存失败状态。
                    storageFailed = true
                }
            }
        }

        private fun fingerprint(session: CookieSession): Pair<List<String>, Map<String, String>> =
            session.cookies.filter { it.persistent }.map { it.toString() }.sorted() to session.loginData
    }
}
