package dev.jhadenfeldt.calendarbridge.core

/** JVM-test stand-in for Android's built-in ICU (same API, different package). */
object Icu4jWindowsZoneLookup : WindowsZoneLookup {
    override fun ianaFor(windowsId: String): String? =
        com.ibm.icu.util.TimeZone.getIDForWindowsID(windowsId, null)
}
