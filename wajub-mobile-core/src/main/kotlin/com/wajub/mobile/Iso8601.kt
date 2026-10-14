package com.wajub.mobile

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ISO 8601 without java.time, which needs API 26 (minSdk is 24).
internal object Iso8601 {
    private val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")

    /** Epoch milliseconds of `2026-10-14T09:30:00+00:00`, or null. */
    fun parse(text: String): Long? {
        for (pattern in patterns) {
            try {
                return SimpleDateFormat(pattern, Locale.ROOT).parse(text.trim().replace("Z", "+00:00"))?.time
            } catch (_: ParseException) {
                // next pattern
            }
        }
        return null
    }

    /** `2026-10-14T09:30:00Z`. */
    fun format(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))
}
