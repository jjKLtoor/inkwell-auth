package com.radium.inkwell.ui.sourceauth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.sourceauth.LoginWebSession
import com.radium.inkwell.ui.components.AppScaffold
import com.radium.inkwell.ui.components.AppTextButton
import com.radium.inkwell.ui.components.AppTopBar
import com.radium.inkwell.ui.components.Dimens
import com.radium.inkwell.ui.components.SecondaryButton
import com.radium.inkwell.ui.components.PrimaryButton
import top.yukonga.miuix.kmp.basic.Card
import com.radium.inkwell.ui.components.rememberAppTopBarScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SourceLoginScreen(onBack: () -> Unit, viewModel: SourceLoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SourceLoginContent(state, onBack, viewModel::start, viewModel::cancel, viewModel::fail, viewModel::save, viewModel::logout)
}

@Composable
internal fun SourceLoginContent(
    state: SourceLoginState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit,
    onSave: (com.radium.inkwell.sourceauth.CookieSession) -> Unit,
    onLogout: () -> Unit,
    sessionFactory: (android.content.Context, com.radium.inkwell.core.source.auth.SourceAuthScope, () -> Unit) -> LoginWebSession = { context, scope, onFailure ->
        LoginWebSession(context, scope, WebViewNetworkGuard()::intercept, onFailure)
    },
) {
    var session by remember { mutableStateOf<LoginWebSession?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val topBarScroll = rememberAppTopBarScroll()
    var timeoutJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var blockedSite by remember { mutableStateOf<String?>(null) }
    var pageMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            LoginWebSession.cleanupOrphans()
            supported = LoginWebSession.supported()
        } catch (_: Exception) { onError("临时网页登录数据清理失败，请重试") }
    }
    BackHandler(state.browsing) { if (!capturing) onCancel() }
    AppScaffold(
        blurBars = false,
        topBar = { AppTopBar(state.name, topBarScroll, onBack = { if (!capturing && !state.busy) { if (state.browsing) onCancel() else onBack() } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Card(modifier = Modifier.fillMaxWidth().padding(Dimens.screenPadding)) {
                Column(Modifier.padding(Dimens.listHorizontal)) {
                    Text(if (state.browsing) pageMessage ?: "正在创建登录网页…" else state.message, style = MiuixTheme.textStyles.body1)
                    state.scope?.let { Text(it.originUrls.joinToString(" → "), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    if (state.browsing && blockedSite != null) Text("已拦截额外站点：" + blockedSite, style = MiuixTheme.textStyles.footnote1)
                }
            }
            if (state.browsing && state.scope != null) {
                PrimaryButton(if (capturing) "正在保存会话…" else "完成并保存会话", modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding), enabled = session != null && visible && !state.busy && !capturing, onClick = {
                    capturing = true
                    coroutineScope.launch {
                        try {
                            val current = session ?: return@launch
                            val captured = current.capture()
                            current.close().await()
                            onSave(captured)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            onError("会话读取或临时数据清理失败，请重试")
                        } finally { capturing = false }
                    }
                })
                AppTextButton("重新加载", enabled = !capturing && session != null, onClick = {
                    pageMessage = "正在重新加载…"
                    visible = false
                    loading = true
                    session?.view?.reload()
                })
                AppTextButton("取消登录", enabled = !capturing, onClick = onCancel)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        try {
                            val owned = sessionFactory(context, requireNotNull(state.scope)) {
                                loading = false
                                pageMessage = "网页加载失败，请重试"
                            }
                            owned.onPageEvent = { event ->
                                when (event) {
                                    com.radium.inkwell.sourceauth.LoginPageEvent.Loading -> {
                                        loading = true; visible = false; pageMessage = "正在加载登录网页…"
                                        timeoutJob?.cancel()
                                        timeoutJob = coroutineScope.launch {
                                            kotlinx.coroutines.delay(30000)
                                            if (session === owned && loading) { loading = false; pageMessage = "登录网页加载超时，请检查网络后重新加载" }
                                        }
                                    }
                                    com.radium.inkwell.sourceauth.LoginPageEvent.Visible -> {
                                        timeoutJob?.cancel()
                                        loading = false; visible = true
                                        pageMessage = "网页已显示，请自行完成登录；完成后保存会话"
                                    }
                                    is com.radium.inkwell.sourceauth.LoginPageEvent.Failed -> { timeoutJob?.cancel(); loading = false; pageMessage = event.reason }
                                    is com.radium.inkwell.sourceauth.LoginPageEvent.Blocked -> {
                                        loading = false
                                        blockedSite = event.origin ?: "不支持的地址"
                                        pageMessage = if (event.mainFrame) "登录页需要跳转到未授权站点：" + (event.origin ?: "不支持的地址")
                                            else "网页有资源被安全策略拦截，可能影响显示：" + (event.origin ?: "不支持的地址")
                                    }
                                }
                            }
                            session = owned
                            owned.view.apply {
                                tag = owned
                                // 等原生视图附着后再发起加载，避免依赖 factory 写状态触发外层重组。
                                post {
                                    if (session === owned) coroutineScope.launch {
                                        try {
                                            loading = true
                                            owned.start(requireNotNull(state.page).url, state.restored)
                                        } catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            loading = false
                                            pageMessage = "登录页无法打开，请取消后重试"
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            onError("当前 WebView 无法安全创建独立登录环境")
                            android.view.View(context)
                        }
                    },
                    onRelease = { released ->
                        val owned = released.tag as? LoginWebSession
                        try { owned?.close() } catch (_: Exception) { onError("临时网页登录数据清理失败，请重试") }
                        if (session === owned) { timeoutJob?.cancel(); session = null; loading = false; visible = false }
                    },
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        "登录前请核对上方来源和登录站点。仅允许所列 HTTPS 站点；Cookie 按域名匹配，只持久化有到期时间的 Cookie，不保存密码。",
                        modifier = Modifier.padding(Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1,
                    )
                    Text(
                        "保存后，来源请求会使用符合域名规则的 Cookie。保存会话不代表网站已确认登录，也不保证搜索或正文规则可用。",
                        modifier = Modifier.padding(horizontal = Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1,
                    )
                    if (state.page?.home == true) Text("来源未声明登录地址，这里仅尝试打开来源 HTTPS 首页，能否登录需自行确认。", modifier = Modifier.padding(Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1)
                    if (state.page?.upgraded == true) Text("原规则使用 HTTP 登录地址，这里仅尝试相同主机的 HTTPS 地址；不支持明文登录。", modifier = Modifier.padding(Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1)
                    if (state.page?.partial == true && state.page?.home != true && state.page?.upgraded != true) Text("此来源还定义了脚本或表单；这里仅打开其中明确声明的网页，不执行按钮脚本或购买操作。", modifier = Modifier.padding(Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1)
                    if (!supported) Text("系统 WebView 不支持所需的独立会话能力，请更新 WebView 后重试", modifier = Modifier.padding(Dimens.screenPadding))
                    PrimaryButton(if (state.restored == null) "登录" else "重新登录",
                        modifier = Modifier.fillMaxWidth().padding(Dimens.screenPadding),
                        enabled = state.loaded && state.page != null && supported && !state.busy, onClick = onStart)
                    AppTextButton("退出登录", enabled = state.loaded && state.scope != null && !state.busy, onClick = onLogout)
                }
            }
        }
    }
}
