package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import kotlin.test.*

class GuardedUrlConnectionTest {
    private fun bindings(http: SourceHttpClient) = mapOf("java" to JavaBridge(EngineJsHttp(http), JsCache(), mutableMapOf()))
    private val runtime = RhinoScriptRuntime()

    @Test fun 二进制请求逐字节往返且重复读响应不重发() {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(256) { it.toByte() }
            val packed = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write("夹具响应\n第二行".toByteArray()) } }.toByteArray()
            server.enqueue(MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(packed)))
            val env = bindings(SourceHttpClient(OkHttpClient(), allowPrivateNetwork = true)) + mapOf("url" to server.url("/binary").toString(), "bytes" to bytes)
            val result = runtime.eval("""
                var c = new Packages.java.net.URL(url).openConnection();
                c.setRequestMethod('POST'); c.setDoOutput(true);
                c.setRequestProperty('Accept-Encoding','gzip');
                c.getOutputStream().write(bytes,0,bytes.length);
                var code=c.getResponseCode(); var again=c.getResponseCode();
                var g=new Packages.java.util.zip.GZIPInputStream(c.getInputStream());
                var r=new Packages.java.io.BufferedReader(new Packages.java.io.InputStreamReader(g,'UTF-8'));
                code+'|'+again+'|'+r.readLine()+'|'+r.readLine();
            """.trimIndent(), env)
            assertEquals("200|200|夹具响应|第二行", result)
            assertContentEquals(bytes, server.takeRequest().body.readByteArray())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun 原生连接写法仍阻止私网文件协议与隐式重定向() {
        MockWebServer().use { server -> MockWebServer().use { outside ->
            server.start(); outside.start()
            val env = bindings(SourceHttpClient()) + mapOf("url" to server.url("/private").toString())
            assertFails { runtime.eval("new Packages.java.net.URL(url).openConnection().getResponseCode()", env) }
            assertEquals(0, server.requestCount)
            assertFails { runtime.eval("new Packages.java.net.URL('file:///private').openConnection()", env) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", outside.url("/outside")))
            val allowed = bindings(SourceHttpClient(OkHttpClient(), allowPrivateNetwork = true)) + mapOf("url" to server.url("/redirect").toString())
            assertEquals("302", runtime.eval("new Packages.java.net.URL(url).openConnection().getResponseCode()", allowed))
            assertEquals(0, outside.requestCount)
        } }
    }

    @Test fun 提交收到服务端错误或读取超时都不自动重发() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(503).setBody("fixture"))
            val env = bindings(SourceHttpClient(OkHttpClient(), allowPrivateNetwork = true)) + mapOf("url" to server.url("/submit").toString())
            assertEquals("503|503", runtime.eval("var c=new Packages.java.net.URL(url).openConnection();c.setDoOutput(true);c.getOutputStream().write(1);c.getResponseCode()+'|'+c.getResponseCode()", env))
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setBody("slow").setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS))
            val http = EngineJsHttp(SourceHttpClient(OkHttpClient(), allowPrivateNetwork = true))
            val c = GuardedUrlConnection(http, server.url("/slow").toString())
            c.setDoOutput(true); c.setReadTimeout(50)
            assertFails { c.getResponseCode() }
            assertFails { c.getResponseCode() }
            assertEquals(2, server.requestCount)
        }
    }
}
