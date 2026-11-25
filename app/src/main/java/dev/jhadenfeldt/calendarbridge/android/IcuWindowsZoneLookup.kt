package dev.jhadenfeldt.calendarbridge.android

import dev.jhadenfeldt.calendarbridge.core.WindowsZoneLookup

/** Android's built-in ICU carries CLDR's Windows→IANA table (API 24+). */
object IcuWindowsZoneLookup : WindowsZoneLookup {
    override fun ianaFor(windowsId: String): String? =
        android.icu.util.TimeZone.getIDForWindowsID(windowsId, null)
}
