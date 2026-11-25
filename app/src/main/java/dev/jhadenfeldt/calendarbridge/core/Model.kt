package dev.jhadenfeldt.calendarbridge.core

/** One row for CalendarContract.Events, fully resolved; no Android types so it's testable on the JVM. */
data class EventRow(
    val syncId: String,
    val originalSyncId: String? = null,
    val originalInstanceTime: Long? = null,
    val originalAllDay: Boolean? = null,
    val title: String,
    val description: String?,
    val location: String?,
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val eventTimezone: String,
    val eventEndTimezone: String?,
    val rrule: String?,
    val rdate: String?,
    val exdate: String?,
    val status: Int?,
    val availability: Int,
    val accessLevel: Int,
    val reminderMinutes: List<Int>,
)

data class ParseResult(
    val calendarName: String?,
    val events: List<EventRow>,
    val warnings: List<String>,
    /** Original TZID → the zone id actually stored. */
    val tzidMappings: Map<String, String>,
)

/** Mirrors of CalendarContract constants (checked against the real ones in CalConstantsTest). */
object Cal {
    const val STATUS_TENTATIVE = 0
    const val STATUS_CONFIRMED = 1
    const val STATUS_CANCELED = 2
    const val AVAIL_BUSY = 0
    const val AVAIL_FREE = 1
    const val AVAIL_TENTATIVE = 2
    const val ACCESS_DEFAULT = 0
    const val ACCESS_CONFIDENTIAL = 1
    const val ACCESS_PRIVATE = 2
    const val ACCESS_PUBLIC = 3
}

class IcsParseException(message: String) : Exception(message)
