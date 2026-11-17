package com.radium.inkwell.data.source

import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.source.RemoteChapter
import com.radium.inkwell.core.source.RemoteChapterContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class SourceContentProbeTest {
    private val toc = (0..3).map { RemoteChapter(it, "fixture", "/" + it) }
    @Test fun 图片封面不当作正文且中间章可作为有效样本() = runBlocking<Unit> {
        val calls = mutableListOf<Int>()
        val result = probeSourceContent(toc) { chapter ->
            calls += chapter.index
            RemoteChapterContent(if (chapter.index == 0) listOf(ContentElement.Image("fixture")) else listOf(ContentElement.Paragraph("valid")))
        }
        assertEquals(listOf(0, 2), calls)
        assertEquals(5, result.chars)
        assertEquals(2, result.chapter.index)
    }
    @Test fun 非空短章节通过但纯空白不通过() = runBlocking<Unit> {
        assertEquals(1, probeSourceContent(toc.take(1)) { RemoteChapterContent(listOf(ContentElement.Paragraph("  文  "))) }.chars)
        assertFailsWith<IllegalStateException> { probeSourceContent(toc) { RemoteChapterContent(listOf(ContentElement.Paragraph("  "))) } }
    }
    @Test fun 取消不会继续试下一章() = runBlocking<Unit> {
        var calls = 0
        assertFailsWith<CancellationException> { probeSourceContent(toc) { calls++; throw CancellationException() } }
        assertEquals(1, calls)
    }
    @Test fun 首章足够长不额外取中间章() = runBlocking<Unit> {
        var calls = 0
        probeSourceContent(toc) { calls++; RemoteChapterContent(listOf(ContentElement.Paragraph("文".repeat(100)))) }
        assertEquals(1, calls)
    }
}
