package dev.jhadenfeldt.calendarbridge.core

import biweekly.Biweekly
import biweekly.ICalVersion
import biweekly.ICalendar
import biweekly.component.Observance as BwObservance
import biweekly.component.VEvent
import biweekly.io.TimezoneInfo
import biweekly.io.WriteContext
import biweekly.io.scribe.property.RecurrenceRuleScribe
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Parses an .ics document into calendar rows with every TZID resolved to something Android understands. */
class IcsParser(private val resolver: TimezoneResolver) {

    fun parse(text: String): ParseResult {
        if (!text.contains("BEGIN:VCALENDAR")) throw IcsParseException("Not an iCalendar document")
        val calendars = runCatching { Biweekly.parse(text).all() }
            .getOrElse { throw IcsParseException("Unreadable iCalendar document: ${it.message}") }
        if (calendars.isEmpty()) throw IcsParseException("No calendar in document")
        return Session(calendars).run()
    }

    private inner class Session(private val calendars: List<ICalendar>) {
        private val warnings = mutableListOf<String>()
        private val mappings = linkedMapOf<String, String>()
        private val warnedTzids = mutableSetOf<String>()

        fun run(): ParseResult {
            val masters = linkedMapOf<String, EventRow>()
            val exceptions = linkedMapOf<String, EventRow>()
            for (cal in calendars) {
                val ctx = CalContext(cal.timezoneInfo)
                for (event in cal.events) {
                    val uid = event.uid?.value ?: "nouid-${event.summary?.value.hashCode()}-${event.dateStart?.value?.time}"
                    val row = runCatching { mapEvent(event, uid, ctx) }.getOrElse {
                        warnings += "Skipped event $uid: ${it.message}"; null
                    } ?: continue
                    if (row.originalSyncId == null) {
                        if (masters.put(uid, row) != null) warnings += "Duplicate event $uid; using the last one"
                    } else {
                        exceptions[row.syncId] = row
                    }
                }
            }
            val result = ArrayList<EventRow>(masters.values)
            for (ex in exceptions.values) {
                val uid = ex.originalSyncId!!
                result += when {
                    uid in masters -> ex
                    // Exception without its series: show it as a plain event (first one takes the bare UID).
                    result.none { it.syncId == uid } -> ex.copy(syncId = uid, originalSyncId = null, originalInstanceTime = null, originalAllDay = null)
                    else -> ex.copy(originalSyncId = null, originalInstanceTime = null, originalAllDay = null)
                }
            }
            val name = calendars.firstNotNullOfOrNull { it.getExperimentalProperty("X-WR-CALNAME")?.value }
                ?: calendars.firstNotNullOfOrNull { c -> c.names.firstOrNull()?.value }
            return ParseResult(name, result, warnings, mappings)
        }

        private inner class CalContext(val tzInfo: TimezoneInfo) {
            val rules: Map<String, VTimezoneRules> = tzInfo.components.mapNotNull { vt ->
                val id = vt.timezoneId?.value ?: return@mapNotNull null
                id to VTimezoneRules(id, vt.standardTimes.firstOrNull()?.let(::observance), vt.daylightSavingsTime.firstOrNull()?.let(::observance))
            }.toMap()
        }

        /** A point in time plus the zone id Android should store with it. */
        private inner class Resolved(val millis: Long, val zone: String, val allDay: Boolean)

        private fun resolve(prop: ICalProperty, value: ICalDate, ctx: CalContext): Resolved {
            val raw = value.rawComponents ?: biweekly.util.DateTimeComponents(value)
            if (!value.hasTime()) {
                val date = LocalDate.of(raw.year, raw.month, raw.date)
                return Resolved(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), "UTC", true)
            }
            val local = LocalDateTime.of(raw.year, raw.month, raw.date, raw.hour, raw.minute, raw.second)
            if (raw.isUtc) return Resolved(local.toInstant(ZoneOffset.UTC).toEpochMilli(), "UTC", false)
            val tzid = ctx.tzInfo.getTimezone(prop)?.component?.timezoneId?.value
                ?: prop.parameters.timezoneId
            return when (val r = resolver.resolve(tzid, tzid?.let { ctx.rules[it] })) {
                is Resolution.Zone -> {
                    if (tzid != null) mappings[tzid] = r.zoneId.id
                    Resolved(local.atZone(r.zoneId).toInstant().toEpochMilli(), r.zoneId.id, false)
                }
                is Resolution.FixedOffset -> {
                    val zone = if (r.offset == ZoneOffset.UTC) "UTC" else "GMT${r.offset.id}"
                    if (tzid != null) {
                        mappings[tzid] = zone
                        if (warnedTzids.add(tzid)) warnings += "Timezone \"$tzid\" not recognised; using fixed offset $zone (times may be an hour off around daylight-saving changes)"
                    }
                    Resolved(local.toInstant(r.offset).toEpochMilli(), zone, false)
                }
                Resolution.Floating -> {
                    val zone = resolver.deviceZone
                    if (tzid != null) {
                        mappings[tzid] = zone.id
                        if (warnedTzids.add(tzid)) warnings += "Timezone \"$tzid\" not recognised and not defined in the feed; using this phone's zone ${zone.id}"
                    }
                    Resolved(local.atZone(zone).toInstant().toEpochMilli(), zone.id, false)
                }
            }
        }

        private fun mapEvent(e: VEvent, uid: String, ctx: CalContext): EventRow {
            val startProp = e.dateStart ?: error("no start time")
            val start = resolve(startProp, startProp.value, ctx)
            val end: Resolved? = e.dateEnd?.let { resolve(it, it.value, ctx) }
            val durationMs = when {
                end != null -> end.millis - start.millis
                e.duration != null -> e.duration.value.toMillis()
                start.allDay -> DAY_MS
                else -> 0L
            }.coerceAtLeast(0)

            val rrule = e.recurrenceRule?.let { RecurrenceRuleScribe().writeText(it, WriteContext(ICalVersion.V2_0, ctx.tzInfo, null)) }
            val rdates = e.recurrenceDates.flatMap { p -> p.dates.orEmpty().map { formatUtc(resolve(p, it, ctx)) } }
            val exdates = e.exceptionDates.flatMap { p -> p.values.map { formatUtc(resolve(p, it, ctx)) } }
            val recurring = rrule != null || rdates.isNotEmpty()

            val recurrenceId = e.recurrenceId
            val original = recurrenceId?.let { resolve(it, it.value, ctx) }

            return EventRow(
                syncId = if (original == null) uid else "$uid#${original.millis}",
                originalSyncId = original?.let { uid },
                originalInstanceTime = original?.millis,
                originalAllDay = original?.allDay,
                title = e.summary?.value.orEmpty(),
                description = e.description?.value,
                location = e.location?.value,
                dtStart = start.millis,
                dtEnd = if (recurring) null else start.millis + durationMs,
                duration = if (!recurring) null else if (start.allDay) "P${maxOf(1, durationMs / DAY_MS)}D" else "P${durationMs / 1000}S",
                allDay = start.allDay,
                eventTimezone = start.zone,
                eventEndTimezone = if (recurring) null else (end?.zone ?: start.zone),
                rrule = rrule,
                rdate = rdates.takeIf { it.isNotEmpty() }?.joinToString(","),
                exdate = exdates.takeIf { it.isNotEmpty() }?.joinToString(","),
                status = when (e.status?.value?.uppercase()) {
                    "CANCELLED" -> Cal.STATUS_CANCELED
                    "TENTATIVE" -> Cal.STATUS_TENTATIVE
                    "CONFIRMED" -> Cal.STATUS_CONFIRMED
                    else -> null
                },
                availability = availability(e),
                accessLevel = when (e.classification?.value?.uppercase()) {
                    "PUBLIC" -> Cal.ACCESS_PUBLIC
                    "PRIVATE" -> Cal.ACCESS_PRIVATE
                    "CONFIDENTIAL" -> Cal.ACCESS_CONFIDENTIAL
                    else -> Cal.ACCESS_DEFAULT
                },
                reminderMinutes = e.alarms.mapNotNull { alarm ->
                    val action = alarm.action?.value?.uppercase()
                    if (action != null && action != "DISPLAY" && action != "AUDIO") return@mapNotNull null
                    val trigger = alarm.trigger ?: return@mapNotNull null
                    if (trigger.related?.value?.uppercase() == "END") return@mapNotNull null
                    val millis = trigger.duration?.toMillis() ?: return@mapNotNull null
                    if (millis > 0) null else (-millis / 60_000).toInt()
                }.distinct(),
            )
        }

        private fun availability(e: VEvent): Int =
            when (e.getExperimentalProperty("X-MICROSOFT-CDO-BUSYSTATUS")?.value?.uppercase()) {
                "FREE" -> Cal.AVAIL_FREE
                "TENTATIVE" -> Cal.AVAIL_TENTATIVE
                "BUSY", "OOF", "WORKINGELSEWHERE" -> Cal.AVAIL_BUSY
                else -> if (e.transparency?.value?.uppercase() == "TRANSPARENT") Cal.AVAIL_FREE else Cal.AVAIL_BUSY
            }

        private fun formatUtc(r: Resolved): String =
            if (r.allDay) DATE.format(Instant.ofEpochMilli(r.millis).atZone(ZoneOffset.UTC))
            else DATE_TIME.format(Instant.ofEpochMilli(r.millis).atZone(ZoneOffset.UTC))
    }

    private fun observance(o: BwObservance): Observance? {
        val offset = o.timezoneOffsetTo?.value ?: return null
        val to = ZoneOffset.ofTotalSeconds((offset.millis / 1000).toInt())
        val rule = o.recurrenceRule?.value
        val raw = o.dateStart?.value?.rawComponents
        val time = if (raw != null && raw.hasTime()) LocalTime.of(raw.hour, raw.minute, raw.second) else LocalTime.MIDNIGHT
        val byDay = rule?.byDay?.firstOrNull()
        return Observance(
            offsetTo = to,
            month = rule?.byMonth?.firstOrNull() ?: raw?.month ?: 1,
            weekOfMonth = byDay?.num ?: 1,
            dayOfWeek = byDay?.day?.let { DayOfWeek.valueOf(it.name) } ?: DayOfWeek.SUNDAY,
            localTime = time,
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    }
}
