package com.iltan.settle.domain.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Items taken on one calendar day, newest first. */
data class DaySection<T>(val date: LocalDate, val items: List<T>)

/**
 * Splits a newest-first list into calendar days in [zone]. Order is kept as given; a day that
 * appears twice (because the list isn't sorted) gets two sections rather than reordering items.
 */
fun <T> groupByDay(items: List<T>, zone: ZoneId, takenAtMillis: (T) -> Long): List<DaySection<T>> {
    val sections = mutableListOf<DaySection<T>>()
    var currentDate: LocalDate? = null
    var current = mutableListOf<T>()
    for (item in items) {
        val date = Instant.ofEpochMilli(takenAtMillis(item)).atZone(zone).toLocalDate()
        if (date != currentDate) {
            currentDate?.let { sections += DaySection(it, current) }
            currentDate = date
            current = mutableListOf()
        }
        current += item
    }
    currentDate?.let { sections += DaySection(it, current) }
    return sections
}
