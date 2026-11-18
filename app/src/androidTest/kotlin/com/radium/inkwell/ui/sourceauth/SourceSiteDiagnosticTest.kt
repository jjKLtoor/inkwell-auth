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
    @Test(timeout = 120000) fun 指定来源的已有校验与搜索页诊断() = runBlocking<Unit> {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val progress = File(inst.targetContext.getExternalFilesDir(null), "source-site-diagnostic-progress.txt")
        fun phase(value: String) { progress.writeText(value) }
        phase("START")
        kotlin.concurrent.thread(isDaemon = true, name = "source-diagnostic-watchdog") {
            Thread.sleep(15000)
            if (progress.readText() != "DONE") {
                // 仅保留线程状态和代码栈，不输出线程名称、局部变量或网页内容。
                val stacks = Thread.getAllStackTraces().entries.joinToString("\n\n") { (thread, frames) ->
                    "${thread.id}: ${thread.state}\n" + frames.joinToString("\n") { it.toString() }
                }
                File(inst.targetContext.getExternalFilesDir(null), "source-diagnostic-stacks.txt").writeText(stacks)
            }
        }
        val names = args.getString("diagnosticNames")?.split(',')?.filter { it.isNotBlank() }
        assumeTrue(!names.isNullOrEmpty())
        require(names!!.size <= 8)
        val koin = GlobalContext.get()
        val repo = koin.get<BookSourceRepository>()
        val items = repo.sources.first().filter { it.name in names }
        phase("SOURCES_READY")
        val renderRequests = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
        val sessions = koin.get<PersistentSourceSessions>()
        val baseRenderer = WebViewPageRenderer(inst.targetContext, authenticatedGuard = {
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
        val renderedPages = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
        fun recordPage(page: FetchedPage?) {
            if (page == null || renderedPages.size >= 12) return
            val doc = org.jsoup.Jsoup.parse(page.bodyText, page.finalUrl)
            renderedPages.add(buildJsonObject {
                put("bodyChars", page.bodyText.length)
                put("pathKind", if (page.finalUrl.toHttpUrlOrNull()?.encodedPath?.startsWith("/chapter/") == true) "CHAPTER" else "OTHER")
                put("selectors", buildJsonObject {
                    for (css in listOf(".content p", ".read-content p", ".chapter-content p", "article p", ".book-img-text ul li")) {
                        put(css, buildJsonObject {
                            val nodes = doc.select(css); put("count", nodes.size)
                            put("textChars", nodes.sumOf { it.text().length })
                        })
                    }
                })
            })
            File(inst.targetContext.getExternalFilesDir(null), "source-rendered-page-metadata.json").writeText(JsonArray(renderedPages.toList()).toString())
        }
        val renderer = object : AuthenticatedPageRenderer {
            override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) = baseRenderer.render(url, headers, userAgent).also(::recordPage)
            override suspend fun renderAuthenticated(url: String, headers: Map<String, String>, userAgent: String?, session: com.radium.inkwell.core.source.auth.SourceRequestSession) =
                baseRenderer.renderAuthenticated(url, headers, userAgent, session).also(::recordPage)
        }
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
                val primary = source.baseUrl.toHttpUrlOrNull()
                val comparison = args.getString("comparisonOrigin")?.toHttpUrlOrNull()
                put("cookieScopeSummary", JsonArray(session?.cookiesForRendering().orEmpty()
                    .groupBy { listOf(it.domain, it.hostOnly, it.secure, it.persistent, primary?.let(it::matches), comparison?.let(it::matches)) }
                    .map { (key, rows) -> buildJsonObject {
                        put("domain", key[0] as String); put("hostOnly", key[1] as Boolean)
                        put("secure", key[2] as Boolean); put("persistent", key[3] as Boolean)
                        put("matchesSourceRoot", key[4] as? Boolean ?: false)
                        put("matchesComparisonRoot", key[5] as? Boolean ?: false)
                        put("count", rows.size)
                    } }))
                put("searchRuleSha256", MessageDigest.getInstance("SHA-256").digest((source.searchUrl.orEmpty()+source.ruleSearch.toString()).toByteArray()).joinToString("") { "%02x".format(it) })
                if (args.getString("diagnosticProbe") == "chain") {
                    var stage = "SEARCH"
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    try {
                        withTimeout(110000) {
                            val keyword = args.getString("keyword") ?: com.radium.inkwell.data.source.sourceTestKeyword(source)
                            val found = engine.search(source, keyword).items
                            put("searchCount", found.size)
                            put("searchElapsedMs", android.os.SystemClock.elapsedRealtime() - startedAt)
                            val first = found.firstOrNull() ?: error("搜索无结果")
                            stage = "DETAIL_AND_TOC"
                            val (detail, toc) = koin.get<com.radium.inkwell.data.repo.NetBookRepository>().fetchDetailAndToc(source, first.bookUrl, first.variables)
                            put("tocCount", toc.size)
                            put("tocElapsedMs", android.os.SystemClock.elapsedRealtime() - startedAt)
                            put("firstBookTitle", first.title)
                            put("firstChapters", JsonArray(toc.take(2).map { chapter -> buildJsonObject {
                                put("title", chapter.title)
                                put("origin", origin(chapter.url))
                                put("pathKind", if (chapter.url.toHttpUrlOrNull()?.encodedPath?.startsWith("/chapter/") == true) "CHAPTER" else "OTHER")
                            } }))
                            File(inst.targetContext.getExternalFilesDir(null), "source-chain-progress.json").writeText(buildJsonObject {
                                put("searchCount", found.size); put("tocCount", toc.size)
                                put("elapsedMs", android.os.SystemClock.elapsedRealtime() - startedAt)
                                put("firstChapterOrigin", toc.firstOrNull()?.url?.let(::origin).orEmpty())
                            }.toString())
                            stage = "CONTENT"
                            val urls = toc.mapTo(HashSet()) { it.url }
                            val probe = com.radium.inkwell.data.source.probeSourceContent(toc) { chapter ->
                                engine.getContent(source, chapter.url, urls, chapter.variable, chapterTitle = chapter.title,
                                    chapterIndex = chapter.index, bookUrl = first.bookUrl, bookTitle = detail.title, variables = detail.variables)
                            }
                            put("contentChars", probe.chars)
                            put("passed", true)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is TimeoutCancellationException) throw error
                        put("passed", false); put("failedStage", stage)
                        put("errorType", error.javaClass.simpleName); put("errorCategory", category(error.message.orEmpty()))
                    }
                }
                if (args.getString("diagnosticProbe") == "search" && item.name in args.getString("probeNames").orEmpty().split(',')) {
                    try {
                        phase("FETCH_SEARCH")
                        val keyword = args.getString("keyword") ?: com.radium.inkwell.data.source.sourceTestKeyword(source)
                        put("keywordKind", if (args.getString("keyword") != null) "OVERRIDE" else if (source.ruleSearch?.checkKeyWord.isNullOrBlank()) "FALLBACK" else "DECLARED")
                        val page = withTimeout(35000) { engine.fetchSearchPage(source, keyword) }
                        phase("SEARCH_RECEIVED_" + item.name)
                        val document = org.jsoup.Jsoup.parse(page.bodyText, page.finalUrl)
                        fun count(css: String) = document.select(css).size
                        phase("READ_TITLE_" + item.name)
                        val title = document.title()
                        val visibleText = document.body().text()
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
                        phase("READ_PAGE_COUNTS_" + item.name)
                        File(inst.targetContext.getExternalFilesDir(null), "source-page-metadata.json").writeText(buildJsonObject {
                            put("bodyChars", page.bodyText.length); put("visibleTextChars", visibleText.length)
                            put("visibleMarkers", JsonArray(listOf("验证", "登录", "搜索", "结果", "都市").filter { it in visibleText }.map(::JsonPrimitive)))
                        }.toString())
                        put("passwordInputs", count("input[type=password]"))
                        put("scriptCount", count("script"))
                        put("externalScriptOrigins", JsonArray(document.select("script[src]").map { origin(it.absUrl("src")) }.filter { it.isNotEmpty() }.distinct().map(::JsonPrimitive)))
                        put("hasViewport", count("meta[name=viewport]") > 0)
                        put("listSelectors", buildJsonObject {
                            for (css in listOf(".book-img-text ul li", ".book-mid-info", ".book-info-title", ".book-list", ".book-box", ".book-item")) put(css, count(css))
                        })
                        put("bookClassCounts", buildJsonObject {
                            document.allElements.flatMap { it.classNames() }.filter { it.length <= 64 && it.matches(Regex("[a-zA-Z_-]+")) && Regex("book|search|result", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(40).forEach { put(it.key, it.value) }
                        })
                        // 来源列表规则可能执行脚本，不能在引擎会话和取消边界外另行求值。
                        put("listEvaluation", "ENGINE_ONLY")
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
