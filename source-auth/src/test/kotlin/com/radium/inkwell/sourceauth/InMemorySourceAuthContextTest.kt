package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceAuthSnapshot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class InMemorySourceAuthContextTest {
    @Test
    fun `同域来源独立存取且换域后不返回旧凭据`() {
        val context = InMemorySourceAuthContext()
        val first = assertNotNull(SourceAuthScope.create("source#one", "https://example.com/"))
        val second = assertNotNull(SourceAuthScope.create("source#two", "https://example.com/"))
        val moved = assertNotNull(SourceAuthScope.create("source#one", "https://other.test/"))
        val snapshot = SourceAuthSnapshot(loginInfo = "fixture-session")
        context.replace(first, snapshot)
        assertSame(snapshot, context.snapshot(first, "https://example.com/chapter"))
        assertNull(context.snapshot(second, "https://example.com/chapter"))
        assertNull(context.snapshot(moved, "https://other.test/"))
        assertNull(context.snapshot(first, "https://other.test/"))
        assertNull(context.snapshot(first, "http://example.com/"))
    }

    @Test
    fun `撤销仅影响指定来源且新容器没有持久化会话`() {
        val context = InMemorySourceAuthContext()
        val first = assertNotNull(SourceAuthScope.create("one", "https://example.com/"))
        val second = assertNotNull(SourceAuthScope.create("two", "https://example.com/"))
        val snapshot = SourceAuthSnapshot()
        context.replace(first, snapshot)
        context.replace(second, snapshot)
        context.clear(first)
        context.clear(first)
        assertNull(context.snapshot(first, "https://example.com/"))
        assertSame(snapshot, context.snapshot(second, "https://example.com/"))
        assertNull(InMemorySourceAuthContext().snapshot(second, "https://example.com/"))
    }
}
