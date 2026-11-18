package com.radium.inkwell.data.source

import com.radium.inkwell.core.source.BookSourceRule
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceTestKeywordTest {
    private fun source(word: String = "样本作品") = BookSourceRule.fromJson("""{"bookSourceUrl":"https://example.com/","searchUrl":"/search?q={{key}}","ruleSearch":{"checkKeyWord":"$word","bookList":"li"}}""")

    @Test fun 校验词解析及重新编码保留且自动选择声明词() {
        val rule = source()
        assertEquals("样本作品", rule.ruleSearch?.checkKeyWord)
        assertEquals("样本作品", sourceTestKeyword(rule))
        assertEquals("样本作品", sourceTestKeyword(BookSourceRule.fromJson(rule.toJson())))
    }

    @Test fun 手动指定词优先即使恰好等于旧默认值() {
        assertEquals("剑", sourceTestKeyword(source(), " 剑 "))
        assertEquals("自选作品", sourceTestKeyword(source(), "自选作品"))
    }

    @Test fun 无声明或空声明才退回通用样本() {
        assertEquals(DEFAULT_SOURCE_TEST_KEYWORD, sourceTestKeyword(source("  ")))
        assertEquals(DEFAULT_SOURCE_TEST_KEYWORD, sourceTestKeyword(source().copy(ruleSearch = null)))
    }
}
