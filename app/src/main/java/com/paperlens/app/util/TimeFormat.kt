package com.paperlens.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展示用时间格式化（本地时区）。 */
object TimeFormat {

    private val zone: ZoneId = ZoneId.systemDefault()

    private val hmFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val mdFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val fullFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** 列表 meta 行：今天显示时分，今年显示月日，往年显示完整日期。 */
    fun friendly(epochMillis: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
        val today = LocalDate.now(zone)
        return when {
            dt.toLocalDate() == today -> dt.format(hmFormatter)
            dt.year == today.year -> dt.format(mdFormatter)
            else -> dt.format(fullFormatter)
        }
    }

    /** AI 生成时间：精确到分钟。 */
    fun withTime(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
}
