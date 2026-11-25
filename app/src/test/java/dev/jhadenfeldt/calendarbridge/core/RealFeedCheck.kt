package dev.jhadenfeldt.calendarbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/**
 * Runs the parser over a real feed saved outside the repo:
 *   CB_FEED_FILE=/path/to/calendar.ics ./gradlew :app:testDebugUnitTest --tests '*RealFeedCheck'
 * Skipped when the variable is unset. Titles are hashed so private data never lands in test output.
 */
class RealFeedCheck {
    @Test fun parsesRealFeedWithoutWarnings() {
        val path = System.getenv("CB_FEED_FILE")
        assumeTrue("CB_FEED_FILE not set", path != null)
        val text = File(path!!).readText()
        val result = IcsParser(TimezoneResolver(Icu4jWindowsZoneLookup, ZoneId.systemDefault())).parse(text)

        val vevents = Regex("^BEGIN:VEVENT", RegexOption.MULTILINE).findAll(text).count()
        println("VEVENTs in feed: $vevents, rows: ${result.events.size}")
        println("TZID mappings: ${result.tzidMappings}")
        println("Warnings: ${result.warnings}")
        result.events.groupingBy { it.eventTimezone }.eachCount().forEach { (z, n) -> println("  $z: $n") }
        result.events.filter { it.rrule != null }.take(10).forEach { e ->
            val local = Instant.ofEpochMilli(e.dtStart).atZone(ZoneId.of(e.eventTimezone))
            println("  ${hash(e.title)} | $local | ${e.rrule}")
        }
        assertEquals(emptyList<String>(), result.warnings)
        assertEquals(vevents, result.events.size)
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
}
