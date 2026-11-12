package com.todo.android.core.data.time

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 数据层时间工具：只有这里允许读系统时钟与做时区转换。 */
object IsoTime {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun nowMs(): Long = System.currentTimeMillis()

    fun toIso(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun nowIso(nowMs: Long = nowMs()): String = toIso(nowMs)

    fun afterDaysIso(days: Long, nowMs: Long = nowMs()): String = toIso(nowMs + days * 86_400_000L)

    fun parseMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(raw).toEpochMilli() }
            .getOrNull()
    }
}
