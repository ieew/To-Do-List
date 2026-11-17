package com.todo.android.core.designsystem

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间选择相关纯函数：任意日期 + 任意分钟必须原样往返。 */
class TimeTextTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun fixedNow(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun isoAt_roundTripsAnyDateAndMinute() {
        val date = LocalDate.of(2027, 2, 3)
        val iso = TimeText.isoAt(date, 13, 7)

        val parsed = TimeText.localDateTime(iso)
        assertEquals(date.atTime(13, 7), parsed)
    }

    @Test
    fun isoAt_keepsZeroSecondAndNano() {
        val iso = TimeText.isoAt(LocalDate.of(2027, 2, 3), 0, 5)

        val instant = TimeText.parse(iso)!!
        val local = instant.atZone(zone).toLocalTime()
        assertEquals(0, local.second)
        assertEquals(0, local.nano)
        assertEquals(5, local.minute)
    }

    @Test
    fun localDateTime_returnsNullForBlankOrBrokenInput() {
        assertNull(TimeText.localDateTime(null))
        assertNull(TimeText.localDateTime(""))
        assertNull(TimeText.localDateTime("not-a-time"))
    }

    @Test
    fun clock_padsSingleDigits() {
        assertEquals("09:05", TimeText.clock(9, 5))
        assertEquals("23:59", TimeText.clock(23, 59))
        assertEquals("00:00", TimeText.clock(0, 0))
    }

    @Test
    fun fullLabel_keepsMinuteForDistantDate() {
        val now = fixedNow(2026, 11, 15, 10, 0)
        val target = ZonedDateTime.of(2026, 12, 24, 16, 45, 0, 0, zone).toInstant().toEpochMilli()

        val label = TimeText.fullLabel(Instant.ofEpochMilli(target).toString(), now)!!

        assertTrue("应包含分钟：$label", label.endsWith("16:45"))
        assertTrue("应包含月日：$label", label.contains("12月24日"))
    }

    @Test
    fun fullLabel_usesRelativeWordsForNearbyDays() {
        val now = fixedNow(2026, 11, 15, 10, 0)

        assertEquals("今天 10:00", TimeText.fullLabel(TimeText.isoAfterDays(0, now), now))
        assertEquals("明天 10:00", TimeText.fullLabel(TimeText.isoAfterDays(1, now), now))
    }
}
