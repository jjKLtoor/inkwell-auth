package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.core.source.js.ScriptValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 使用本轮核验的公开规则，所有响应均在拦截器内构造。 */
class FanqieReferenceRuleTest {
    private fun reference(): BookSourceRule {
        val file = System.getenv("INKWELL_AUTH_SNAPSHOT")
        assumeTrue(file != null)
        val row = Json.parseToJsonElement(File(requireNotNull(file)).readText()).jsonArray.single {
            it.jsonObject["bookSourceName"]?.jsonPrimitive?.content == "番茄小说" &&
                it.jsonObject["bookSourceUrl"]?.jsonPrimitive?.content?.trimEnd('/') == "https://fanqienovel.com"
        }
        return BookSourceRule.fromJson(row.toString())
    }

    @Test fun 番茄参考规则无需网页登录即可发起搜索() = runBlocking {
        val source = reference()
        val hosts = mutableListOf<String>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            hosts += request.url.host
            assertTrue(request.header("Cookie").isNullOrEmpty())
            assertTrue(request.header("Authorization").isNullOrEmpty())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body("""{"data":{"ret_data":[{"title":"公开测试书","author":"作者","book_id":"1234567890123456789"}]}}""".toResponseBody())
                .build()
        }.build()
        val engine = BookSourceEngine(SourceHttpClient(transport), scriptRuntime = RhinoScriptRuntime())
        assertEquals("公开测试书", engine.search(source, "公开测试书").items.single().title)
        assertEquals(listOf("api.fanqiesdk.com"), hosts)
    }

    @Test fun 番茄参考规则的详情与目录可在现有脚本沙箱执行() = runBlocking {
        val source = reference()
        val issues = mutableListOf<String>()
        val runtime = RhinoScriptRuntime()
        val traced = object : ScriptRuntime by runtime {
            override fun eval(script: String, bindings: Map<String, Any?>, jsLib: String?): String? =
                record { runtime.eval(script, bindings, jsLib) }

            override fun evalForList(script: String, bindings: Map<String, Any?>, jsLib: String?): ScriptValue =
                record { runtime.evalForList(script, bindings, jsLib) }

            private fun <T> record(run: () -> T): T = try {
                run()
            } catch (error: Exception) {
                val method = Regex("""(?:method|property|function|read|call)\s+['"]?([a-zA-Z][a-zA-Z0-9_]*)""")
                    .find(error.message.orEmpty())?.groupValues?.get(1)
                // 只取 Rhino 的错误摘要，丢弃脚本正文、地址与合成请求参数。
                val summary = error.message.orEmpty().substringBefore(" (source-")
                    .substringBefore(" (rule")
                    .replace(Regex("""https?://[^\s'"]+"""), "[地址]")
                    .replace(Regex("[A-Za-z0-9_-]{24,}"), "[标识]")
                    .take(150)
                issues += listOfNotNull(error.javaClass.simpleName, method, summary).joinToString(" ")
                throw error
            }
        }
        val stages = mutableListOf<String>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            stages += when {
                "device_register" in request.url.encodedPath -> "register-fixture"
                "/detail/" in request.url.encodedPath -> "detail-fixture"
                "/directory/" in request.url.encodedPath -> "directory-fixture"
                else -> "other-fixture"
            }
            val body = when {
                "device_register" in request.url.encodedPath -> """{"device_id_str":"1234567890123456789","install_id_str":"1234567890123456788"}"""
                "/detail/" in request.url.encodedPath -> """{"code":0,"data":{"book_info":{"book_name":"测试书","author":"作者"}}}"""
                "/directory/" in request.url.encodedPath -> """{"data":{"item_data_list":[{"item_id":"1234567890123456787","title":"测试章"}]}}"""
                else -> "<html>fixture</html>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody()).build()
        }.build()
        val engine = BookSourceEngine(SourceHttpClient(transport), scriptRuntime = traced)
        val url = "${source.baseUrl}/page/1234567890123456789"
        val detail = engine.getDetail(source, url)
        assertEquals("测试书", detail.title, "$stages $issues")
        assertTrue(engine.getToc(source, detail.tocUrl, bookUrl = url).any { it.title == "测试章" }, "$stages $issues")
    }
}
