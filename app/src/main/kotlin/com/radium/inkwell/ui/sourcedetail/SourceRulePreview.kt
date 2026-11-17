package com.radium.inkwell.ui.sourcedetail

import android.content.ClipData
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import com.radium.inkwell.ui.components.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 进入详情时不排版规则；用户主动展开后按原有方式显示全文，复制不截断原文。 */
@Composable
internal fun SourceRulePreview(json: String) {
    var expanded by remember(json) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SecondaryButton(if (expanded) "收起规则原文" else "查看规则原文", onClick = { expanded = !expanded })
    if (!expanded) return
    AppTextButton("复制全文", onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("来源规则", json))) } })
    SelectionContainer {
        Text(json, style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
    }
}
