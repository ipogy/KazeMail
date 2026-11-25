package com.kazemail.app.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object DateFormatters {
    private val monthDayTimeThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
    }

    private val fullDateTimeThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    }

    private val fullDateTimeSecondsThreadLocal = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
    }

    fun formatMonthDayTime(epochMs: Long): String =
        if (epochMs > 0L) monthDayTimeThreadLocal.get()!!.format(Date(epochMs)) else "--"

    fun formatDateTime(epochMs: Long): String =
        if (epochMs > 0L) fullDateTimeThreadLocal.get()!!.format(Date(epochMs)) else "--"

    fun formatDateTimeSeconds(epochMs: Long): String =
        if (epochMs > 0L) fullDateTimeSecondsThreadLocal.get()!!.format(Date(epochMs)) else "--"
}
