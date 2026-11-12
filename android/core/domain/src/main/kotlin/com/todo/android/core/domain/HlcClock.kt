package com.todo.android.core.domain

/**
 * HLC 发号器：同一事务内为多条 op 逐条推进逻辑时钟。
 * `nowMs` 由调用方传入（数据层读取系统时间），领域层不读物理时钟。
 */
class HlcClock(
    initial: Hlc,
    private val nowMs: Long,
) {
    private var current: Hlc = initial

    fun next(): Hlc {
        current = Hlc.next(current, nowMs, current.nodeId)
        return current
    }

    fun last(): Hlc = current
}
