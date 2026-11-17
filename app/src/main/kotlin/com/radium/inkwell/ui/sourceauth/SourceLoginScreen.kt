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
import com.radium.inkwell.sourceauth.LoginWebDialog
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
    SourceLoginContent(state, onBack, viewModel::start, viewModel::cancel, viewModel::fail, viewModel::save, viewModel::logout,
        onSelectPage = viewModel::selectPage, onRefreshCookies = viewModel::refreshCookies, onEditCookie = viewModel::editCookie, onScriptAction = viewModel::runAction)
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
    onSelectPage: (String) -> Unit = {},
    onRefreshCookies: () -> Unit = {},
    onEditCookie: (okhttp3.Cookie, String?) -> Unit = { _, _ -> },
    onScriptAction: (Int, Map<String, String>) -> Unit = { _, _ -> },
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
    var blockedResources by remember { mutableStateOf(false) }
    var webDialog by remember { mutableStateOf<LoginWebDialog?>(null) }
    var currentOrigin by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var showCookies by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    // 不用 rememberSaveable，表单可能含密码，不进入进程恢复或备份。
    val formValues = remember(state.form) { mutableStateMapOf<String, String>().apply {
        state.form?.controls?.filter { it.type == "edit" || it.type == "password" }?.forEach { put(it.name, it.initial) }
    } }

    LaunchedEffect(Unit) {
        try { LoginWebSession.cleanupOrphans(); supported = LoginWebSession.supported() }
        catch (_: Exception) { onError("网页登录环境初始化失败，请重试") }
        finally { checking = false }
    }
    fun back() { if (!busy && session?.goBack() != true) onCancel() }
    BackHandler(state.browsing) { back() }
    BackHandler(showForm && !state.browsing) { onCancel(); showForm = false; formValues.clear() }
    LaunchedEffect(state.message) {
        if (state.browsing && (state.message.contains("会话已保存") || state.message.contains("Cookie") || state.message.contains("保存失败"))) pageMessage = state.message
    }

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

    if (showCookies) {
        BackHandler { showCookies = false }
        SourceCookieScreen(state, { showCookies = false }, onRefreshCookies, onEditCookie)
        return
    }

    if (showForm && !state.browsing && state.form != null) {
        SourceLoginFormScreen(state, formValues, { name, value -> formValues[name] = value }, onScriptAction,
            onBack = { onCancel(); showForm = false; formValues.clear() }, onCancel = onCancel)
        return
    }
    AppScaffold(
        containerColor = pageColor,
        blurBars = !state.browsing,
        topBar = {
            if (state.browsing) AppToolbar(
                title = { ToolbarTitle(state.name) },
                navigationIcon = { BackButton(onClick = ::back) },
                actions = {
                    AppTextButton("会话", enabled = !busy, onClick = onCancel)
                    AppTextButton("重载", enabled = !busy && session != null, onClick = ::reload)
                },
            ) else AppTopBar("来源登录", topBarScroll, onBack = onBack, containerColor = pageColor)
        },
    ) { padding ->
        if (state.browsing && checking) {
            LoadingState(Modifier.padding(padding))
        } else if (state.browsing && state.scope != null && supported) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = Dimens.listHorizontal, vertical = Dimens.gapS)) {
                    Text(pageMessage, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1)
                    Text(currentOrigin.ifBlank { state.page?.url.orEmpty() }.toHttpUrlOrNull()?.host.orEmpty(),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { context ->
                        try {
                            val owned = sessionFactory(context, state.scope) { pageMessage = "网页加载失败，请重试" }
                            owned.onDialog = { webDialog = it }
                            owned.onPageEvent = { event ->
                                onDiagnosticEvent(event)
                                when (event) {
                                    LoginPageEvent.Loading -> {
                                        visible = false
                                        pageFailed = false
                                        blockedResources = false
                                        pageMessage = "正在加载登录页…"
                                        timeout?.cancel()
                                        timeout = coroutineScope.launch { delay(30000); if (session === owned && !visible) pageMessage = "加载较慢，请检查网络后重载" }
                                    }
                                    LoginPageEvent.Visible -> {
                                        timeout?.cancel(); visible = !pageFailed
                                        if (!pageFailed) pageMessage = if (!blockedResources) "请在下方网页完成登录" else "部分资源不符合安全要求，已阻止加载"
                                    }
                                    is LoginPageEvent.Failed -> { timeout?.cancel(); pageFailed = true; visible = false; pageMessage = event.reason }
                                    is LoginPageEvent.Location -> currentOrigin = event.origin
                                    is LoginPageEvent.Notice -> pageMessage = event.message
                                    is LoginPageEvent.Blocked -> {
                                        blockedResources = true
                                        if (event.mainFrame) {
                                            timeout?.cancel(); pageFailed = true; visible = false
                                            pageMessage = "已阻止不安全的网页地址，请检查来源登录配置"
                                        } else pageMessage = "部分资源不符合安全要求，已阻止加载"

                                    }
                                }
                            }
                            session = owned
                            owned.container.apply {
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
                        if (session === owned) { timeout?.cancel(); session = null; visible = false; blockedResources = false; webDialog = null }
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
                                try { onSave(owned.capture()) }
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
                    if (state.pages.size > 1) SpinnerSettingRow(
                        title = "登录网页", selectedId = state.page?.url,
                        options = state.pages.mapIndexed { index, page -> PickerOption(page.url, if (page.home) "来源首页" else "登录入口 "+ (index + 1), page.url.toHttpUrlOrNull()?.let { it.host + it.encodedPath }) },
                        onSelect = { onSelectPage(it.id) }, position = SettingGroupPosition.Middle,
                    )
                    SettingBlock {
                        Text(state.message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        state.scope?.originUrls?.forEach { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        PrimaryButton(if (state.restored == null) "登录" else "重新登录",
                            onClick = { if (state.form != null) showForm = true else { blockedResources = false; pageMessage = "正在打开登录页…"; onStart() } },
                            enabled = state.loaded && (state.form != null || state.page != null && supported) && !checking && !state.busy, modifier = Modifier.fillMaxWidth())
                    }
                }
                SettingFootnote(when {
                    checking -> "正在检查网页登录环境…"
                    !supported -> "请更新系统 WebView 后重试"
                    state.form != null -> "按来源定义的表单和按钮登录，网页保存后继续执行登录脚本。"
                    state.formError -> "该来源的自定义表单类型暂不支持，当前只提供可识别的网页登录。"
                    state.page?.home == true -> "未声明登录地址，仅尝试打开来源首页。"
                    state.page?.upgraded == true -> "旧地址仅尝试 HTTPS，不使用明文登录。"
                    state.page?.partial == true -> "仅打开规则中明确声明的网页，不执行表单按钮脚本。"
                    else -> "账号密码在网站页面输入，完成登录后保存会话。"
                })
                SectionHeader("会话管理")
                SettingGroup {
                    SettingRow("已保存 Cookie", value = (state.restored?.cookies?.size ?: 0).toString(),
                        onClick = if (state.loaded && state.scope != null && !state.busy) { { showCookies = true; onRefreshCookies() } } else null,
                        position = SettingGroupPosition.Alone)
                }
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
    webDialog?.let { request ->
        AppAlertDialog(title = "网页提示", text = request.origin + "\n\n" + request.message,
            confirmText = "确定", dismissText = if (request.confirmation) "取消" else null,
            onDismissRequest = { request.respond(false); webDialog = null },
            onConfirm = { request.respond(true); webDialog = null }, closeOnConfirm = true)
    }
    if (showHelp) AppAlertDialog(
        title = "网页登录", onDismissRequest = { showHelp = false }, confirmText = "知道了",
        onConfirm = { showHelp = false }, closeOnConfirm = true, dismissText = null,
        text = "不保存密码，只保存可恢复的来源 Cookie。网页及公开 HTTPS 跳转会自动加载，当前站点显示在网页上方。私网、无效证书和第三方 Cookie 仍受限制；网页登录的访问范围不会扩大阅读请求的认证范围。保存后恢复所选入口和 Cookie。",
    )
}
