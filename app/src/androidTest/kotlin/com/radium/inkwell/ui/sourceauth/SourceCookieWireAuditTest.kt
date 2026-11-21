package com.radium.inkwell.ui.sourceauth

import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceRequestSession
import com.radium.inkwell.core.source.auth.SourceSessionProvider
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.sourceauth.CookieSession
import com.radium.inkwell.sourceauth.EncryptedSessionStore
import com.radium.inkwell.sourceauth.SessionCookieJar
import com.radium.inkwell.sourceauth.sourceAuthScope
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** 手工指定来源和阶段才探测一次搜索。使用只读快照，凭据比对只在内存。 */
class SourceCookieWireAuditTest {
    @Test(timeout = 50000) fun 搜索请求与本地会话对照() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("auditSource")
        val stage = args.getString("auditStage")
        assumeTrue(!name.isNullOrBlank() && stage in setOf("saved", "guest", "deleted", "restart", "relogin"))
        val koin = GlobalContext.get()
        val repo = koin.get<BookSourceRepository>()
        val row = repo.sources.first().single { it.name == name }
        val rule = requireNotNull(repo.getRule(row.id))
        val scope = requireNotNull(sourceAuthScope(rule))
        val disk = koin.get<EncryptedSessionStore>().load(scope)
        val snapshot = if (stage == "guest") CookieSession(emptyList()) else disk ?: CookieSession(emptyList())
        val jar = SessionCookieJar(scope, snapshot)
        val localHeaders = runCatching {
            Json.parseToJsonElement(snapshot.loginData["\$loginHeader"] ?: "{}").jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
        val session = object : SourceRequestSession, okhttp3.CookieJar by jar {
            override val scope = scope
            override val active = true
            override val hasCredentials get() = jar.snapshot().isNotEmpty() || loginData.isNotEmpty()
            override val loginData = java.util.concurrent.ConcurrentHashMap(snapshot.loginData)
            override suspend fun flush() = Unit
        }
        val requests = ConcurrentLinkedQueue<JsonObject>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun requestHeadersEnd(call: Call, request: Request) {
                if (requests.size >= 16) return
                val sentCookies = request.headers.values("Cookie").flatMap { it.split(';') }.map(String::trim)
                requests.add(buildJsonObject {
                    put("host", request.url.host)
                    put("cookiePairs", sentCookies.count(String::isNotEmpty))
                    put("savedCookiePairs", snapshot.cookies.count { "${it.name}=${it.value}" in sentCookies })
                    put("authorization", request.header("Authorization") != null)
                    put("savedHeaderMatches", localHeaders.count { (key, value) -> request.header(key) == value })
                })
            }
        }).build()
        val provider = SourceSessionProvider { requested: SourceAuthScope ->
            require(requested.sourceId == scope.sourceId)
            session
        }
        val engine = BookSourceEngine(SourceHttpClient(client), scriptRuntime = koin.get<ScriptRuntime>(), sessions = provider)
        val result = buildJsonObject {
            put("stage", stage); put("savedCookieCount", disk?.cookies?.size ?: 0)
            put("savedHeaderCount", localHeaders.size)
            try {
                val page = withTimeout(30000) { engine.search(rule, args.getString("auditKeyword") ?: "剑") }
                put("searchCount", page.items.size)
            } catch (error: Exception) {
                put("errorType", error.javaClass.simpleName)
                Regex("HTTP\\s+(\\d{3})").find(error.message.orEmpty())?.let { put("httpStatus", it.groupValues[1].toInt()) }
            }
            put("requests", JsonArray(requests.toList()))
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "source-cookie-wire-$stage.json").writeText(result.toString())
    }
}
