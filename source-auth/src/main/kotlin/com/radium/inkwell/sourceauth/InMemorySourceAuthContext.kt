package com.radium.inkwell.sourceauth

import com.radium.inkwell.core.source.auth.SourceAuthContext
import com.radium.inkwell.core.source.auth.SourceAuthScope
import com.radium.inkwell.core.source.auth.SourceAuthSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * 阶段 A 的进程内会话容器；尚未接入引擎，也没有磁盘持久化。
 * 一次替换完整只读快照，避免脚本并发读到一半新一半旧的认证数据。
 */
class InMemorySourceAuthContext : SourceAuthContext {
    private val sessions = ConcurrentHashMap<SourceAuthScope, SourceAuthSnapshot>()

    override fun snapshot(scope: SourceAuthScope, requestUrl: String): SourceAuthSnapshot? =
        if (scope.permits(requestUrl)) sessions[scope] else null

    fun replace(scope: SourceAuthScope, snapshot: SourceAuthSnapshot) {
        sessions[scope] = snapshot
    }

    /** 只撤销指定来源与 origin 的会话；不触碰来源配置、书架、阅读记录或其它来源。 */
    fun clear(scope: SourceAuthScope) {
        sessions.remove(scope)
    }
}
