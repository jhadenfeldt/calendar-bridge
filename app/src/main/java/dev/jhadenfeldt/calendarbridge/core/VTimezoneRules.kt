package dev.jhadenfeldt.calendarbridge.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** One STANDARD or DAYLIGHT block of a VTIMEZONE, reduced to its yearly rule. */
data class Observance(
    val offsetTo: ZoneOffset,
    val month: Int,
    /** 1..5, or -1 for "last". */
    val weekOfMonth: Int,
    val dayOfWeek: DayOfWeek,
    val localTime: LocalTime,
) {
    fun localTransition(year: Int): LocalDateTime =
        LocalDate.of(year, month, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(weekOfMonth, dayOfWeek))
            .atTime(localTime)
}

/** A feed's own timezone definition: `standard == null` means no usable rule, `daylight == null` means no DST. */
data class VTimezoneRules(val tzid: String, val standard: Observance?, val daylight: Observance?)
