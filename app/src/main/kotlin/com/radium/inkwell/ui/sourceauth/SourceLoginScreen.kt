package com.radium.inkwell.ui.sourceauth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.data.source.WebViewNetworkGuard
import com.radium.inkwell.sourceauth.CookieSession
import com.radium.inkwell.sourceauth.LoginPageEvent
import com.radium.inkwell.sourceauth.LoginWebSession
import com.radium.inkwell.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    onSave: (CookieSession) -> Unit,
    onLogout: () -> Unit,
    sessionFactory: (android.content.Context, SourceAuthScope, () -> Unit) -> LoginWebSession = { context, scope, onFailure ->
        LoginWebSession(context, scope, WebViewNetworkGuard()::intercept, onFailure)
    },
    onDiagnosticEvent: (LoginPageEvent) -> Unit = {},
) {
    var session by remember { mutableStateOf<LoginWebSession?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val topBarScroll = rememberAppTopBarScroll()
    val pageColor = settingsPageColor()
    var supported by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(true) }
    var pageFailed by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var timeout by remember { mutableStateOf<Job?>(null) }
    var pageMessage by remember { mutableStateOf("正在打开登录页…") }
    var blocked by remember { mutableStateOf<List<LoginPageEvent.Blocked>>(emptyList()) }
    var review by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try { LoginWebSession.cleanupOrphans(); supported = LoginWebSession.supported() }
        catch (_: Exception) { onError("网页登录环境初始化失败，请重试") }
        finally { checking = false }
    }
    BackHandler(state.browsing) { if (!busy) onCancel() }

    fun reload() {
        val current = session ?: return
        visible = false
        pageMessage = "正在重新加载…"
        coroutineScope.launch {
            try {
                // 只回到初始 GET 页面，不重放用户刚才提交的账号表单。
                current.start(requireNotNull(state.page).url, null)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pageMessage = "登录页无法加载，请取消后重试"
            }
        }
    }

    AppScaffold(
        containerColor = pageColor,
        blurBars = !state.browsing,
        topBar = {
            if (state.browsing) AppToolbar(
                title = { ToolbarTitle(state.name) },
                navigationIcon = { BackButton(onClick = { if (!busy) onCancel() }) },
                actions = { AppTextButton("重载", enabled = !busy && session != null, onClick = ::reload) },
            ) else AppTopBar("来源登录", topBarScroll, onBack = onBack, containerColor = pageColor)
        },
    ) { padding ->
        if (state.browsing && state.scope != null) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS)) {
                    Text(pageMessage, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1)
                    Text(state.page?.url?.let { okhttp3.HttpUrl.Companion.run { it.toHttpUrlOrNull()?.host } }.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                blocked.filter { it.origin != null }.minByOrNull { it.kind.priority }?.let { item ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (item.kind == com.radium.inkwell.sourceauth.LoginResourceKind.STYLE) "网页样式未完整加载" else "网页需要额外站点", Modifier.weight(1f), style = MiuixTheme.textStyles.footnote1)
                        AppTextButton("核对站点", enabled = !busy && item.origin != null, onClick = { review = item.origin })
                    }
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        try {
                            val owned = sessionFactory(context, state.scope) { pageMessage = "网页加载失败，请重试" }
                            owned.onPageEvent = { event ->
                                onDiagnosticEvent(event)
                                when (event) {
                                    LoginPageEvent.Loading -> {
                                        visible = false
                                        pageFailed = false
                                        pageMessage = "正在加载登录页…"
                                        timeout?.cancel()
                                        timeout = coroutineScope.launch { delay(30000); if (session === owned && !visible) pageMessage = "加载较慢，可重载或查看受限站点" }
                                    }
                                    LoginPageEvent.Visible -> {
                                        timeout?.cancel(); visible = !pageFailed
                                        if (!pageFailed) pageMessage = if (blocked.isEmpty()) "请在下方网页完成登录" else "部分网页内容受限，可核对所需站点"
                                    }
                                    is LoginPageEvent.Failed -> { timeout?.cancel(); pageFailed = true; visible = false; pageMessage = event.reason }
                                    is LoginPageEvent.Blocked -> {
                                        val old = blocked.firstOrNull { it.origin == event.origin }
                                        if (old == null && blocked.size < 16) blocked = blocked + event
                                        else if (old != null && event.kind.priority < old.kind.priority) blocked = blocked.map { if (it.origin == event.origin) event else it }
                                        if (event.mainFrame) { timeout?.cancel(); pageMessage = "登录需要其他站点，请先核对" }
                                    }
                                }
                            }
                            session = owned
                            owned.view.apply {
                                tag = owned
                                post {
                                    if (session === owned) coroutineScope.launch {
                                        try { owned.start(requireNotNull(state.page).url, state.restored) }
                                        catch (error: Exception) {
                                            if (error is CancellationException) throw error
                                            pageMessage = "登录页无法打开，请取消后重试"
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) { onError("无法创建独立网页登录环境"); android.view.View(context) }
                    },
                    onRelease = { released ->
                        val owned = released.tag as? LoginWebSession
                        try { owned?.close() } catch (_: Exception) { onError("网页登录清理失败，请重试") }
                        if (session === owned) { timeout?.cancel(); session = null; visible = false; blocked = emptyList() }
                    },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gapM),
                ) {
                    SecondaryButton("取消", onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f))
                    PrimaryButton("保存会话", modifier = Modifier.weight(1f), enabled = visible && session != null && !state.busy,
                        loading = busy, onClick = {
                            val owned = session ?: return@PrimaryButton
                            busy = true
                            coroutineScope.launch {
                                try { val captured = owned.capture(); owned.close().await(); onSave(captured) }
                                catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    onError("会话读取或清理失败，请重试")
                                } finally { busy = false }
                            }
                        })
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                SectionHeader("来源账号")
                SettingGroup {
                    SettingRow(state.name, value = if (state.restored == null) "未保存会话" else "会话已保存", position = SettingGroupPosition.First)
                    SettingBlock {
                        Text(state.message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        state.scope?.originUrls?.forEach { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        PrimaryButton(if (state.restored == null) "登录" else "重新登录",
                            onClick = { blocked = emptyList(); pageMessage = "正在打开登录页…"; onStart() },
                            enabled = state.loaded && state.page != null && supported && !checking && !state.busy, modifier = Modifier.fillMaxWidth())
                    }
                }
                SettingFootnote(when {
                    checking -> "正在检查网页登录环境…"
                    !supported -> "请更新系统 WebView 后重试"
                    state.page?.home == true -> "未声明登录地址，仅尝试打开来源首页。"
                    state.page?.upgraded == true -> "旧地址仅尝试 HTTPS，不使用明文登录。"
                    state.page?.partial == true -> "仅打开规则中明确声明的网页，不执行表单按钮脚本。"
                    else -> "请核对登录站点。账号密码在网站页面输入。"
                })
                SectionHeader("会话管理")
                SettingGroup {
                    SettingRow("支持范围与隐私", onClick = { showHelp = true }, position = SettingGroupPosition.Alone)
                }
                SettingGroup {
                    SettingRow("退出登录", destructive = true, onClick = if (state.loaded && state.scope != null && !state.busy) onLogout else null, position = SettingGroupPosition.Alone)
                }
                SettingFootnote("保存会话不代表网站已确认登录。搜索、目录和正文需要分别测试。")
            }
        }
    }
    if (showHelp) AppAlertDialog(
        title = "网页登录", onDismissRequest = { showHelp = false }, confirmText = "知道了",
        onConfirm = { showHelp = false }, closeOnConfirm = true, dismissText = null,
        text = "不保存密码，只保存可恢复的来源 Cookie。额外站点默认拦截，经你逐个确认后仅在本次网页登录中生效，关闭后清除；不会加入阅读请求的认证范围。第三方 Cookie、无效证书和私网仍受限制。",
    )
    review?.let { origin ->
        AppAlertDialog(
            title = "允许此站点？", onDismissRequest = { if (!busy) review = null },
            text = origin + "\n\n请求类型：" + (blocked.firstOrNull { it.origin == origin }?.kind?.label ?: "网页资源") + "。网页需要从此站点加载内容或继续登录。它可能收到按域名匹配的网页 Cookie。只在本次登录有效，不加入阅读请求的认证范围；允许后重新加载登录页，请只允许你信任的站点。",
            confirmText = "本次允许", confirmLoading = busy, dismissEnabled = !busy,
            onConfirm = {
                val owned = session ?: return@AppAlertDialog
                busy = true
                coroutineScope.launch {
                    try {
                        if (owned.approveTemporaryOrigin(origin)) {
                            blocked = blocked.filterNot { it.origin == origin }
                            review = null
                            reload()
                        } else { review = null; pageMessage = "此站点不符合安全要求，或已达到本次许可上限" }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        review = null
                        pageMessage = "站点检查失败，请稍后重试"
                    } finally { busy = false }
                }
            },
        )
    }
}
