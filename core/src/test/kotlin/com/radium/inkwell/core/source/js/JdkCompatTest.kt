package com.radium.inkwell.core.source.js

import com.radium.inkwell.core.source.SourceHttpClient
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `JavaImporter` / `Packages` 的替身（JdkCompat）：阅读来源直接拿 JDK 做加解密的脚本能跑，沙箱照旧封着。
 */
class JdkCompatTest {

    private val runtime = RhinoScriptRuntime()

    @Test fun UUID替身返回字符串且不暴露Java类() {
        val script = "String(Packages.java.util.UUID.randomUUID())"
        val first = checkNotNull(runtime.eval(script, emptyMap()))
        assertEquals(4, java.util.UUID.fromString(first).version())
        assertTrue(first != runtime.eval(script, emptyMap()))
        assertEquals("undefined", runtime.eval("typeof Packages.java.util.UUID.randomUUID().getClass", emptyMap()))
    }

    @Test fun 大整数通过通用脚本入口保留精度与符号() {
        val result = runtime.eval("""
            importPackage(Packages.java.math);
            var id = new BigInteger('900719925474099312345');
            var sum = id.add(BigInteger.ONE);
            var negative = new BigInteger('-ff', 16);
            var roundTrip = new BigInteger(negative.toByteArray());
            var signed = new BigInteger(1, negative.toByteArray());
            [sum.toString(), roundTrip.toString(16), signed.toString(16),
              BigInteger.valueOf(17).multiply(BigInteger.TEN).divide(BigInteger.valueOf(2)).toString(),
              BigInteger.valueOf(-17).mod(BigInteger.TEN).toString(), id.compareTo(sum),
              BigInteger.ZERO.signum(), negative.bitLength()].join('|');
        """.trimIndent(), emptyMap())
        assertEquals("900719925474099312346|-ff|ff01|85|3|-1|0|8", result)
    }

    @Test fun 大整数限制输入与运算结果并保持沙箱隔离() {
        val prefix = "var Big = Packages.java.math.BigInteger; "
        assertEquals("4096", runtime.eval(prefix + "new Big(new Array(4097).join('1'), 2).bitLength()", emptyMap()))
        assertFails { runtime.eval(prefix + "new Big('1' + new Array(4097).join('0'), 2)", emptyMap()) }
        assertFails { runtime.eval(prefix + "new Big(new Array(4097).join('1'), 2).add(Big.ONE)", emptyMap()) }
        assertFails { runtime.eval(prefix + "Big.valueOf(9007199254740992)", emptyMap()) }
        // Rhino 能看到继承的方法名，沙箱应在方法返回 Java Class 时拒绝包装。
        assertFails { runtime.eval(prefix + "Big.ONE.getClass()", emptyMap()) }
    }

    @Test fun 大整数模幂和安全随机数遵守脚本预算() {
        val prefix = "var Big = Packages.java.math.BigInteger; var random = new Packages.java.security.SecureRandom(); "
        val result = runtime.eval(prefix + """
            var bytes = random.generateSeed(32);
            random.nextBytes(bytes);
            var exponent = Big.valueOf(13);
            [Big.valueOf(4).modPow(exponent, Big.valueOf(497)).toString(), bytes.length,
             new Big(128, random).bitLength() <= 128, random.nextInt(1)].join('|');
        """.trimIndent(), emptyMap())
        assertEquals("445|32|true|0", result)
        assertFails { runtime.eval(prefix + "new Big(4097, random)", emptyMap()) }
        assertFails { runtime.eval(prefix + "random.generateSeed(4097)", emptyMap()) }
        assertFails { runtime.eval(prefix + "random.getClass()", emptyMap()) }
        assertFails { runtime.eval(prefix + """
            var huge = new Big(new Array(4097).join('1'), 2);
            Big.TEN.modPow(huge, huge);
        """.trimIndent(), emptyMap()) }
    }

    @Test fun 内存gzip兼容字符集并保留文件访问边界() {
        val compressed = checkNotNull(runtime.eval("""
            var memory = new Packages.java.io.ByteArrayOutputStream();
            var gzip = new Packages.java.util.zip.GZIPOutputStream(memory);
            var utf8 = Packages.java.nio.charset.Charset.forName('UTF-8');
            var bytes = new Packages.java.lang.String('!测试!').getBytes(utf8);
            gzip.write(bytes, 1, bytes.length - 2);
            gzip.close();
            String(Packages.java.util.Base64.getEncoder().encodeToString(memory.toByteArray()));
        """.trimIndent(), emptyMap()))
        val bytes = Base64.getDecoder().decode(compressed)
        assertEquals("测试", java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader().use { it.readText() })
        assertEquals("undefined", runtime.eval("typeof Packages.java.io.File", emptyMap()))
        assertFails { runtime.eval("new Packages.java.io.ByteArrayOutputStream(4194305)", emptyMap()) }
    }

    @Test fun 二进制请求保留字节且不重发或跨作用域发送凭据() = runBlocking {
        var count = 0
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            count++
            assertEquals("POST", request.method)
            assertEquals(null, request.header("X-Session-Fixture"))
            val buffer = Buffer()
            checkNotNull(request.body).writeTo(buffer)
            assertTrue(byteArrayOf(-1, 0, 65).contentEquals(buffer.readByteArray()))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("Fixture")
                .body("响应".toResponseBody()).build()
        }.build()
        val http = EngineJsHttp(SourceHttpClient(fixture))
        val bindings = mapOf("java" to JavaBridge(http, JsCache(), mutableMapOf()))
        withContext(http.bindingTo(coroutineContext[Job], "fixture", { setOf("X-Session-Fixture") }, { false })) {
            val result = runtime.eval("""
                var url = new Packages.java.net.URL('https://transport.example.test/submit');
                var connection = url.openConnection();
                connection.setRequestMethod('POST');
                connection.setDoOutput(true);
                connection.setRequestProperty('X-Session-Fixture', 'fixture-only');
                var bytes = Packages.java.util.Base64.getDecoder().decode('/wBB');
                connection.getOutputStream().write(bytes, 0, bytes.length);
                var stream = new Packages.java.io.InputStreamReader(connection.getErrorStream(), 'UTF-8');
                connection.getResponseCode() + ':' + stream.readLine();
            """.trimIndent(), bindings)
            assertEquals("403:响应", result)
            assertFails { runtime.eval("new Packages.java.net.URL('https://127.0.0.1/')", bindings) }
        }
        assertEquals(1, count)
    }

    @Test fun 二进制读取保留请求作用域且不跟随跳转() = runBlocking {
        var count = 0
        val fixture = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            count++
            assertEquals("GET", request.method)
            assertEquals(null, request.body)
            assertEquals(null, request.header("X-Session-Fixture"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Fixture")
                .header("Location", "https://other.example.test/")
                .body("响应".toResponseBody()).build()
        }.build()
        val http = EngineJsHttp(SourceHttpClient(fixture))
        val bindings = mapOf("java" to JavaBridge(http, JsCache(), mutableMapOf()))
        withContext(http.bindingTo(coroutineContext[Job], "fixture", { setOf("X-Session-Fixture") }, { false })) {
            val result = runtime.eval("""
                var connection = new Packages.java.net.URL('https://transport.example.test/read').openConnection();
                connection.setRequestProperty('X-Session-Fixture', 'fixture-only');
                var stream = new Packages.java.io.InputStreamReader(connection.getInputStream(), 'UTF-8');
                connection.getResponseCode() + ':' + connection.getHeaderField('Location') + ':' + stream.readLine();
            """.trimIndent(), bindings)
            assertEquals("302:https://other.example.test/:响应", result)
        }
        assertEquals(1, count)
    }

    /** 七猫接口源解正文的原样写法：密文 = Base64(IV 16 字节 + AES/CBC 密文) */
    private val qimaoDecode = """
        var javaImport = new JavaImporter();
        javaImport.importPackage(
            Packages.java.lang,
            Packages.javax.crypto.spec,
            Packages.javax.crypto,
            Packages.java.util
        );
        with(javaImport) {
            function decode(content) {
                var ivEncData = Base64.getDecoder().decode(String(content));
                var key = SecretKeySpec(String("242ccb8230d709e1").getBytes(), "AES");
                var iv = IvParameterSpec(Arrays.copyOfRange(ivEncData, 0, 16));
                var chipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                chipher.init(2, key, iv);
                return String(chipher.doFinal(Arrays.copyOfRange(ivEncData, 16, ivEncData.length)));
            }
        }
        decode(content)
    """.trimIndent()

    private fun qimaoEncrypt(plain: String): String {
        val iv = ByteArray(16) { (it * 7 + 3).toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("242ccb8230d709e1".toByteArray(), "AES"), IvParameterSpec(iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray()))
    }

    @Test
    fun `七猫的正文解密脚本原样能跑`() {
        val plain = "<p>第一章 陨落的天才</p><p>“斗之力，三段！”</p>"
        assertEquals(plain, runtime.eval(qimaoDecode, mapOf("content" to qimaoEncrypt(plain))))
    }

    @Test
    fun `顶层 importPackage、不带 new 的 JavaImporter、加密方向也认`() {
        val script = """
            importPackage(Packages.java.util);
            importClass(Packages.javax.crypto.Cipher);
            var imp = JavaImporter(Packages.javax.crypto.spec, Packages.java.lang);
            var key = imp.SecretKeySpec(imp.String("242ccb8230d709e1").getBytes(), "AES");
            var c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, key, imp.IvParameterSpec(Arrays.copyOf(imp.String("0123456789abcdef").getBytes(), 16)));
            Base64.getEncoder().encodeToString(c.doFinal(imp.String("你好").getBytes("UTF-8")))
        """.trimIndent()
        val out = runtime.eval(script, emptyMap())!!
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec("242ccb8230d709e1".toByteArray(), "AES"),
            IvParameterSpec("0123456789abcdef".toByteArray()),
        )
        assertEquals("你好", String(cipher.doFinal(Base64.getDecoder().decode(out))))
    }

    @Test
    fun `替身之外的 JDK 类一个都没有 —— Runtime、System、文件照样够不着`() {
        assertEquals("undefined", runtime.eval("typeof Packages.java.lang.Runtime", emptyMap()))
        assertEquals("undefined", runtime.eval("typeof Packages.java.io.File", emptyMap()))
        assertEquals(
            "undefined",
            runtime.eval("var i = new JavaImporter(Packages.java.lang); with (i) { typeof System }", emptyMap()),
        )
        // 替身对象上拿不到它攥着的那个真 Cipher
        assertEquals(
            "undefined",
            runtime.eval("typeof Packages.javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding').cipher", emptyMap()),
        )
    }

    @Test
    fun `getClass 越狱链照旧断在第一步`() {
        assertFails {
            runtime.eval("Packages.java.lang.String('x').getClass().forName('java.lang.Runtime')", emptyMap())
        }
        assertFails {
            runtime.eval("Packages.javax.crypto.Cipher.getInstance('AES/ECB/PKCS5Padding').getClass()", emptyMap())
        }
    }

    @Test
    fun `替身只装在这一次求值上，不串到下一段脚本`() {
        assertEquals("function", runtime.eval("importPackage(Packages.java.util); typeof Base64", emptyMap()))
        // 同一线程上的下一段脚本：没导过 Base64 就没有
        assertEquals("undefined", runtime.eval("typeof Packages; typeof Base64", emptyMap()))
        // 不提这几个名字的脚本根本不装
        assertEquals("undefined", runtime.eval("typeof this['Pack' + 'ages']", emptyMap()))
    }

    // ---- android.util.Base64（连城读书、书耽、柚免费耽美这一批接口源）----

    private val javaBridge = JavaBridge(null, JsCache(), mutableMapOf())

    private val androidImport = """
        var javaImport = new JavaImporter();
        javaImport.importPackage(
            Packages.java.lang,
            Packages.javax.crypto.spec,
            Packages.javax.crypto,
            Packages.android.util
        );
    """.trimIndent()

    private val shublKey = Base64.getDecoder().decode("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8=")

    private fun shublCipher(mode: Int) = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
        init(mode, SecretKeySpec(shublKey, "AES"), IvParameterSpec(ByteArray(16)))
    }

    @Test
    fun `书耽的加解密脚本原样能跑 —— Base64 用的是 Android 那套`() {
        val decode = androidImport + """

            with(javaImport){
              function decode(word){
                  let key=SecretKeySpec(java.base64DecodeToByteArray("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8="),"AES");
                  let iv=IvParameterSpec(java.base64DecodeToByteArray("AAAAAAAAAAAAAAAAAAAAAA=="));
                  let chipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                  let bytes=Base64.decode(String(word).getBytes(),2);
                  chipher.init(2,key,iv);
                  return String(chipher.doFinal(bytes));
                }
            }
            decode(result)
        """.trimIndent()
        val plain = """{"data":{"book_list":[{"book_name":"天官赐福"}]}}"""
        val cipherText = Base64.getEncoder().encodeToString(shublCipher(Cipher.ENCRYPT_MODE).doFinal(plain.toByteArray()))
        assertEquals(plain, runtime.eval(decode, mapOf("result" to cipherText, "java" to javaBridge)))

        val encode = androidImport + """

            with(javaImport){
              function encode(word){
                  let key=SecretKeySpec(java.base64DecodeToByteArray("nvlrM3RT6n0iYj4I/zbGqisUGGMpy3UT84cNphYONC8="),"AES");
                  let iv=IvParameterSpec(java.base64DecodeToByteArray("AAAAAAAAAAAAAAAAAAAAAA=="));
                  let chipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                  chipher.init(1,key,iv);
                  return java.encodeURI(Base64.encodeToString(chipher.doFinal(String(word).getBytes()),Base64.NO_WRAP));
                }
            }
            "secret_content="+encode(JSON.stringify({"book_id":100256}))
        """.trimIndent()
        val body = runtime.eval(encode, mapOf("java" to javaBridge))!!
        val sent = java.net.URLDecoder.decode(body.removePrefix("secret_content="), "UTF-8")
        assertEquals("""{"book_id":100256}""", String(shublCipher(Cipher.DECRYPT_MODE).doFinal(Base64.getDecoder().decode(sent))))
    }

    @Test
    fun `连城读书的 DES 正文解密原样能跑`() {
        val script = androidImport + """

            with(javaImport){
                function decrypt(str){
                    var key=SecretKeySpec(String("1701019k").getBytes(),"DES");
                    var iv=IvParameterSpec(java.base64DecodeToByteArray("AQIDBAUGBwg="));
                    var bytes=Base64.decode(String(str).getBytes(),2);
                    var chipher=Cipher.getInstance("DES/CBC/PKCS5Padding");
                    chipher.init(2,key,iv);
                    return String(chipher.doFinal(bytes));
                }
            }
            String(decrypt(JSON.parse(result).DATA.CTXT)).replace(/{{title}}/,'')
        """.trimIndent()
        val des = Cipher.getInstance("DES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("1701019k".toByteArray(), "DES"), IvParameterSpec(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        }
        val ctxt = Base64.getEncoder().encodeToString(des.doFinal("{{title}}　　第一章的正文".toByteArray()))
        assertEquals(
            "　　第一章的正文",
            runtime.eval(script, mapOf("result" to """{"DATA":{"CTXT":"$ctxt"}}""", "java" to javaBridge)),
        )
    }

    @Test
    fun `android Base64 的换行、标志位、宽松解码都照它`() {
        fun eval(expr: String) = runtime.eval("importPackage(Packages.android.util); $expr", emptyMap())
        val bytes = "var b = Packages.java.lang.String('${"x".repeat(58)}').getBytes();"
        // DEFAULT：76 个字符一行，末尾也带换行
        assertEquals("${"eHh4".repeat(19)}\neA==\n", eval("$bytes Base64.encodeToString(b, Base64.DEFAULT)"))
        assertEquals("${"eHh4".repeat(19)}\r\neA==\r\n", eval("$bytes Base64.encodeToString(b, Base64.CRLF)"))
        assertEquals("${"eHh4".repeat(19)}eA", eval("$bytes Base64.encodeToString(b, Base64.NO_WRAP | Base64.NO_PADDING)"))
        // URL_SAFE 换字母表；偏移 + 长度的四参数写法
        assertEquals("-_8=", eval("Base64.encodeToString(Base64.decode('+/8=', 0), Base64.URL_SAFE | Base64.NO_WRAP)"))
        assertEquals("eHg=", eval("$bytes Base64.encodeToString(b, 1, 2, Base64.NO_WRAP)"))
        // 解码跳过换行、空格，缺填充也认
        assertEquals("你好", eval("Packages.java.lang.String(Base64.decode('5L2g\\n5aW9 ', 0))"))
        assertEquals("你好", eval("Packages.java.lang.String(Base64.decode('5L2g5aW9', Base64.NO_PADDING))"))
        // 两个包导进同一个 JavaImporter：两套 Base64 的方法都在
        assertEquals(
            "function function",
            runtime.eval(
                "var i = new JavaImporter(Packages.android.util, Packages.java.util); " +
                    "with (i) { typeof Base64.getDecoder + ' ' + typeof Base64.encodeToString }",
                emptyMap(),
            ),
        )
    }

    /** 八叉书库在请求之间随机停一会儿：照停，太长的封顶；URLEncoder 按给的编码转 */
    @Test
    fun `Thread sleep 照停，URLEncoder 认编码`() {
        val t0 = System.currentTimeMillis()
        runtime.eval("Packages.java.lang.Thread.sleep(120)", emptyMap())
        assertTrue(System.currentTimeMillis() - t0 >= 100)
        assertEquals(
            "%D6%D8%C9%FA|重生",
            runtime.eval(
                "var e = Packages.java.net.URLEncoder.encode('重生', 'GBK'); e + '|' + Packages.java.net.URLDecoder.decode(e, 'GBK')",
                emptyMap(),
            ),
        )
    }

    /** 七猫接口源：MD5 签名、`new String(字节, 起点, 长度, 编码)` 从 zip 里切文件名、Inflater 解压正文 */
    @Test
    fun `MessageDigest、Inflater、四参数的 String 照 JDK`() {
        fun eval(expr: String) = runtime.eval(expr, emptyMap())
        assertEquals(
            "e10adc3949ba59abbe56e057f20f883e",
            eval(
                "var b = Packages.java.security.MessageDigest.getInstance('MD5').digest(new Packages.java.lang.String('123456').getBytes('UTF-8'));" +
                    "var h = ''; for (var i = 0; i < b.length; i++) { var x = (b[i] & 0xff).toString(16); h += (x.length < 2 ? '0' : '') + x; } h",
            ),
        )
        assertEquals("七猫", eval("var b = new Packages.java.lang.String('xx七猫yy').getBytes('UTF-8'); new Packages.java.lang.String(b, 2, 6, 'UTF-8')"))
        val text = "第一章 正文".repeat(20)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(text.toByteArray())
        deflater.finish()
        val buf = ByteArray(1024)
        val packed = buf.copyOf(deflater.deflate(buf))
        val size = text.toByteArray().size
        assertEquals(
            text,
            runtime.eval(
                "var inf = new Packages.java.util.zip.Inflater(true); inf.setInput(packed, 0, packed.length);" +
                    "var out = Packages.java.util.Arrays.copyOf(packed, $size); inf.inflate(out); inf.end();" +
                    "String(new Packages.java.lang.String(out, 'UTF-8'))",
                mapOf("packed" to packed),
            ),
        )
    }
}
