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
    var session by remember { mutableStateOf<LoginWebSession?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val topBarScroll = rememberAppTopBarScroll()
    var capturing by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            LoginWebSession.cleanupOrphans()
            supported = LoginWebSession.supported()
        } catch (_: Exception) { viewModel.fail("临时网页登录数据清理失败，请重试") }
    }
    BackHandler(state.browsing) { if (!capturing) viewModel.cancel() }
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        try { current.start(requireNotNull(state.page).url, state.restored) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            viewModel.fail("登录页无法打开，请重试")
        }
    }
    AppScaffold(
        blurBars = false,
        topBar = { AppTopBar(state.name, topBarScroll, onBack = { if (!capturing && !state.busy) { if (state.browsing) viewModel.cancel() else onBack() } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Card(modifier = Modifier.fillMaxWidth().padding(Dimens.screenPadding)) {
                Column(Modifier.padding(Dimens.listHorizontal)) {
                    Text(state.message, style = MiuixTheme.textStyles.body1)
                    state.scope?.let { Text(it.originUrl, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
            }
            if (state.browsing && state.scope != null) {
                PrimaryButton(if (capturing) "正在保存会话…" else "完成并保存会话", modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.screenPadding), enabled = session != null && !state.busy && !capturing, onClick = {
                    capturing = true
                    coroutineScope.launch {
                        try {
                            val current = session ?: return@launch
                            val captured = current.capture()
                            current.close().await()
                            viewModel.save(captured)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            viewModel.fail("会话读取或临时数据清理失败，请重试")
                        } finally { capturing = false }
                    }
                })
                AppTextButton("取消登录", enabled = !capturing, onClick = viewModel::cancel)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        try {
                            LoginWebSession(context, requireNotNull(state.scope), WebViewNetworkGuard()::intercept) {
                                viewModel.fail("网页加载失败，请重试")
                            }.also { session = it }.view
                        } catch (_: Exception) {
                            viewModel.fail("当前 WebView 无法安全创建独立登录环境")
                            android.view.View(context)
                        }
                    },
                    onRelease = {
                        try { session?.close() } catch (_: Exception) { viewModel.fail("临时网页登录数据清理失败，请重试") }
                        session = null
                    },
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        "仅支持同站 HTTPS Cookie 登录；跨站登录、自定义表单和网页本地存储暂不支持。只持久化有到期时间的 Cookie，不保存密码。",
                        modifier = Modifier.padding(Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1,
                    )
                    Text(
                        "保存后，同站阅读请求会使用对应来源的 Cookie。保存会话不代表网站已确认登录，也不保证搜索或正文规则可用。",
                        modifier = Modifier.padding(horizontal = Dimens.screenPadding), style = MiuixTheme.textStyles.footnote1,
                    )
                    if (!supported) Text("系统 WebView 不支持所需的独立会话能力，请更新 WebView 后重试", modifier = Modifier.padding(Dimens.screenPadding))
                    PrimaryButton(if (state.restored == null) "登录" else "重新登录",
                        modifier = Modifier.fillMaxWidth().padding(Dimens.screenPadding),
                        enabled = state.loaded && state.page != null && supported && !state.busy, onClick = viewModel::start)
                    AppTextButton("退出登录", enabled = state.loaded && state.scope != null && !state.busy, onClick = viewModel::logout)
                }
            }
        }
    }
}
