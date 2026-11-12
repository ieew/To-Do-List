package com.todo.android.core.designsystem

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** UI 侧时间格式化；领域层不读时钟、不做时区转换。 */
object TimeText {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun now(): Long = System.currentTimeMillis()

    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { Instant.parse(raw) }
            .getOrNull()
    }

    fun isoNow(nowMs: Long = now()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun isoAfterDays(days: Long, nowMs: Long = now()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).plusDays(days)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun isoTodayAt(hour: Int, minute: Int, nowMs: Long = now()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun isoTomorrowAt(hour: Int, minute: Int, nowMs: Long = now()): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            .plusDays(1)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun localDate(raw: String?): LocalDate? = parse(raw)?.atZone(zone)?.toLocalDate()

    fun dateTimeLabel(raw: String?): String? {
        val instant = parse(raw) ?: return null
        return instant.atZone(zone).format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault()))
    }

    fun dueLabel(raw: String?, nowMs: Long = now()): String? {
        val instant = parse(raw) ?: return null
        val target = instant.atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = target.format(hourMinute)
        return when (target.toLocalDate()) {
            today -> "今天 $time"
            today.plusDays(1) -> "明天 $time"
            today.minusDays(1) -> "昨天 $time"
            else -> target.format(DateTimeFormatter.ofPattern("M月d日", Locale.getDefault()))
        }
    }

    fun isOverdue(raw: String?, nowMs: Long = now()): Boolean {
        val instant = parse(raw) ?: return false
        return instant.toEpochMilli() < nowMs
    }

    fun completedLabel(ms: Long?): String? =
        ms?.let { Instant.ofEpochMilli(it).atZone(zone).format(hourMinute) }

    fun greeting(nowMs: Long = now()): String {
        val hour = Instant.ofEpochMilli(nowMs).atZone(zone).hour
        return when (hour) {
            in 5..10 -> "早上好，今天也要高效呀"
            in 11..13 -> "中午好，先吃个饭再继续"
            in 14..18 -> "下午好，保持节奏"
            else -> "晚上好，收尾今天的待办"
        }
    }

    fun dateLine(nowMs: Long = now()): String =
        Instant.ofEpochMilli(nowMs).atZone(zone)
            .format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE))

    fun monthTitle(year: Int, month: Int): Pair<String, String> =
        "${month}月" to year.toString()

    fun weekdayShort(date: LocalDate): String {
        val names = listOf("一", "二", "三", "四", "五", "六", "日")
        return names[date.dayOfWeek.value - 1]
    }

    fun dayTitle(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("M月d日", Locale.getDefault())) + " · 周" + weekdayShort(date)

    fun historyTime(atMs: Long, nowMs: Long = now()): String {
        val diffMinutes = (nowMs - atMs).coerceAtLeast(0) / 60_000
        return when {
            diffMinutes < 1 -> "刚刚"
            diffMinutes < 60 -> "$diffMinutes 分钟前"
            else -> Instant.ofEpochMilli(atMs).atZone(zone)
                .format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault()))
        }
    }

    fun relativeSyncLabel(lastSyncedAt: Long?, nowMs: Long = now()): String {
        if (lastSyncedAt == null) return "尚未同步"
        val minutes = (nowMs - lastSyncedAt).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "刚刚同步"
            minutes < 60 -> "$minutes 分钟前同步"
            else -> "${minutes / 60} 小时前同步"
        }
    }
}
