package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** 显式快照和目标来源才执行；所有 HTTP 返回人工响应，不使用账号或真实站点。 */
class SourceSnapshotCompatibilityTest {
    @Test fun 实际规则的详情与目录脚本在受控HTTP响应下连续执行() = runBlocking {
        val path = System.getenv("INKWELL_AUTH_SNAPSHOT")
        val origin = System.getenv("INKWELL_COMPAT_SOURCE_BASE_URL")
        assumeTrue(path != null && origin != null)
        val row = Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonArray.single {
            it.jsonObject["bookSourceUrl"]?.jsonPrimitive?.content?.trimEnd('/') == origin?.trimEnd('/')
        }
        val source = BookSourceRule.fromJson(row.toString())
        val errors = mutableListOf<String>()
        val delegate = RhinoScriptRuntime()
        val runtime = object : ScriptRuntime by delegate {
            override fun eval(script: String, bindings: Map<String, Any?>, jsLib: String?): String? = try {
                delegate.eval(script, bindings, jsLib)
            } catch (e: Exception) { errors.add(e.javaClass.simpleName + ":" + e.message); throw e }
            override fun evalForList(script: String, bindings: Map<String, Any?>, jsLib: String?): ScriptValue = try {
                delegate.evalForList(script, bindings, jsLib)
            } catch (e: Exception) { errors.add(e.javaClass.simpleName + ":" + e.message); throw e }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when {
                chain.request().url.encodedPath.contains("device_register") -> """{"device_id_str":"1234567890123456789","install_id_str":"1234567890123456788"}"""
                chain.request().url.encodedPath.contains("/detail/") -> """{"code":0,"data":{"book_info":{"book_name":"fixture-title","author":"fixture-author"}}}"""
                chain.request().url.encodedPath.contains("/directory/") -> """{"data":{"item_data_list":[{"item_id":"1234567890123456787","title":"fixture-chapter"}]}}"""
                else -> "<html>fixture</html>"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val engine = BookSourceEngine(SourceHttpClient(client), scriptRuntime = runtime)
        val url = source.baseUrl.trimEnd('/') + "/page/1234567890123456789"
        val detail = engine.getDetail(source, url)
        assertEquals("fixture-title", detail.title, errors.joinToString("\n"))
        assertTrue(engine.getToc(source, detail.tocUrl, bookUrl = url).any { it.title == "fixture-chapter" }, errors.joinToString("\n"))
    }
}
