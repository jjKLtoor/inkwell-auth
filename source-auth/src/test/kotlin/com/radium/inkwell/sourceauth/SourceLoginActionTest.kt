package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.auth.*
import com.radium.inkwell.core.source.js.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.UnknownHostException
import javax.crypto.KeyGenerator
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SourceLoginActionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun cookie(host: String, value: String) = Cookie.Builder().name("sid").value(value).hostOnlyDomain(host).path("/").secure()
        .expiresAt(System.currentTimeMillis()+3600000).build()
    private fun store(): EncryptedSessionStore { val key=KeyGenerator.getInstance("AES").generateKey(); return EncryptedSessionStore(temp.newFolder(), AesGcmSessionCipher { key }) }

    @Test fun 网页确认后同次脚本继续读取Cookie并由同一引擎接入后续请求且密码不落盘() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false); server.start()
            val source=BookSourceRule(server.url("/").toString(), searchUrl="/search")
            val scope=requireNotNull(SourceAuthScope.create(source.id, source.baseUrl))
            val store=store(); val sessions=PersistentSourceSessions(store)
            sessions.replace(scope, CookieSession(listOf(cookie("localhost","old"))))
            val original=sessions.open(scope)
            val draft=DraftLoginSession(scope, sessions.savedSnapshot(scope))
            val http=SourceHttpClient(OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(),trust.trustManager).build(), allowPrivateNetwork=true)
            val engine=BookSourceEngine(http, scriptRuntime=RhinoScriptRuntime(), sessions=sessions)
            var opens=0
            val host=object:SourceScriptInteraction {
                override fun browser(url:String,title:String?,await:Boolean):String {
                    assertTrue(await); opens++
                    assertEquals("old", original.loadForRequest(server.url("/")).single().value)
                    draft.importCookies(CookieSession(listOf(cookie("localhost","new"))))
                    return ""
                }
                override fun notice(message:String) {}
            }
            server.enqueue(MockResponse().setBody("refetched")); server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            val script="""function act(){java.startBrowserAwait(source.bookSourceUrl+'login','login');source.put('stage','continued');source.putLoginHeader(JSON.stringify({'X-Identity':cookie.getKey(source.bookSourceUrl,'sid')}));return java.ajax(source.bookSourceUrl+'info');} act()"""
            assertEquals("""{"ok":true}""", engine.runLoginAction(source,script,mapOf("password" to "fixture-password"),draft,host))
            assertEquals(1,opens)
            assertEquals("sid=new",server.takeRequest().getHeader("Cookie"))
            val info=server.takeRequest(); assertEquals("sid=new",info.getHeader("Cookie"));assertEquals("new",info.getHeader("X-Identity"))
            assertEquals("old",store.load(scope)!!.cookies.single().value)
            sessions.replace(scope,draft.snapshot(null)); draft.revoke()
            val infoKey='$'+"loginInfo"
            assertTrue(sessions.open(scope).loginData[infoKey]!!.contains("fixture-password"))
            assertFalse(store.load(scope)!!.loginData.containsKey(infoKey))
            server.enqueue(MockResponse().setBody("after-login"))
            assertEquals("after-login",engine.fetchSearchPage(source,"fixture").bodyText)
            assertEquals("sid=new",server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun 登录脚本越界请求在DNS前拒绝且不发送表单凭据() = runBlocking<Unit> {
        var lookups=0
        val source=BookSourceRule("https://example.com/")
        val scope=requireNotNull(SourceAuthScope.create(source.id,source.baseUrl))
        val draft=DraftLoginSession(scope,null)
        val http=SourceHttpClient(OkHttpClient.Builder().dns { lookups++;throw UnknownHostException() }.build())
        val host=object:SourceScriptInteraction { override fun browser(url:String,title:String?,await:Boolean)=""; override fun notice(message:String){} }
        val result=BookSourceEngine(http,scriptRuntime=RhinoScriptRuntime()).runLoginAction(source,"java.ajax('https://outside.invalid/')",emptyMap(),draft,host)
        assertEquals(0,lookups)
        assertNotNull(result)
    }

    @Test fun 脚本失败不暴露原异常凭据且取消草稿不能覆盖旧身份() = runBlocking<Unit> {
        val source=BookSourceRule("https://example.com/")
        val scope=requireNotNull(SourceAuthScope.create(source.id,source.baseUrl))
        val store=store();val sessions=PersistentSourceSessions(store)
        sessions.replace(scope,CookieSession(listOf(cookie("example.com","old"))))
        val draft=DraftLoginSession(scope,sessions.savedSnapshot(scope))
        val host=object:SourceScriptInteraction { override fun browser(url:String,title:String?,await:Boolean)=""; override fun notice(message:String){} }
        val error=assertFailsWith<SourceException> { BookSourceEngine(SourceHttpClient(),scriptRuntime=RhinoScriptRuntime()).runLoginAction(source,"throw new Error('fixture-secret')",emptyMap(),draft,host) }
        assertFalse(error.message.orEmpty().contains("fixture-secret"))
        draft.revoke();assertFails { draft.snapshot(null) }
        assertEquals("old",store.load(scope)!!.cookies.single().value)
    }

    @Test fun 当前快照的等待网页登录脚本可在Cookie更新后继续执行但不代表真实登录() = runBlocking<Unit> {
        val path=System.getenv("INKWELL_AUTH_SNAPSHOT") ?: return@runBlocking
        val rows=kotlinx.serialization.json.Json.parseToJsonElement(java.io.File(path).readText()) as kotlinx.serialization.json.JsonArray
        val sources=rows.mapNotNull { runCatching { BookSourceRule.fromJson(it.toString()) }.getOrNull() }
            .filter { it.group.contains("大厂正版") && !it.loginUi.isNullOrBlank() && it.loginUrl.orEmpty().contains("startBrowserAwait") }
        assertTrue(sources.isNotEmpty())
        for(source in sources) {
            val form=requireNotNull(parseSourceLoginForm(source));val scope=requireNotNull(sourceAuthScope(source))
            val names=linkedSetOf<String>()
            org.mozilla.javascript.Parser().parse(form.script,"fixture",1).visit { node ->
                if(node is org.mozilla.javascript.ast.FunctionCall) {
                    val target=node.target as? org.mozilla.javascript.ast.PropertyGet
                    if((target?.target as? org.mozilla.javascript.ast.Name)?.identifier=="cookie" && target.property.identifier=="getKey") {
                        (node.arguments.getOrNull(1) as? org.mozilla.javascript.ast.StringLiteral)?.value?.let(names::add)
                    }
                };true
            }
            val draft=DraftLoginSession(scope,null);val notices=mutableListOf<String>();var opens=0
            val client=OkHttpClient.Builder().addInterceptor { chain ->
                check(scope.permits(chain.request().url.toString()))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline Fixture")
                    .body("{\"data\":{\"name\":\"fixture-user\"}}".toResponseBody()).build()
            }.build()
            val host=object:SourceScriptInteraction {
                override fun browser(url:String,title:String?,await:Boolean):String {
                    assertTrue(await);opens++
                    draft.importCookies(CookieSession(names.map { cookie(okhttp3.HttpUrl.Companion.run { url.toHttpUrl() }.host,"fixture-session").newBuilder().name(it).build() }))
                    return ""
                }
                override fun notice(message:String){notices+=message}
            }
            val action=form.controls.first { it.type=="button" && !it.action.isNullOrBlank() }.action!!
            BookSourceEngine(SourceHttpClient(client),scriptRuntime=RhinoScriptRuntime()).runLoginAction(source,form.script+"\n"+action,emptyMap(),draft,host)
            assertEquals(1,opens)
            assertTrue(notices.any { it.contains("fixture-user") }, "未观察到网页后的来源用户信息处理")
            draft.revoke()
        }
    }

}
