package dev.jhadenfeldt.calendarbridge

import android.provider.CalendarContract.Events
import dev.jhadenfeldt.calendarbridge.core.Cal
import org.junit.Assert.assertEquals
import org.junit.Test

/** core/ can't import Android, so it mirrors these constants; this keeps the mirror honest. */
class CalConstantsTest {
    @Test fun mirrorsCalendarContract() {
        assertEquals(Events.STATUS_TENTATIVE, Cal.STATUS_TENTATIVE)
        assertEquals(Events.STATUS_CONFIRMED, Cal.STATUS_CONFIRMED)
        assertEquals(Events.STATUS_CANCELED, Cal.STATUS_CANCELED)
        assertEquals(Events.AVAILABILITY_BUSY, Cal.AVAIL_BUSY)
        assertEquals(Events.AVAILABILITY_FREE, Cal.AVAIL_FREE)
        assertEquals(Events.AVAILABILITY_TENTATIVE, Cal.AVAIL_TENTATIVE)
        assertEquals(Events.ACCESS_DEFAULT, Cal.ACCESS_DEFAULT)
        assertEquals(Events.ACCESS_CONFIDENTIAL, Cal.ACCESS_CONFIDENTIAL)
        assertEquals(Events.ACCESS_PRIVATE, Cal.ACCESS_PRIVATE)
        assertEquals(Events.ACCESS_PUBLIC, Cal.ACCESS_PUBLIC)
    }
}
