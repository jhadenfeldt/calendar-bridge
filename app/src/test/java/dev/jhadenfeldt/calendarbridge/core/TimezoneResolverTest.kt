package dev.jhadenfeldt.calendarbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class TimezoneResolverTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val la = ZoneId.of("America/Los_Angeles")
    private fun resolver(device: ZoneId = berlin) = TimezoneResolver(Icu4jWindowsZoneLookup, device)

    private val pacificRules = VTimezoneRules(
        tzid = "Custom Zone 1",
        standard = Observance(ZoneOffset.ofHours(-8), 11, 1, DayOfWeek.SUNDAY, LocalTime.of(2, 0)),
        daylight = Observance(ZoneOffset.ofHours(-7), 3, 2, DayOfWeek.SUNDAY, LocalTime.of(2, 0)),
    )

    @Test fun ianaPassesThrough() =
        assertEquals(Resolution.Zone(ZoneId.of("America/Chicago"), "iana"), resolver().resolve("America/Chicago", null))

    @Test fun windowsPacific() {
        val r = resolver()
        assertEquals(Resolution.Zone(la, "windows"), r.resolve("Pacific Standard Time", null))
        assertEquals(Resolution.Zone(ZoneId.of("America/New_York"), "windows"), r.resolve("Eastern Standard Time", null))
        assertEquals(Resolution.Zone(berlin, "windows"), r.resolve("W. Europe Standard Time", null))
    }

    @Test fun windowsIsTrimmedQuotedCaseInsensitive() =
        assertEquals(Resolution.Zone(la, "windows"), resolver().resolve("\"pacific standard time \"", null))

    @Test fun decoratedNames() {
        assertEquals(Resolution.Zone(la, "decorated"), resolver().resolve("tzone://Microsoft/Pacific Standard Time", null))
        assertEquals(Resolution.Zone(la, "decorated"), resolver().resolve("/Microsoft/Pacific Standard Time", null))
    }

    @Test fun vtimezoneMatch() {
        val res = resolver().resolve("Custom Zone 1", pacificRules)
        assertTrue(res is Resolution.Zone)
        res as Resolution.Zone
        assertEquals("vtimezone", res.via)
        val rules = res.zoneId.rules
        fun off(y: Int, m: Int, d: Int, h: Int = 12) = rules.getOffset(LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC))
        assertEquals(ZoneOffset.ofHours(-8), off(2027, 1, 15))
        assertEquals(ZoneOffset.ofHours(-7), off(2027, 7, 15))
        assertEquals(ZoneOffset.ofHours(-7), off(2026, 11, 1, 8))   // 01:00 PDT
        assertEquals(ZoneOffset.ofHours(-8), off(2026, 11, 1, 10))  // 02:00 PST
        assertEquals(ZoneOffset.ofHours(-8), off(2027, 3, 14, 9))
        assertEquals(ZoneOffset.ofHours(-7), off(2027, 3, 14, 11))
    }

    @Test fun vtimezonePrefersDeviceZone() =
        assertEquals(Resolution.Zone(la, "vtimezone"), resolver(la).resolve("Custom Zone 1", pacificRules))

    @Test fun noMatchFallsBackToFixedOffset() {
        val mars = VTimezoneRules(
            "Mars Time",
            Observance(ZoneOffset.ofHoursMinutes(5, 17), 1, 1, DayOfWeek.SUNDAY, LocalTime.MIDNIGHT),
            null,
        )
        assertEquals(Resolution.FixedOffset(ZoneOffset.ofHoursMinutes(5, 17)), resolver().resolve("Mars Time", mars))
    }

    @Test fun noTzid() = assertEquals(Resolution.Floating, resolver().resolve(null, null))
}
