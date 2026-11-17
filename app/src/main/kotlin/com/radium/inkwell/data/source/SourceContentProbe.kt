package com.radium.inkwell.data.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.RemoteChapterContent
import kotlinx.coroutines.CancellationException

internal const val DEFAULT_SOURCE_TEST_KEYWORD = "剑"
internal const val SHORT_SOURCE_CONTENT_CHARS = 100

internal data class SourceContentProbe(val chapter: RemoteChapter, val content: RemoteChapterContent, val chars: Int)

/** 两个来源测试入口共用正文判据：首章可能只有封面/公告，再探一章中间章节，非空文字才能通过。 */
internal suspend fun probeSourceContent(
    toc: List<RemoteChapter>,
    fetch: suspend (RemoteChapter) -> RemoteChapterContent,
): SourceContentProbe {
    require(toc.isNotEmpty()) { "目录为空" }
    val probes = listOfNotNull(toc.first(), toc.getOrNull(toc.size / 2).takeIf { toc.size > 1 })
    var best: SourceContentProbe? = null
    var lastError: Throwable? = null
    for (chapter in probes) {
        try {
            val content = fetch(chapter)
            val chars = content.elements.filterIsInstance<ContentElement.Paragraph>().sumOf { it.text.trim().length }
            if (chars > (best?.chars ?: 0)) best = SourceContentProbe(chapter, content, chars)
            if (chars >= SHORT_SOURCE_CONTENT_CHARS) break
        } catch (error: Exception) {
            // 用户取消和总超时不能降级成正文为空，也不能继续发下一章请求。
            if (error is CancellationException) throw error
            lastError = error
        }
    }
    return best ?: throw (lastError ?: IllegalStateException("正文为空"))
}
