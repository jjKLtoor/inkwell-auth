package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceRequestSession
import com.radium.inkwell.core.source.auth.SourceSessionProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/** 磁盘操作与撤销串行；旧请求即使迟到也不能重新写回已经退出的身份。 */
class PersistentSourceSessions(private val store: EncryptedSessionStore) : SourceSessionProvider {
    private val mutex = Mutex()
    private val sessions = ConcurrentHashMap<SourceAuthScope, Session>()

    fun storageFailed(scope: SourceAuthScope): Boolean = sessions[scope]?.storageFailed == true

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

    private inner class Session(override val scope: SourceAuthScope, initial: CookieSession, private val readable: Boolean = true) : SourceRequestSession {
        @Volatile override var active = true
            private set
        private val jar = SessionCookieJar(scope, initial)
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

        @Synchronized fun revoke() { active = false; jar.clear(); loginData.clear() }

        override suspend fun flush() = mutex.withLock {
            if (!readable || !active || sessions[scope] !== this) return@withLock
            // 登录信息可能含密码，只在内存使用；仅持久化认证 Header 与持久 Cookie。
            val snapshot = CookieSession(jar.snapshot(), loginData.filterKeys { it == "\$loginHeader" })
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
