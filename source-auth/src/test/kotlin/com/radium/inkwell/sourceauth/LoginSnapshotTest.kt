package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.BookSourceRule
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/** 可选读取已经校验的开发快照；不请求网站、不把第三方规则打包到 APK。 */
class LoginSnapshotTest {
    @Test fun 对实际登录字段生成入口兼容矩阵而不推断真实登录成功() {
        val path = System.getenv("INKWELL_AUTH_SNAPSHOT")
        val input = path?.let { File(it).readText() } ?: """[
            {"bookSourceUrl":"https://example.com","bookSourceName":"fixture","bookSourceGroup":"大厂正版","loginUrl":"/login"}
        ]"""
        val rows = Json.parseToJsonElement(input).jsonArray.filter {
            it.jsonObject["bookSourceGroup"]?.jsonPrimitive?.content.orEmpty().contains("大厂正版")
        }
        assertTrue(rows.isNotEmpty())
        val matrix = rows.map { row ->
            val source = BookSourceRule.fromJson(row.toString())
            val config = resolveLoginConfiguration(source)
            val scope = sourceAuthScope(source)
            val page = config as? LoginConfiguration.WebPage
            buildJsonObject {
                put("name", source.name)
                put("source", source.id)
                put("entry", when {
                    page == null || scope == null -> "NOT_SUPPORTED"
                    page.home -> "HOME_ATTEMPT"
                    page.upgraded -> "HTTPS_ATTEMPT"
                    page.partial -> "SCRIPT_DECLARED_WEBPAGE_ONLY"
                    else -> "DECLARED_WEBPAGE"
                })
                page?.let { put("login_page", it.url) }
                put("origins", JsonArray(scope?.originUrls.orEmpty().map(::JsonPrimitive)))
                put("login", "NOT_TESTED")
                put("search", "NOT_TESTED")
                put("toc", "NOT_TESTED")
                put("content", "NOT_TESTED")
            }
        }
        val report = Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), JsonArray(matrix))
        println(report)
        System.getenv("INKWELL_AUTH_REPORT")?.let { File(it).writeText(report) }
    }
}
