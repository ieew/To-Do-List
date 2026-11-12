package com.todo.android.core.domain

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {
    private val node = "node-a"

    @Test
    fun `next 在物理时间前进时重置 counter`() {
        val local = Hlc(physicalMs = 1000, counter = 7, nodeId = node)
        assertEquals(Hlc(2000, 0, node), Hlc.next(local, nowMs = 2000, nodeId = node))
    }

    @Test
    fun `next 在时钟回拨时自增 counter`() {
        val local = Hlc(physicalMs = 1000, counter = 7, nodeId = node)
        assertEquals(Hlc(1000, 8, node), Hlc.next(local, nowMs = 900, nodeId = node))
    }

    @Test
    fun `observe 采用远端更大的物理时间`() {
        val local = Hlc(1000, 5, node)
        val remote = Hlc(3000, 2, "node-b")
        assertEquals(Hlc(3000, 2, node), Hlc.observe(local, remote, node))
    }

    @Test
    fun `observe 同毫秒取更大 counter`() {
        val local = Hlc(1000, 5, node)
        val remote = Hlc(1000, 9, "node-b")
        assertEquals(Hlc(1000, 9, node), Hlc.observe(local, remote, node))
    }

    @Test
    fun `observe 不会回退`() {
        val local = Hlc(5000, 3, node)
        val remote = Hlc(1000, 99, "node-b")
        assertEquals(local, Hlc.observe(local, remote, node))
    }

    @Test
    fun `op 全序在 HLC 相同时用 opId 打破平局`() {
        val hlc = Hlc(1000, 0, node)
        val a = op("op-a", hlc, "title", JsonPrimitive("A"))
        val b = op("op-b", hlc, "title", JsonPrimitive("B"))
        assertTrue("opId 更大的 op 应排在后面", a < b)
        val state = MergeState.applyAll(listOf(b, a))
        assertEquals(JsonPrimitive("B"), state.raw(EntityType.TASK, "task-1", "title"))
    }

    private fun op(
        opId: String,
        hlc: Hlc,
        field: String,
        value: kotlinx.serialization.json.JsonElement,
    ): Op = Op(
        opId = opId,
        workspaceId = "ws-1",
        entityType = EntityType.TASK,
        entityId = "task-1",
        actorId = "u-1",
        deviceId = "dev-1",
        operation = OpOperation.SET,
        field = field,
        value = value,
        hlc = hlc,
        source = OpSource.HUMAN,
    )
}
