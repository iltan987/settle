package com.iltan.settle.domain.timeline

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

class DaySectionsTest {

    private val istanbul = ZoneId.of("Europe/Istanbul")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    private fun sections(zone: ZoneId, vararg times: Long) =
        groupByDay(times.toList(), zone) { it }.map { it.date to it.items.size }

    @Test
    fun `items on either side of local midnight land on different days`() {
        val result =
            sections(
                istanbul,
                at(istanbul, 2026, 10, 31, 0, 1),
                at(istanbul, 2026, 10, 30, 23, 59),
                at(istanbul, 2026, 10, 30, 8),
            )

        result shouldBe listOf(LocalDate.of(2026, 10, 31) to 1, LocalDate.of(2026, 10, 30) to 2)
    }

    @Test
    fun `day boundaries follow the zone, not UTC`() {
        // 22:30 UTC on 30 October is already 31 October in Istanbul (UTC+3).
        val time = at(ZoneId.of("UTC"), 2026, 10, 30, 22, 30)

        sections(istanbul, time).single().first shouldBe LocalDate.of(2026, 10, 31)
        sections(ZoneId.of("UTC"), time).single().first shouldBe LocalDate.of(2026, 10, 30)
    }

    @Test
    fun `days stay whole across a daylight saving change`() {
        // Berlin leaves summer time at 03:00 on 25 October 2026; that day has 25 hours.
        val result =
            sections(
                berlin,
                at(berlin, 2026, 10, 25, 23, 30),
                at(berlin, 2026, 10, 25, 2, 30),
                at(berlin, 2026, 10, 25, 0, 30),
                at(berlin, 2026, 10, 24, 23, 30),
            )

        result shouldBe listOf(LocalDate.of(2026, 10, 25) to 3, LocalDate.of(2026, 10, 24) to 1)
    }

    @Test
    fun `empty list has no sections`() {
        groupByDay(emptyList<Long>(), istanbul) { it }.shouldBeEmpty()
    }
}
