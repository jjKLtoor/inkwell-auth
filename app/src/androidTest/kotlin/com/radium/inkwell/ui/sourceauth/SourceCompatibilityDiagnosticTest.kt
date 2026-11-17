package com.radium.inkwell.ui.sourceauth

import androidx.test.platform.app.InstrumentationRegistry
import com.radium.inkwell.core.source.js.ScriptRuntime
import com.radium.inkwell.data.repo.BookSourceRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

/** 显式指定已导入来源后才运行；只读规则并检查沙箱能力，不发网络请求、不读取登录数据。 */
class SourceCompatibilityDiagnosticTest {
    @Test fun 记录实际导入脚本指纹与UUID替身能力() = runBlocking<Unit> {
        val origin = InstrumentationRegistry.getArguments().getString("compatibilityOrigin")
        assumeTrue(origin != null)
        val koin = GlobalContext.get()
        val source = koin.get<BookSourceRepository>().findByHost(requireNotNull(origin)).single()
        val lib = source.jsLib.orEmpty()
        val runtime = koin.get<ScriptRuntime>()
        // 只查询类型，不执行来源的注册、签名或登录函数；不导出规则原文与异常原文。
        val uuidType = runtime.eval("typeof Packages.java.util.UUID", emptyMap())
        val report = buildJsonObject {
            put("jsLibSha256", MessageDigest.getInstance("SHA-256").digest(lib.toByteArray()).joinToString("") { "%02x".format(it) })
            put("callsJavaUuid", lib.contains("Packages.java.util.UUID.randomUUID()"))
            put("uuidType", uuidType)
            put("networkRequests", 0)
            put("loginDataRead", false)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "source-compatibility-report.json").writeText(report.toString())
    }
}
