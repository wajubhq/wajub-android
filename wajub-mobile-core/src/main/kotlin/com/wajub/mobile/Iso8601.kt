package com.wajub.mobile

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ISO 8601 without java.time, which needs API 26 (minSdk is 24).
internal object Iso8601 {
    /** `2026-10-14T09:30:00Z`. */
    fun format(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))
}
