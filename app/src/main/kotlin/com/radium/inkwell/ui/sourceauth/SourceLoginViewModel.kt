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
    val restored: CookieSession? = null,
    val browsing: Boolean = false,
    val busy: Boolean = false,
    val message: String = "正在读取登录配置",
)

class SourceLoginViewModel(
    private val sourceId: String,
    private val sources: BookSourceRepository,
    private val store: EncryptedSessionStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceLoginState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val source = sources.getRule(sourceId)
                if (source == null) { fail("来源不存在"); return@launch }
                val config = resolveLoginConfiguration(source)
                val scope = SourceAuthScope.create(source.id, source.baseUrl)
                val page = config as? LoginConfiguration.WebPage
                mutableState.value = mutableState.value.copy(name = source.name, scope = scope, page = page)
                if (page == null || scope == null) {
                    fail(when (config) {
                        LoginConfiguration.NotConfigured -> "该来源未配置登录"
                        is LoginConfiguration.Unsupported -> when (config.reason) {
                            LoginConfiguration.Reason.CUSTOM_UI -> "暂不支持该来源的自定义登录表单"
                            LoginConfiguration.Reason.SCRIPT_OR_DYNAMIC -> "暂不支持脚本或动态登录规则"
                            else -> "登录地址不受支持，仅允许同站 HTTPS 页面"
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
                mutableState.value = mutableState.value.copy(loaded = true, restored = restored,
                    message = when {
                        restored == null -> "未登录"
                        restored.cookies.isEmpty() -> "本地会话已过期，需要重新登录"
                        else -> "会话已保存，登录状态未知"
                    })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("本地会话无法恢复，请重新登录或退出登录")
            }
        }
    }

    fun start() { mutableState.value = mutableState.value.copy(browsing = true, message = "登录中，完成后可保存会话") }
    fun cancel() { mutableState.value = mutableState.value.copy(browsing = false, message = "已取消，之前保存的会话未修改") }
    fun fail(message: String) { mutableState.value = mutableState.value.copy(loaded = true, browsing = false, busy = false, message = message) }

    fun save(session: CookieSession) {
        val scope = mutableState.value.scope ?: return
        if (session.cookies.none { it.persistent && it.expiresAt > System.currentTimeMillis() }) {
            mutableState.value = mutableState.value.copy(browsing = false, message = "未发现可恢复的 Cookie，之前保存的会话未修改")
            return
        }
        mutableState.value = mutableState.value.copy(browsing = false, busy = true)
        viewModelScope.launch {
            try {
                // 保存是用户明确发起的操作；离开页面也应完成原子写，不能留下半截会话。
                withContext(NonCancellable) { store.save(scope, session) }
                val restored = store.load(scope)
                mutableState.value = mutableState.value.copy(busy = false, restored = restored,
                    message = if (restored?.cookies?.isNotEmpty() == true) "会话已保存，登录状态未知" else "未发现可持久化的 Cookie，无法恢复登录")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("会话保存失败，之前保存的会话未修改")
            }
        }
    }

    fun logout() {
        val scope = mutableState.value.scope ?: return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { com.radium.inkwell.sourceauth.LoginWebSession.cleanupOrphans() }
                    store.clear(scope)
                }
                mutableState.value = mutableState.value.copy(busy = false, restored = null, message = "已退出登录")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail("退出失败，请重试")
            }
        }
    }
}
