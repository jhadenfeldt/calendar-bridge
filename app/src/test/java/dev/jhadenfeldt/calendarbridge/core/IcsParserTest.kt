package dev.jhadenfeldt.calendarbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class IcsParserTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val text = javaClass.getResource("/outlook-sample.ics")!!.readText()
    private val result = IcsParser(TimezoneResolver(Icu4jWindowsZoneLookup, ZoneId.of("Europe/Berlin"))).parse(text)
    private fun row(syncId: String) = result.events.single { it.syncId == syncId }
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun calendarName() = assertEquals("Work", result.calendarName)

    @Test fun weeklyKeepsPacificWallClockAcrossDstEnd() {
        val w = row("weekly")
        assertEquals(ms("2026-10-19T09:30-07:00"), w.dtStart)
        assertEquals("America/Los_Angeles", w.eventTimezone)
        assertNull(w.dtEnd)
        assertEquals("P1800S", w.duration)
        assertEquals("FREQ=WEEKLY;UNTIL=20271115T173000Z;INTERVAL=1;BYDAY=MO", w.rrule)
        assertEquals("20261026T163000Z", w.exdate)
        assertEquals("Agenda, notes\nLine 2", w.description)
        assertEquals("Room 1", w.location)
        // Android expands the series in the stored zone, so 09:30 stays 09:30 after DST ends on 2026-11-01.
        val start = java.time.Instant.ofEpochMilli(w.dtStart).atZone(ZoneId.of(w.eventTimezone))
        assertEquals(start.toLocalTime(), start.plusWeeks(3).toLocalTime())
        assertEquals(ms("2026-11-09T09:30-08:00"), start.plusWeeks(3).toInstant().toEpochMilli())
    }

    @Test fun exceptionRow() {
        val instance = ms("2026-11-02T09:30-08:00")
        val ex = row("weekly#$instance")
        assertEquals("weekly", ex.originalSyncId)
        assertEquals(instance, ex.originalInstanceTime)
        assertEquals(false, ex.originalAllDay)
        assertEquals(ms("2026-11-02T11:00-08:00"), ex.dtStart)
        assertEquals(ms("2026-11-02T11:30-08:00"), ex.dtEnd)
        assertNull(ex.rrule)
        assertEquals("Weekly sync (moved)", ex.title)
    }

    @Test fun cancelledException() =
        assertEquals(Cal.STATUS_CANCELED, row("weekly#${ms("2026-11-09T09:30-08:00")}").status)

    @Test fun easternSingle() {
        val e = row("eastern-single")
        assertEquals("America/New_York", e.eventTimezone)
        assertEquals(ms("2026-12-03T14:00-05:00"), e.dtStart)
        assertEquals(ms("2026-12-03T15:00-05:00"), e.dtEnd)
        assertNull(e.duration)
        assertEquals(Cal.AVAIL_TENTATIVE, e.availability)
        assertEquals(Cal.ACCESS_PRIVATE, e.accessLevel)
        assertEquals(listOf(15), e.reminderMinutes)
    }

    @Test fun allDay() {
        val a = row("allday")
        assertTrue(a.allDay)
        assertEquals(ms("2026-12-24T00:00Z"), a.dtStart)
        assertEquals(ms("2026-12-25T00:00Z"), a.dtEnd)
        assertEquals("UTC", a.eventTimezone)
        assertEquals(Cal.AVAIL_FREE, a.availability)
    }

    @Test fun utc() {
        val u = row("utc")
        assertEquals("UTC", u.eventTimezone)
        assertEquals(ms("2026-12-01T12:00Z"), u.dtStart)
        assertEquals(Cal.AVAIL_BUSY, u.availability)
    }

    @Test fun mixedTz() {
        val m = row("mixed-tz")
        assertEquals("America/Los_Angeles", m.eventTimezone)
        assertEquals("America/New_York", m.eventEndTimezone)
        assertEquals(ms("2026-12-10T12:00-05:00"), m.dtEnd)
    }

    @Test fun orphanException() {
        val o = row("orphan")
        assertNull(o.originalSyncId)
        assertNull(o.originalInstanceTime)
        assertEquals(ms("2026-12-04T10:00-08:00"), o.dtStart)
    }

    @Test fun duplicateUid() {
        assertEquals("Dup v2", row("dup").title)
        assertEquals(1, result.warnings.count { "dup" in it })
    }

    @Test fun tzidMappings() = assertEquals(
        mapOf("Pacific Standard Time" to "America/Los_Angeles", "Eastern Standard Time" to "America/New_York"),
        result.tzidMappings,
    )

    @Test fun eventCount() = assertEquals(9, result.events.size)

    @Test fun unknownTzidWithoutVtimezoneWarns() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:x
            DTSTART;TZID=Nowhere Standard Time:20261201T100000
            DTEND;TZID=Nowhere Standard Time:20261201T110000
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
        val r = IcsParser(TimezoneResolver(Icu4jWindowsZoneLookup, ZoneId.of("Europe/Berlin"))).parse(ics)
        assertEquals("Europe/Berlin", r.events.single().eventTimezone)
        assertEquals(mapOf("Nowhere Standard Time" to "Europe/Berlin"), r.tzidMappings)
        assertEquals(1, r.warnings.count { "Nowhere Standard Time" in it })
    }

    @Test(expected = IcsParseException::class)
    fun notIcsThrows() {
        IcsParser(TimezoneResolver(Icu4jWindowsZoneLookup, la)).parse("<html>login</html>")
    }
}
