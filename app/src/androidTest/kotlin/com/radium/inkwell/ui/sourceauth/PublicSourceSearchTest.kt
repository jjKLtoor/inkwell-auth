package com.radium.inkwell.ui.sourceauth

import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import com.radium.inkwell.data.source.WebViewPageRenderer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式传入快照才执行。只搜索公开元数据，不登录、不保存响应正文、不访问目录或章节。 */
class PublicSourceSearchTest {
    @Test fun 当前分组逐条搜索并区分真实公开访问和登录验证() = runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        val path=args.getString("publicSnapshot");assumeTrue(path!=null)
        val rows=Json.parseToJsonElement(File(requireNotNull(path)).readText()).jsonArray
        val report=mutableListOf<JsonObject>()
        val allowed=setOf("user-agent","accept","accept-language","content-type","referer","origin","cache-control")
        for(row in rows) {
            val source=BookSourceRule.fromJson(row.toString())
            var title="";var rendered=false;var bodyChars=0;var challenge=false;var httpStatus=0
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                val request=chain.request().newBuilder()
                chain.request().headers.names().filter { it.lowercase() !in allowed }.forEach { request.removeHeader(it) }
                val response=chain.proceed(request.build())
                // 403/429 不重试、不换参数继续尝试，不因诊断增加访问频率。
                if(response.code==403 || response.code==429) { response.close();throw java.io.IOException("公开访问受限") }
                response
            }.build()
            val native=WebViewPageRenderer(inst.targetContext)
            val renderer=object:PageRenderer {
                override suspend fun render(url:String,headers:Map<String,String>,userAgent:String?):FetchedPage? {
                    rendered=true
                    return native.render(url,headers.filterKeys { it.lowercase() in allowed },userAgent)?.also {
                        bodyChars=it.bodyText.length
                        title=RuleEvaluator().evalToString(LegadoRuleAnalyzer.analyze("title@text"),EvalContext.page(it.bodyText,it.finalUrl)).orEmpty().take(100)
                        val text=it.bodyText
                        challenge=text.contains("环境异常") || text.contains("访问异常") || text.contains("验证码")
                    }
                }
            }
            val trace=object:RuleTraceCollector {
                override fun onRule(trace:RuleTrace) {}
                override fun onFetch(trace:FetchTrace) { if(trace.method!="RENDER")httpStatus=trace.statusCode }
            }
            var count=0;var result="ERROR";var errorType=""
            try {
                count=withTimeout(30000) { BookSourceEngine(SourceHttpClient(client),trace=trace,scriptRuntime=RhinoScriptRuntime(),renderer=renderer).search(source,"剑").items.size }
                result=if(count>0)"SEARCH_RESULTS" else "EMPTY"
            } catch(error:TimeoutCancellationException) { result="TIMEOUT" }
            catch(error:Exception) { if(error is CancellationException)throw error;errorType=error.javaClass.simpleName }
            report+=buildJsonObject {
                put("name",source.name);put("public_search",result);put("count",count);put("error_type",errorType)
                put("rendered_title",title);put("rendered",rendered);put("rendered_body_chars",bodyChars);put("challenge_text_detected",challenge);put("http_status_if_observed",httpStatus)
                put("authenticated_login","NOT_TESTED");put("toc","NOT_TESTED");put("content","NOT_TESTED")
            }
            File(inst.targetContext.getExternalFilesDir(null),"public-search-report.json").writeText(Json { prettyPrint=true }.encodeToString(JsonArray.serializer(),JsonArray(report)))
            delay(2000)
        }
    }
}
