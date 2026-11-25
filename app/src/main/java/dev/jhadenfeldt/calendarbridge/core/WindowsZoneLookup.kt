package dev.jhadenfeldt.calendarbridge.core

/** Maps a Windows timezone name ("Pacific Standard Time") to an IANA id, or null. */
fun interface WindowsZoneLookup {
    fun ianaFor(windowsId: String): String?
}
