package com.radium.inkwell.ui.sourceauth

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.radium.inkwell.ui.components.*
import okhttp3.Cookie
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

/** 凭据只在本页内存显示，不用 rememberSaveable，不自动写剪贴板或输出日志。 */
@Composable
internal fun SourceCookieScreen(
    state: SourceLoginState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: (Cookie, String?) -> Unit,
) {
    val scroll = rememberAppTopBarScroll()
    val color = settingsPageColor()
    var revealed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editing by remember { mutableStateOf<Cookie?>(null) }
    var deleting by remember { mutableStateOf<Cookie?>(null) }
    var value by remember { mutableStateOf("") }
    var showValue by remember { mutableStateOf(false) }
    val cookies = state.restored?.cookies.orEmpty()
    AppScaffold(modifier = Modifier.topBarScroll(scroll), containerColor = color, topBar = {
        AppTopBar("已保存 Cookie", scroll, onBack = onBack, containerColor = color,
            actions = { AppTextButton("刷新", enabled = !state.busy, onClick = onRefresh) })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).pageScroll()) {
            item {
                SettingFootnote(state.message)
                SettingFootnote("修改值或删除后立即在本机加密保存。域名、路径和有效期保持原属性；修改不代表网站已确认登录。")
            }
            if (cookies.isEmpty()) item { SettingFootnote(if (state.busy) "正在读取…" else "暂无已保存的 Cookie") }
            items(cookies, key = { cookieKey(it) }) { cookie ->
                val key = cookieKey(cookie)
                SettingGroup {
                    SettingBlock {
                        Text(cookie.name, style = MiuixTheme.textStyles.body1)
                        Text(cookie.domain + cookie.path, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        Text(cookieAttributes(cookie), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (key in revealed) SelectionContainer {
                            Text(cookie.value.ifEmpty { "（空值）" }, style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                        } else Text("••••••", style = MiuixTheme.textStyles.footnote1)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.gapS), verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
                            AppTextButton(if (key in revealed) "隐藏值" else "显示值", enabled = !state.busy,
                                onClick = { revealed = if (key in revealed) revealed - key else revealed + key })
                            AppTextButton("编辑值", enabled = !state.busy, onClick = { editing = cookie; value = cookie.value; showValue = false })
                            AppTextButton("删除", enabled = !state.busy, onClick = { deleting = cookie })
                        }
                    }
                }
            }
        }
    }
    editing?.let { cookie ->
        AppAlertDialog(title = "编辑 Cookie 值", confirmText = "保存", closeOnConfirm = true,
            onDismissRequest = { editing = null; value = "" },
            onConfirm = { onEdit(cookie, value); editing = null; value = "" },
            content = {
                Text(cookie.name + "\n" + cookie.domain + cookie.path, style = MiuixTheme.textStyles.footnote1)
                AppFormField(value, { value = it }, label = "Cookie 值",
                    visualTransformation = if (showValue) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                AppTextButton(if (showValue) "隐藏输入" else "显示输入", onClick = { showValue = !showValue })
                Text("仅修改值，请勿粘贴整段 Cookie 请求头。域名、路径、安全属性及到期时间不变。", style = MiuixTheme.textStyles.footnote1)
            })
    }
    deleting?.let { cookie ->
        AppAlertDialog(title = "删除此 Cookie？", text = cookie.name + "\n删除后可能需要重新登录。",
            confirmText = "删除", closeOnConfirm = true, onDismissRequest = { deleting = null },
            onConfirm = { onEdit(cookie, null); deleting = null; revealed = revealed - cookieKey(cookie) })
    }
}

private fun cookieKey(cookie: Cookie) = cookie.name + "\u0000" + cookie.domain + "\u0000" + cookie.path

private fun cookieAttributes(cookie: Cookie): String = buildList {
    if (cookie.secure) add("HTTPS")
    if (cookie.httpOnly) add("HttpOnly")
    if (cookie.hostOnly) add("仅当前主机")
    add(if (cookie.persistent) "到期：" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cookie.expiresAt)) else "会话结束时过期")
}.joinToString(" · ")
