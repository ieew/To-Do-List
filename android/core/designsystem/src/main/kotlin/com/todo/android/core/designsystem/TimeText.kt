package com.todo.android.core.designsystem

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

    fun localDate(raw: String?): LocalDate? = parse(raw)?.atZone(zone)?.toLocalDate()

    /** 解析成设备时区的本地日期时间，用于时间选择器回填。 */
    fun localDateTime(raw: String?): LocalDateTime? = parse(raw)?.atZone(zone)?.toLocalDateTime()

    /** 把任意「日期 + 时 + 分」合成为带时区偏移的 ISO 字符串（秒/纳秒归零）。 */
    fun isoAt(date: LocalDate, hour: Int, minute: Int): String =
        date.atTime(hour, minute)
            .atZone(zone)
            .toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun clock(hour: Int, minute: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

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

    /**
     * 详情页字段完整文案：与 [dueLabel] 一致，但非近三天的日期也带上 `HH:mm`，
     * 让「精确到分钟」的设置结果可见。
     */
    fun fullLabel(raw: String?, nowMs: Long = now()): String? {
        val instant = parse(raw) ?: return null
        val target = instant.atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = target.format(hourMinute)
        return when (target.toLocalDate()) {
            today -> "今天 $time"
            today.plusDays(1) -> "明天 $time"
            today.minusDays(1) -> "昨天 $time"
            else -> target.format(DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.getDefault()))
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
