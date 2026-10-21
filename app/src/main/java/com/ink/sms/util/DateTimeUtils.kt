package com.ink.sms.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 短信时间戳格式化：
 * - 一年内（含当天）：`MM-dd HH:mm`
 * - 超过一年：`yyyy-MM-dd HH:mm`（补全年份）
 */
fun formatSmsTime(timestampMs: Long): String {
    val now = Calendar.getInstance()
    val oneYearAgo = now.clone() as Calendar
    oneYearAgo.add(Calendar.YEAR, -1)

    val pattern = if (timestampMs < oneYearAgo.timeInMillis) {
        "yyyy-MM-dd HH:mm"
    } else {
        "MM-dd HH:mm"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestampMs))
}
