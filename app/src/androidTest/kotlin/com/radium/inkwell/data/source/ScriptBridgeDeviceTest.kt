package com.radium.inkwell.data.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radium.inkwell.core.source.SourceHttpClient
import com.radium.inkwell.core.source.js.EngineJsHttp
import com.radium.inkwell.core.source.js.JavaBridge
import com.radium.inkwell.core.source.js.JsCache
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptBridgeDeviceTest {
    /** 脚本按原名反射访问 URL、请求及读流对象。Release 压缩后也必须能访问。 */
    @Test fun 二进制请求桥可从脚本调用() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("POST", request.method)
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            GZIPInputStream(buffer.inputStream()).use { input ->
                assertEquals("fixture", input.reader().readText())
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body("目录夹具".toResponseBody()).build()
        }.build()
        val http = EngineJsHttp(SourceHttpClient(client))
        val bindings = mapOf("java" to JavaBridge(http, JsCache(), mutableMapOf()))
        withContext(http.bindingTo(coroutineContext[Job], "fixture")) {
            val answer = RhinoScriptRuntime().eval("""
                var url = new Packages.java.net.URL('https://transport.example.test/submit');
                var connection = url.openConnection();
                connection.setRequestMethod('POST');
                connection.setDoOutput(true);
                var gzip = new Packages.java.util.zip.GZIPOutputStream(connection.getOutputStream());
                var bytes = Packages.java.util.Base64.getDecoder().decode('Zml4dHVyZQ==');
                gzip.write(bytes, 0, bytes.length);
                gzip.close();
                var input = new Packages.java.io.InputStreamReader(connection.getInputStream(), 'UTF-8');
                var result = connection.getResponseCode() + ':' + input.readLine();
                input.close();
                result;
            """.trimIndent(), bindings)
            assertEquals("200:目录夹具", answer)
        }
    }
}
