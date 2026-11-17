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

/** 大规则默认不排版，展开后一次只测量一小段；复制仍使用完整原文，避免破坏规则。 */
@Composable
internal fun SourceRulePreview(json: String) {
    var expanded by remember(json) { mutableStateOf(false) }
    var page by remember(json) { mutableIntStateOf(0) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    SecondaryButton(if (expanded) "收起规则原文" else "查看规则原文", onClick = { expanded = !expanded })
    if (!expanded) return
    val chunks = remember(json) {
        buildList {
            var start = 0
            while (start < json.length) {
                var end = (start + 4096).coerceAtMost(json.length)
                if (end < json.length && json[end - 1].isHighSurrogate() && json[end].isLowSurrogate()) end--
                add(json.substring(start, end))
                start = end
            }
            if (isEmpty()) add("")
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.gapS), verticalArrangement = Arrangement.spacedBy(Dimens.gapXS)) {
        AppTextButton("上一段", enabled = page > 0, onClick = { page-- })
        Text((page + 1).toString() + " / " + chunks.size, style = MiuixTheme.textStyles.footnote1)
        AppTextButton("下一段", enabled = page < chunks.lastIndex, onClick = { page++ })
        AppTextButton("复制全文", onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("来源规则", json))) } })
    }
    SelectionContainer {
        Text(chunks[page], style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
    }
}
