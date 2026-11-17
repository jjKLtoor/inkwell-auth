package com.radium.inkwell.ui.sourceauth

import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.data.source.WebViewPageRenderer
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.sourceauth.PersistentSourceSessions
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** 显式目标的开发诊断。只保存状态、数量和页面类型，不导出规则、请求头、账号或页面正文。 */
class SourceSiteDiagnosticTest {
    @Test fun 指定来源的已有校验与搜索页诊断() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val progress = File(inst.targetContext.getExternalFilesDir(null), "source-site-diagnostic-progress.txt")
        fun phase(value: String) { progress.writeText(value) }
        phase("START")
        val names = args.getString("diagnosticNames")?.split(',')?.filter { it.isNotBlank() }
        assumeTrue(!names.isNullOrEmpty())
        require(names!!.size <= 8)
        val koin = GlobalContext.get()
        val repo = koin.get<BookSourceRepository>()
        val items = repo.sources.first().filter { it.name in names }
        phase("SOURCES_READY")
        val renderRequests = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
        val sessions = koin.get<PersistentSourceSessions>()
        val renderer = WebViewPageRenderer(inst.targetContext, authenticatedGuard = {
            val guard = WebViewNetworkGuard()
            val intercept: (android.webkit.WebResourceRequest) -> android.webkit.WebResourceResponse? = { request ->
                if (renderRequests.size < 64) renderRequests.add(buildJsonObject {
                    put("method", request.method.takeIf { it in setOf("GET", "HEAD", "POST", "OPTIONS") } ?: "OTHER")
                    put("main", request.isForMainFrame)
                    put("pathKind", when {
                        request.url.path.orEmpty().endsWith(".js") -> "SCRIPT"
                        request.url.path.orEmpty().endsWith(".css") -> "STYLE"
                        request.isForMainFrame -> "DOCUMENT"
                        else -> "OTHER"
                    })
                })
                guard.intercept(request)
            }
            intercept
        })
        val engine = BookSourceEngine(koin.get<SourceHttpClient>(), scriptRuntime = koin.get<ScriptRuntime>(), renderer = renderer, sessions = sessions)
        val evaluator = RuleEvaluator(koin.get<ScriptRuntime>())
        fun origin(url: String) = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }.orEmpty()
        fun category(message: String): String = when {
            "HTTP" in message -> Regex("HTTP\\s*(\\d{3})").find(message)?.value ?: "HTTP_ERROR"
            "搜索无结果" in message -> "NO_SEARCH_RESULTS"
            "超时" in message -> "TIMEOUT"
            "证书" in message -> "CERTIFICATE"
            "范围" in message -> "AUTH_SCOPE"
            "重定向" in message -> "REDIRECT_UNSUPPORTED"
            "请求方式" in message -> "METHOD_UNSUPPORTED"
            "未匹配" in message -> "RULE_NO_MATCH"
            else -> "OTHER"
        }
        val result = items.map { item ->
            phase("READ_RULE")
            val source = requireNotNull(repo.getRule(item.id))
            phase("READ_SESSION")
            val session = sessions.forSource(source)
            phase("SESSION_READY")
            renderRequests.clear()
            buildJsonObject {
                put("name", item.name); put("savedCheckStatus", item.checkStatus)
                put("savedReason", category(item.checkMessage))
                put("savedStep", listOf("搜索", "详情", "目录", "正文").firstOrNull { it in item.checkMessage }.orEmpty())
                put("sourceOrigin", origin(source.baseUrl))
                put("activeCookieCount", session?.cookiesForRendering()?.size ?: 0)
                put("searchRuleSha256", MessageDigest.getInstance("SHA-256").digest((source.searchUrl.orEmpty()+source.ruleSearch.toString()).toByteArray()).joinToString("") { "%02x".format(it) })
                if (args.getString("diagnosticProbe") == "search" && item.name in args.getString("probeNames").orEmpty().split(',')) {
                    try {
                        phase("FETCH_SEARCH")
                        val page = withTimeout(35000) { engine.fetchSearchPage(source, args.getString("keyword") ?: "剑") }
                        phase("SEARCH_RECEIVED")
                        val ctx = EvalContext.page(page.bodyText, page.finalUrl)
                        fun count(css: String) = evaluator.evalToNodes(LegadoRuleAnalyzer.analyze("@css:$css"), ctx).size
                        val title = evaluator.evalToString(LegadoRuleAnalyzer.analyze("title@text"), ctx).orEmpty()
                        val visibleText = evaluator.evalToString(LegadoRuleAnalyzer.analyze("body@text"), ctx).orEmpty()
                        put("httpStatus", page.statusCode); put("bodyChars", page.bodyText.length)
                        put("finalOrigin", origin(page.finalUrl))
                        put("titleKind", when {
                            Regex("(?i)captcha|challenge|验证|安全|访问限制|access denied").containsMatchIn(title) -> "CHALLENGE_OR_RESTRICTION"
                            Regex("(?i)login|登录").containsMatchIn(title) -> "LOGIN"
                            Regex("(?i)search|搜索").containsMatchIn(title) -> "SEARCH"
                            title.isEmpty() -> "EMPTY"
                            else -> "OTHER"
                        })
                        put("hasChallengeText", Regex("captcha|访问异常|安全验证|环境异常|访问过于频繁", RegexOption.IGNORE_CASE).containsMatchIn(page.bodyText))
                        put("visibleTextChars", visibleText.length)
                        // 仅输出预先允许的通用状态词，不能把页面账号、正文或验证参数写入诊断报告。
                        put("visibleStatusMarkers", JsonArray(listOf("异常", "验证", "失败", "频繁", "访问", "点击", "检查", "环境", "加载", "开启", "JavaScript", "浏览器", "安全", "登录", "搜索").filter { it in visibleText }.map(::JsonPrimitive)))
                        put("passwordInputs", count("input[type=password]"))
                        put("scriptCount", count("script"))
                        put("externalScriptOrigins", JsonArray(evaluator.evalToStrings(LegadoRuleAnalyzer.analyze("script@src"), ctx).map { origin(page.finalUrl.toHttpUrlOrNull()?.resolve(it)?.toString().orEmpty()) }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive)))
                        put("hasViewport", count("meta[name=viewport]") > 0)
                        val list = source.ruleSearch?.bookList
                        if (!list.isNullOrBlank()) put("standaloneListCount", evaluator.evalToNodes(LegadoRuleAnalyzer.analyze(list), EvalContext.page(page.bodyText, page.finalUrl)).size)
                    } catch (e: Exception) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        put("errorType", e.javaClass.simpleName)
                        put("errorCategory", category(e.message.orEmpty()))
                    }
                    delay(1000)
                    put("renderRequests", JsonArray(renderRequests.toList()))
                }
            }
        }
        File(inst.targetContext.getExternalFilesDir(null), "source-site-diagnostic.json").writeText(JsonArray(result).toString())
        phase("DONE")
    }
}
