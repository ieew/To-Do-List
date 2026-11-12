package com.todo.android.core.domain

/**
 * 混合逻辑时钟（HLC）。
 *
 * 物理分量只能来自调用方传入的 [nowMs] 或远端 HLC，领域层不读物理时钟。
 * 全序为 (physicalMs, counter, nodeId)；比较两个 Op 时再追加 opId 打破平局。
 */
data class Hlc(
    val physicalMs: Long,
    val counter: Long,
    val nodeId: String,
) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val byPhysical = physicalMs.compareTo(other.physicalMs)
        if (byPhysical != 0) return byPhysical
        val byCounter = counter.compareTo(other.counter)
        if (byCounter != 0) return byCounter
        return nodeId.compareTo(other.nodeId)
    }

    companion object {
        const val SERVER_NODE_ID: String = "srv"

        /** 本地发号：时间前进则重置 counter，否则在同一物理毫秒内自增。 */
        fun next(local: Hlc, nowMs: Long, nodeId: String): Hlc =
            if (nowMs > local.physicalMs) {
                Hlc(physicalMs = nowMs, counter = 0, nodeId = nodeId)
            } else {
                Hlc(physicalMs = local.physicalMs, counter = local.counter + 1, nodeId = nodeId)
            }

        /**
         * 吸收远端 HLC，只前进不回退。
         * 与 Windows `storage.rs::observe_hlc` 保持一致：物理时间更大时整体采用远端，
         * 相同物理毫秒取 counter 较大者，物理时间更小时本地不变。
         */
        fun observe(local: Hlc, remote: Hlc, nodeId: String): Hlc {
            if (remote.physicalMs > local.physicalMs) {
                return Hlc(physicalMs = remote.physicalMs, counter = remote.counter, nodeId = nodeId)
            }
            if (remote.physicalMs == local.physicalMs) {
                return Hlc(
                    physicalMs = local.physicalMs,
                    counter = maxOf(local.counter, remote.counter),
                    nodeId = nodeId,
                )
            }
            return local.copy(nodeId = nodeId)
        }
    }
}
