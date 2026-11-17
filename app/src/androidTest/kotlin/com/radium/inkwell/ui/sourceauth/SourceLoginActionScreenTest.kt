package com.radium.inkwell.ui.sourceauth

import android.content.Intent
import android.webkit.WebResourceResponse
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.radium.inkwell.MainActivity
import com.radium.inkwell.core.source.*
import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.sourceauth.*
import com.radium.inkwell.ui.theme.InkwellTheme
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SourceLoginActionScreenTest {
    @Test fun 按规则打开网页后保存会话继续执行脚本并加密提交() = checkAction(false)
    @Test fun 取消网页登录不覆盖原会话() = checkAction(true)

    private fun checkAction(cancel: Boolean) = runBlocking<Unit> {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val db=Room.inMemoryDatabaseBuilder(context,InkwellDb::class.java).setQueryCoroutineContext(Dispatchers.IO).build()
        val sources=BookSourceRepository(db.bookSourceDao(),db.bookSourceHitDao())
        val rule=BookSourceRule("https://example.com/#"+UUID.randomUUID(),bookSourceName="受控来源",
            loginUrl="""function act(){cookie.removeCookie(source.bookSourceUrl);java.startBrowserAwait('https://example.com/login','登录');java.toast('fixture-after-await');if(String(cookie.getKey('example.com','sid'))!=='fixture')throw new Error('missing');source.putLoginHeader(JSON.stringify({'X-Identity':'fixture'}));java.toast('续跑完成');} """,
            loginUi="""[{name:'用户名',type:'edit'},{name:'密码',type:'password'},{name:'账号网页登录',type:'button',action:'act()'}]""")
        sources.importJson(sources.encodeRule(rule)).getOrThrow()
        val scope=requireNotNull(sourceAuthScope(rule));val root=File(context.noBackupFilesDir,"login-action-"+UUID.randomUUID())
        val store=EncryptedSessionStore(root,KeystoreSessionCipher());val sessions=PersistentSourceSessions(store)
        if(cancel) sessions.replace(scope,CookieSession(listOf(Cookie.Builder().name("sid").value("old").hostOnlyDomain("example.com").path("/").secure().expiresAt(System.currentTimeMillis()+3600000).build())))
        val client=OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body("fixture".toResponseBody()).build() }.build()
        val engine=BookSourceEngine(SourceHttpClient(client),scriptRuntime=RhinoScriptRuntime(),sessions=sessions)
        val capturedCount=java.util.concurrent.atomic.AtomicInteger(-1)
        val holder=ViewModelStore();lateinit var vm:SourceLoginViewModel
        inst.runOnMainSync { vm=SourceLoginViewModel(rule.id,sources,store,sessions,engine);holder.put("fixture",vm) }
        val activity=inst.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            inst.runOnMainSync { activity.setContent { InkwellTheme {
                val state=vm.state.collectAsState().value
                SourceLoginContent(state,{},vm::start,vm::cancel,vm::fail,{ captured -> capturedCount.set(captured.cookies.size);vm.save(captured) },vm::logout,
                    sessionFactory={ctx,auth,error -> LoginWebSession(ctx,auth,{ WebResourceResponse("text/html","UTF-8",ByteArrayInputStream("""<html><meta name='viewport' content='width=device-width,initial-scale=1'><body><button onclick="document.cookie='sid=fixture;Path=/;Secure;Max-Age=3600';this.innerText='受控登录完成'">受控网页登录</button></body></html>""".toByteArray())) },error) },
                    onScriptAction=vm::runAction)
            } } }
            val device=UiDevice.getInstance(inst)
            assertNotNull(device.wait(Until.findObject(By.text(if(cancel)"重新登录" else "登录")),10000))
            device.findObject(By.text(if(cancel)"重新登录" else "登录")).click()
            assertNotNull(device.wait(Until.findObject(By.text("账号网页登录")),10000))
            if(!cancel) {
                val inputs=device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(2,inputs.size);inputs[0].text="fixture-user";inputs[1].text="fixture-password"
            }
            device.findObject(By.text("账号网页登录")).click()
            assertNotNull(device.wait(Until.findObject(By.text("受控网页登录")),15000))
            if(cancel) {
                device.findObject(By.text("取消")).click()
                withTimeout(10000) { while(vm.state.value.browsing || vm.state.value.busy)delay(100) }
                assertEquals("old",store.load(scope)!!.cookies.single().value)
            } else {
                device.findObject(By.text("受控网页登录")).click()
                assertTrue(device.wait(Until.hasObject(By.text("受控登录完成")),10000))
                device.findObject(By.text("保存会话")).click()
                val completed=device.wait(Until.hasObject(By.textContains("来源脚本已执行")),15000)
                assertTrue("state="+vm.state.value.message+" feedback="+vm.state.value.sourceFeedback+" captured="+capturedCount.get(),completed)
                assertEquals("fixture",store.load(scope)!!.cookies.single().value)
                assertEquals(setOf('$'+"loginHeader"),store.load(scope)!!.loginData.keys)
                assertTrue(sessions.open(scope).loginData['$'+"loginInfo"].orEmpty().contains("fixture-password"))
                device.takeScreenshot(File(context.getExternalFilesDir(null),"login-action-form.png"))
            }
        } finally {
            inst.runOnMainSync { holder.clear();activity.finish() }
            withContext(Dispatchers.Main) { LoginWebSession.awaitIdle() }
            sessions.logout(scope);db.close();root.delete()
        }
    }
}
