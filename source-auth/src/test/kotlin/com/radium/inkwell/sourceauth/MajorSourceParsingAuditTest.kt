package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import kotlin.test.*

/** 使用已验证规则和人工响应检查解析兼容性；拦截所有网络，绝不将结果写成站点登录通过。 */
class MajorSourceParsingAuditTest {
    @Test fun 当前分类逐条走真实引擎并记录离线解析结果() = runBlocking {
        val path = System.getenv("INKWELL_AUTH_SNAPSHOT") ?: return@runBlocking
        val fixtures = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/major-search-fixtures.json")).readText()).jsonObject
        val rows = Json.parseToJsonElement(File(path).readText()).jsonArray.filter {
            it.jsonObject["bookSourceGroup"]?.jsonPrimitive?.content.orEmpty().contains("大厂正版")
        }
        val results = rows.map { row ->
            val source = BookSourceRule.fromJson(row.toString())
            val body = requireNotNull(fixtures[source.name]).jsonPrimitive.content
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline Fixture")
                    .body(body.toResponseBody()).build()
            }.build()
            val renderer = object : PageRenderer {
                override suspend fun render(url: String, headers: Map<String, String>, userAgent: String?) = FetchedPage(url, 200, body, "utf-8")
            }
            val diagnostics = mutableListOf<String>()
            val trace = object : RuleTraceCollector {
                override fun onFetch(trace: FetchTrace) {}
                override fun onRule(trace: RuleTrace) { if (trace.fieldName in setOf("list", "title", "bookUrl")) diagnostics.add(trace.fieldName + ":" + trace.output.orEmpty() + ":" + trace.error.orEmpty()) }
            }
            val result = runCatching { BookSourceEngine(SourceHttpClient(client), trace = trace, scriptRuntime = RhinoScriptRuntime(), renderer = renderer).search(source, "fixture") }
            buildJsonObject {
                put("name", source.name)
                put("fixture_search", if (result.getOrNull()?.items?.any { it.title == "fixture" } == true) "PASS" else "FAIL")
                put("items", result.getOrNull()?.items?.size ?: 0)
                if (result.getOrNull()?.items?.isEmpty() == true) put("fixture_diagnostics", JsonArray(diagnostics.map(::JsonPrimitive)))
                put("exception_type", result.exceptionOrNull()?.javaClass?.simpleName.orEmpty())
                put("real_login", "NOT_TESTED"); put("real_search", "NOT_TESTED")
            }
        }
        assertEquals(rows.size, results.size)
        val report = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(results))
        println(report)
        System.getenv("INKWELL_AUTH_PARSING_REPORT")?.let { File(it).writeText(report) }
        assertTrue(results.all { it["fixture_search"]?.jsonPrimitive?.content == "PASS" }, "有来源未通过人工响应解析，见去敏感报告")
    }
}
