package com.androidharness.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class JstTimeUtilsTest {

    @Test
    fun toJstString_converts_epoch_millis_to_jtst_format() {
        // 0L = 1970-01-01 00:00:00 UTC = 1970-01-01 09:00:00 JST
        val result = JstTimeUtils.toJstString(0L)
        assertEquals("1970-01-01 09:00:00", result)
    }

    @Test
    fun toJst_returns_zoned_date_time_in_asia_tokyo() {
        val zdt = JstTimeUtils.toJst(0L)
        assertEquals("Asia/Tokyo", zdt.zone.id)
        assertEquals(9, zdt.hour) // 1970-01-01 09:00 JST
    }

    @Test
    fun formatDuration_handles_seconds() {
        assertEquals("30秒", JstTimeUtils.formatDuration(0, 30_000))
    }

    @Test
    fun formatDuration_handles_minutes() {
        assertEquals("5分", JstTimeUtils.formatDuration(0, 300_000))
    }

    @Test
    fun formatDuration_handles_hours_and_minutes() {
        assertEquals("2時間30分", JstTimeUtils.formatDuration(0, 9_000_000))
    }

    @Test
    fun formatDuration_handles_days() {
        // 108_000_000ms = 30h = 1日6時間、+10_800_000ms = 3h → 合計33h = 1日9時間
        assertEquals("1日9時間", JstTimeUtils.formatDuration(0, 108_000_000L + 10_800_000L))
    }

    @Test
    fun elapsedMillis_returns_difference() {
        assertEquals(5000L, JstTimeUtils.elapsedMillis(1000, 6000))
        assertEquals(-5000L, JstTimeUtils.elapsedMillis(6000, 1000))
    }

    @Test
    fun remainingUntil_returns_expired_when_deadline_passed() {
        assertEquals("期限切れ", JstTimeUtils.remainingUntil(JstTimeUtils.nowEpochMillis() - 1000))
    }

    @Test
    fun remainingUntil_returns_duration_when_deadline_future() {
        val future = JstTimeUtils.nowEpochMillis() + 300_000
        assertEquals("5分", JstTimeUtils.remainingUntil(future))
    }
}
