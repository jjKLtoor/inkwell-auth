package com.radium.inkwell.ui.sourceauth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.radium.inkwell.ui.components.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 来源只提供控件语义；外观完全使用应用组件，不执行来源自带布局/主题代码。 */
@Composable
internal fun SourceLoginFormScreen(
    state: SourceLoginState,
    values: Map<String, String>,
    onValue: (String, String) -> Unit,
    onAction: (Int, Map<String, String>) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return
    val scroll = rememberAppTopBarScroll()
    AppScaffold(topBar = { AppTopBar("来源登录", scroll, onBack = onBack, containerColor = settingsPageColor()) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())) {
            SectionHeader(state.name)
            SettingGroup {
                SettingBlock {
                    Text(state.message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (state.sourceFeedback.isNotEmpty()) Text("来源反馈：" + state.sourceFeedback, style = MiuixTheme.textStyles.footnote1)
                }
            }
            SettingGroup {
                form.controls.forEachIndexed { index, control ->
                    SettingBlock {
                        when (control.type) {
                            "text" -> Text(control.name, style = MiuixTheme.textStyles.body1)
                            "edit", "password" -> AppFormField(
                                value = values[control.name].orEmpty(), onValueChange = { onValue(control.name, it) },
                                label = control.name, enabled = !state.busy,
                                visualTransformation = if (control.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
                            )
                            "button" -> SecondaryButton(control.name, modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy && !control.action.isNullOrBlank(), onClick = { onAction(index, values.toMap()) })
                        }
                    }
                }
                if (form.hasSubmit) SettingBlock {
                    PrimaryButton("按来源规则提交", enabled = !state.busy, loading = state.busy,
                        modifier = Modifier.fillMaxWidth(), onClick = { onAction(-1, values.toMap()) })
                }
            }
            if (state.busy) SettingGroup {
                SettingBlock { SecondaryButton("取消操作", modifier = Modifier.fillMaxWidth(), onClick = onCancel) }
            }
            SettingFootnote("按钮按来源规则执行；网页登录保存后会继续同一次脚本。表单内容仅在内存使用，不保存明文密码。保存认证材料不等于已确认登录。")
        }
    }
}
