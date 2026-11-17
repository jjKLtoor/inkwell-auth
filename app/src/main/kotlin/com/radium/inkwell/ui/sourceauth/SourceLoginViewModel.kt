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
)

class SourceLoginViewModel(
    private val sourceId: String,
    private val sources: BookSourceRepository,
    private val store: EncryptedSessionStore,
    private val sessions: com.radium.inkwell.sourceauth.PersistentSourceSessions,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceLoginState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val source = sources.getRule(sourceId)
                if (source == null) { fail("来源不存在"); return@launch }
                val (config, scope) = withContext(Dispatchers.Default) {
                    resolveLoginConfiguration(source) to com.radium.inkwell.sourceauth.sourceAuthScope(source)
                }
                val page = config as? LoginConfiguration.WebPage
                val pages = if (page != null && scope != null) com.radium.inkwell.sourceauth.webLoginTargets(source).map {
                    LoginConfiguration.WebPage(it.url, it.partial, it.upgraded, it.home)
                }.plus(listOfNotNull(source.baseUrl.toHttpUrl().takeIf { scope.permits(it.toString()) && it.username.isEmpty() && it.password.isEmpty() }?.let {
                    LoginConfiguration.WebPage(it.toString(), partial = true, home = true)
                })).distinctBy { it.url } else emptyList()
                mutableState.value = mutableState.value.copy(name = source.name, scope = scope, page = page, pages = pages)
                if (page == null || scope == null) {
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
                if (withContext(Dispatchers.IO) { isPrivateNetworkHost(page.url.toHttpUrl().host) }) {
                    mutableState.value = mutableState.value.copy(page = null)
                    fail("已阻止访问私网登录地址")
                    return@launch
                }
                val restored = store.load(scope)
                // 仅恢复仍在当前规则候选列表里的入口，不继续打开旧规则留下的任意深链。
                val selected = pages.firstOrNull { it.url == restored?.loginPageUrl } ?: page
                mutableState.value = mutableState.value.copy(loaded = true, restored = restored,
                    page = selected,
                    browsing = restored?.cookies?.any { it.expiresAt > System.currentTimeMillis() } == true,
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

    fun start() { if (mutableState.value.busy || mutableState.value.browsing) return; mutableState.value = mutableState.value.copy(browsing = true, message = "正在打开登录网页…") }
    fun selectPage(url: String) {
        if (mutableState.value.busy || mutableState.value.browsing) return
        mutableState.value.pages.firstOrNull { it.url == url }?.let { mutableState.value = mutableState.value.copy(page = it) }
    }
    fun cancel() { mutableState.value = mutableState.value.copy(browsing = false, message = "已取消，之前保存的会话未修改") }
    fun fail(message: String) { mutableState.value = mutableState.value.copy(loaded = true, browsing = false, busy = false, message = message) }

    fun save(session: CookieSession) {
        if (mutableState.value.busy) return
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
