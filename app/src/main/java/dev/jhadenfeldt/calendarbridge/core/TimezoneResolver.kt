package dev.jhadenfeldt.calendarbridge.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset

sealed interface Resolution {
    data class Zone(val zoneId: ZoneId, val via: String) : Resolution
    data class FixedOffset(val offset: ZoneOffset) : Resolution
    data object Floating : Resolution
}

/**
 * Turns a TZID from a feed into something Android's calendar understands.
 * Order: IANA id → Windows name (ICU) → Outlook-decorated Windows name →
 * an IANA zone whose rules match the feed's VTIMEZONE → fixed offset.
 * One instance per sync; results are cached.
 */
class TimezoneResolver(
    private val windows: WindowsZoneLookup,
    val deviceZone: ZoneId,
    private val currentYear: Int = Year.now().value,
) {
    private val cache = HashMap<Pair<String?, VTimezoneRules?>, Resolution>()

    fun resolve(tzid: String?, vtimezone: VTimezoneRules?): Resolution =
        cache.getOrPut(tzid to vtimezone) { compute(tzid, vtimezone) }

    private fun compute(tzid: String?, vtimezone: VTimezoneRules?): Resolution {
        val id = tzid?.trim()?.trim('"')?.trim()
        if (!id.isNullOrEmpty()) {
            direct(id)?.let { return it }
            val undecorated = DECORATIONS.fold(id) { acc, prefix ->
                if (acc.startsWith(prefix, ignoreCase = true)) acc.substring(prefix.length) else acc
            }.trim()
            if (undecorated != id) direct(undecorated)?.let { return Resolution.Zone(it.zoneId, "decorated") }
        }
        if (vtimezone != null) {
            matchVTimezone(vtimezone)?.let { return Resolution.Zone(it, "vtimezone") }
            (vtimezone.standard ?: vtimezone.daylight)?.let { return Resolution.FixedOffset(it.offsetTo) }
        }
        return Resolution.Floating
    }

    private fun direct(id: String): Resolution.Zone? {
        iana(id)?.let { return Resolution.Zone(it, "iana") }
        val win = windows.ianaFor(id) ?: capitalized(id).takeIf { it != id }?.let(windows::ianaFor)
        return win?.let { iana(it) }?.let { Resolution.Zone(it, "windows") }
    }

    private fun iana(id: String): ZoneId? =
        if (id == "UTC" || '/' in id) runCatching { ZoneId.of(id) }.getOrNull() else null

    private fun capitalized(id: String) =
        id.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    private fun matchVTimezone(rules: VTimezoneRules): ZoneId? {
        if (rules.standard == null) return null
        val candidates = sequenceOf(deviceZone) + ZoneId.getAvailableZoneIds().asSequence()
            .filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
            .map { ZoneId.of(it) }
        return candidates.firstOrNull { matches(it, rules) }
    }

    private fun matches(zone: ZoneId, rules: VTimezoneRules): Boolean {
        val std = rules.standard ?: return false
        val dst = rules.daylight
        val z = zone.rules
        for (year in listOf(currentYear, currentYear + 1)) {
            if (dst == null) {
                for (m in listOf(1, 7)) {
                    val at = LocalDateTime.of(year, m, 15, 12, 0).toInstant(std.offsetTo)
                    if (z.getOffset(at) != std.offsetTo) return false
                }
                continue
            }
            for ((o, other) in listOf(std to dst, dst to std)) {
                val instant = o.localTransition(year).toInstant(other.offsetTo)
                if (z.getOffset(instant - TWO_HOURS) != other.offsetTo) return false
                if (z.getOffset(instant + TWO_HOURS) != o.offsetTo) return false
            }
        }
        return true
    }

    private companion object {
        val DECORATIONS = listOf("tzone://Microsoft/", "/Microsoft/")
        val TWO_HOURS: Duration = Duration.ofHours(2)
    }
}
