package com.iltan.settle.ui

import android.content.res.Resources
import android.text.format.DateFormat
import com.iltan.settle.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Days as Tidepool names them in day headers and the viewer: Today, Yesterday, "Thursday, 29
 * October" ("29 Ekim Perşembe"), with the year only when it isn't this one.
 */
class DayTitles(private val resources: Resources, locale: Locale) {
    private val thisYear = pattern(locale, "EEEEdMMMM")
    private val otherYear = pattern(locale, "EEEEdMMMMyyyy")

    fun of(date: LocalDate, today: LocalDate): String =
        when {
            date == today -> resources.getString(R.string.day_today)
            date == today.minusDays(1) -> resources.getString(R.string.day_yesterday)
            date.year == today.year -> thisYear.format(date)
            else -> otherYear.format(date)
        }

    companion object {
        /** The locale's own order for the fields in [skeleton]. */
        fun pattern(locale: Locale, skeleton: String): DateTimeFormatter =
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton))
                .withLocale(locale)
    }
}
