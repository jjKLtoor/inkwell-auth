package com.radium.inkwell.ui.sourceauth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.isPrivateNetworkHost
import com.radium.inkwell.data.repo.BookSourceRepository
import com.radium.inkwell.sourceauth.CookieSession
import com.radium.inkwell.sourceauth.EncryptedSessionStore
import com.radium.inkwell.sourceauth.LoginConfiguration
import com.radium.inkwell.sourceauth.resolveLoginConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import com.radium.inkwell.sourceauth.DraftLoginSession
import com.radium.inkwell.sourceauth.SourceLoginForm
import com.radium.inkwell.sourceauth.parseSourceLoginForm
import com.radium.inkwell.core.source.BookSourceEngine
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.js.SourceScriptInteraction
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SourceLoginState(
    val loaded: Boolean = false,
    val name: String = "来源登录",
    val scope: SourceAuthScope? = null,
    val page: LoginConfiguration.WebPage? = null,
    val pages: List<LoginConfiguration.WebPage> = emptyList(),
    val restored: CookieSession? = null,
    val browsing: Boolean = false,
    val busy: Boolean = false,
    val message: String = "正在读取登录配置",
    val form: SourceLoginForm? = null,
    val formError: Boolean = false,
    val sourceFeedback: String = "",
    val sourceUserAgent: String? = null,
)

class SourceLoginViewModel(
    private val sourceId: String,
    private val sources: BookSourceRepository,
    private val store: EncryptedSessionStore,
    private val sessions: com.radium.inkwell.sourceauth.PersistentSourceSessions,
    private val engine: BookSourceEngine,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceLoginState())
    val state = mutableState.asStateFlow()

    private var loadedSource: BookSourceRule? = null
    private var actionJob: Job? = null
    private var actionToken: Any? = null
    private var actionPage: LoginConfiguration.WebPage? = null
    private class BrowserWait(val draft: DraftLoginSession, val done: CompletableDeferred<String>)
    private var browserWait: BrowserWait? = null

    init {
        viewModelScope.launch {
            try {
                val source = sources.getRule(sourceId)
                if (source == null) { fail("来源不存在"); return@launch }
                loadedSource = source
                val form = withContext(Dispatchers.Default) { runCatching { parseSourceLoginForm(source) } }
                val (config, scope) = withContext(Dispatchers.Default) {
                    resolveLoginConfiguration(source) to com.radium.inkwell.sourceauth.sourceAuthScope(source)
                }
                val page = config as? LoginConfiguration.WebPage
                val pages = if (page != null && scope != null) com.radium.inkwell.sourceauth.webLoginTargets(source).map {
                    LoginConfiguration.WebPage(it.url, it.partial, it.upgraded, it.home)
                }.plus(listOfNotNull(source.baseUrl.toHttpUrl().takeIf { scope.permits(it.toString()) && it.username.isEmpty() && it.password.isEmpty() }?.let {
                    LoginConfiguration.WebPage(it.toString(), partial = true, home = true)
                })).distinctBy { it.url } else emptyList()
                mutableState.value = mutableState.value.copy(name = source.name, scope = scope, page = page, pages = pages, form = form.getOrNull(), formError = form.isFailure,
                    sourceUserAgent = source.headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value)
                if (scope == null || page == null && form.getOrNull() == null) {
                    fail(when (config) {
                        LoginConfiguration.NotConfigured -> "该来源未配置登录"
                        is LoginConfiguration.Unsupported -> when (config.reason) {
                            LoginConfiguration.Reason.CUSTOM_UI -> "该来源需要自定义表单，且未声明可识别的网页登录地址"
                            LoginConfiguration.Reason.SCRIPT_OR_DYNAMIC -> "未找到脚本中明确声明的网页登录地址，暂不执行此登录脚本"
                            else -> "登录地址不受支持，仅允许明确声明的有效 HTTPS 页面"
                        }
                        else -> "登录配置不可用"
                    })
                    return@launch
                }
                if (page != null && withContext(Dispatchers.IO) { isPrivateNetworkHost(page.url.toHttpUrl().host) }) {
                    mutableState.value = mutableState.value.copy(page = null)
                    fail("已阻止访问私网登录地址")
                    return@launch
                }
                val restored = store.load(scope)
                // 仅恢复仍在当前规则候选列表里的入口，不继续打开旧规则留下的任意深链。
                val selected = pages.firstOrNull { it.url == restored?.loginPageUrl } ?: page
                mutableState.value = mutableState.value.copy(loaded = true, restored = restored,
                    page = selected,
                    browsing = form.getOrNull() == null && restored?.cookies?.any { it.expiresAt > System.currentTimeMillis() } == true,
                    message = when {
                        sessions.storageFailed(scope) -> "会话更新未能保存，请重新登录"
                        restored == null -> "未登录"
                        restored.cookies.isEmpty() && restored.loginData.isEmpty() -> "本地会话已过期，需要重新登录"
                        else -> "会话已保存，登录状态未知"
                    })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("本地会话无法恢复，请重新登录或退出登录")
            }
        }
    }

    private fun cancelAction() {
        if (actionToken != null) mutableState.value = mutableState.value.copy(page = actionPage)
        actionPage = null
        actionToken = null
        browserWait?.let { it.draft.revoke(); it.done.cancel() }; browserWait = null
        actionJob?.cancel(); actionJob = null
    }

    /** 只执行用户点选的规则按钮；网页返回时续跑同一次脚本，不以重跑脚本模拟 await。 */
    fun runAction(index: Int, values: Map<String, String>) {
        if (mutableState.value.busy || mutableState.value.browsing || actionJob?.isActive == true) return
        val source = loadedSource ?: return
        val scope = mutableState.value.scope ?: return
        val form = mutableState.value.form ?: return
        val action = if (index == -1 && form.hasSubmit) "login(result)" else form.controls.getOrNull(index)?.takeIf { it.type == "button" }?.action
        if (action.isNullOrBlank()) return
        val permitted = form.controls.filter { it.type == "edit" || it.type == "password" }.map { it.name }.toSet()
        val fields = values.filterKeys { it in permitted }
        if (fields.size > 100 || fields.values.any { it.length > 8192 } || fields.values.sumOf { it.length } > 65536) { fail("登录表单内容过长"); return }
        val token = Any(); actionToken = token
        val entry = mutableState.value.page
        actionPage = entry
        mutableState.value = mutableState.value.copy(busy = true, message = "正在执行来源登录操作…", sourceFeedback = "")
        actionJob = viewModelScope.launch {
            var draft: DraftLoginSession? = null
            try {
                val original = sessions.open(scope)
                val saved = sessions.savedSnapshot(scope)
                val initial = CookieSession(original.cookiesForRendering(), original.loginData.toMap(), loginPageUrl = saved?.loginPageUrl)
                val working = DraftLoginSession(scope, initial); draft = working
                val owner = currentCoroutineContext()[Job]!!
                val host = object : SourceScriptInteraction {
                    override fun notice(message: String) {
                        viewModelScope.launch { if (actionToken === token) mutableState.value = mutableState.value.copy(sourceFeedback = message.take(2000)) }
                    }
                    override fun browser(url: String, title: String?, await: Boolean): String = runBlocking(owner + com.radium.inkwell.core.source.auth.SourceRequestSession.binding(working)) {
                        val target = url.toHttpUrlOrNull() ?: error("来源登录地址无效")
                        check(working.active && scope.permits(url) && target.username.isEmpty() && target.password.isEmpty()) { "来源登录站点未授权" }
                        if (withContext(Dispatchers.IO) { isPrivateNetworkHost(target.host) }) error("已阻止私网登录地址")
                        val pending = BrowserWait(working, CompletableDeferred())
                        withContext(Dispatchers.Main) {
                            com.radium.inkwell.sourceauth.LoginWebSession.awaitIdle()
                            check(actionToken === token && browserWait == null)
                            browserWait = pending
                            mutableState.value = mutableState.value.copy(busy = false, browsing = true, page = LoginConfiguration.WebPage(url, partial = true),
                                restored = working.snapshot(entry?.url), message = "请完成网页登录后保存会话，随后继续来源脚本")
                        }
                        if (await) pending.done.await() else ""
                    }
                }
                val result = engine.runLoginAction(source, form.script + "\n" + action, fields, working, host)
                browserWait?.done?.await()
                currentCoroutineContext().ensureActive()
                if (result == "false") error("来源操作返回失败")
                val snapshot = working.snapshot(entry?.url)
                withContext(NonCancellable) { sessions.replace(scope, snapshot) }
                if (actionToken === token) mutableState.value = mutableState.value.copy(restored = sessions.savedSnapshot(scope),
                    message = "会话已保存；来源脚本已执行，登录状态未知")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (actionToken === token) mutableState.value = mutableState.value.copy(message = "来源登录操作未完成；原会话未修改，请检查规则所需能力")
            } finally {
                draft?.revoke()
                if (actionToken === token) {
                    browserWait?.done?.cancel(); browserWait = null; actionJob = null; actionToken = null; actionPage = null
                    mutableState.value = mutableState.value.copy(busy = false, browsing = false, page = entry)
                }
            }
        }
    }
    fun start() { if (mutableState.value.busy || mutableState.value.browsing) return; mutableState.value = mutableState.value.copy(browsing = true, message = "正在打开登录网页…") }
    fun selectPage(url: String) {
        if (mutableState.value.busy || mutableState.value.browsing) return
        mutableState.value.pages.firstOrNull { it.url == url }?.let { mutableState.value = mutableState.value.copy(page = it) }
    }
    fun cancel() { cancelAction(); mutableState.value = mutableState.value.copy(browsing = false, busy = false, message = "已取消来源操作") }
    fun fail(message: String) { cancelAction(); mutableState.value = mutableState.value.copy(loaded = true, browsing = false, busy = false, message = message) }

    fun refreshCookies() {
        if (mutableState.value.busy || mutableState.value.browsing) return
        val scope = mutableState.value.scope ?: return
        mutableState.value = mutableState.value.copy(busy = true, message = "正在读取本地 Cookie…")
        viewModelScope.launch {
            try {
                val saved = sessions.savedSnapshot(scope)
                mutableState.value = mutableState.value.copy(busy = false, restored = saved, message = "本地 Cookie，登录状态未知")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(busy = false, message = "Cookie 无法读取，原会话未修改")
            }
        }
    }

    fun editCookie(cookie: okhttp3.Cookie, value: String?) {
        if (mutableState.value.busy || mutableState.value.browsing) return
        val scope = mutableState.value.scope ?: return
        mutableState.value = mutableState.value.copy(busy = true, message = "正在保存 Cookie…")
        viewModelScope.launch {
            try {
                val saved = sessions.editCookie(scope, cookie, value)
                mutableState.value = mutableState.value.copy(busy = false, restored = saved,
                    message = if (value == null) "Cookie 已删除，登录状态未知" else "Cookie 已更新，登录状态未知")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(busy = false, message = when (error) {
                    is IllegalArgumentException -> "Cookie 值格式不正确，原会话未修改"
                    is IllegalStateException -> "Cookie 已变化或过期，请刷新后重试"
                    else -> "Cookie 保存失败，原会话未修改"
                })
            }
        }
    }

    fun save(session: CookieSession) {
        if (mutableState.value.busy) return
        browserWait?.let { pending ->
            try {
                pending.draft.importCookies(session)
                browserWait = null
                mutableState.value = mutableState.value.copy(browsing = false, busy = true, message = "正在继续来源登录脚本…")
                pending.done.complete("")
            } catch (_: Exception) { fail("网页登录数据不符合当前来源的认证范围") }
            return
        }
        val scope = mutableState.value.scope ?: return
        if (session.cookies.none { it.persistent && it.expiresAt > System.currentTimeMillis() }) {
            mutableState.value = mutableState.value.copy(message = "未发现可恢复的 Cookie，之前保存的会话未修改")
            return
        }
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                // 保存是用户明确发起的操作；离开页面也应完成原子写，不能留下半截会话。
                val saved = CookieSession(session.cookies, session.loginData, session.webOrigins, session.allowWebResources, mutableState.value.page?.url)
                withContext(NonCancellable) { sessions.replace(scope, saved) }
                val restored = store.load(scope)
                mutableState.value = mutableState.value.copy(busy = false, restored = restored,
                    message = if (restored?.cookies?.isNotEmpty() == true) "会话已保存，登录状态未知" else "未发现可持久化的 Cookie，无法恢复登录")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(busy = false, message = "会话保存失败，之前保存的会话未修改")
            }
        }
    }

    fun logout() {
        if (mutableState.value.busy || mutableState.value.browsing) return
        val scope = mutableState.value.scope ?: return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { com.radium.inkwell.sourceauth.LoginWebSession.cleanupOrphans() }
                    sessions.logout(scope)
                }
                mutableState.value = mutableState.value.copy(busy = false, restored = null, message = "已退出登录")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("退出失败，请重试")
            }
        }
    }
}
