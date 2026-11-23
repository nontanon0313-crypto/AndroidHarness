package com.androidharness.app.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 日本標準時（Asia/Tokyo）での日時変換と経過時間計算ユーティリティ。 */
object JstTimeUtils {
    private val JST = ZoneId.of("Asia/Tokyo")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** UTC エポックミリ秒 → JST 文字列（yyyy-MM-dd HH:mm:ss）。 */
    fun toJstString(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(JST)
            .format(formatter)
    }

    /** UTC エポックミリ秒 → JST ZonedDateTime。 */
    fun toJst(epochMillis: Long): ZonedDateTime {
        return Instant.ofEpochMilli(epochMillis).atZone(JST)
    }

    /** 現在時刻を取得（UTC エポックミリ秒）。 */
    fun nowEpochMillis(): Long {
        return System.currentTimeMillis()
    }

    /** 2 つの時刻の経過時間を人間が読みやすい形式で返す。 */
    fun formatDuration(startMillis: Long, endMillis: Long): String {
        val seconds = (endMillis - startMillis) / 1000
        return when {
            seconds < 60 -> "${seconds}秒"
            seconds < 3600 -> "${seconds / 60}分"
            seconds < 86400 -> "${seconds / 3600}時間${(seconds % 3600) / 60}分"
            else -> "${seconds / 86400}日${(seconds % 86400) / 3600}時間"
        }
    }

    /** 指定時刻から現在までの経過時間。 */
    fun elapsedSince(pastMillis: Long): String {
        return formatDuration(pastMillis, nowEpochMillis())
    }

    /** 期限までの残り時間。期限が過ぎている場合は "期限切れ"。 */
    fun remainingUntil(deadlineMillis: Long): String {
        val now = nowEpochMillis()
        return if (deadlineMillis <= now) "期限切れ" else formatDuration(now, deadlineMillis)
    }

    /** 2 つの時刻の経過ミリ秒。 */
    fun elapsedMillis(startMillis: Long, endMillis: Long): Long {
        return endMillis - startMillis
    }
}
