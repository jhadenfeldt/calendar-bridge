package dev.jhadenfeldt.calendarbridge.android

import android.Manifest
import android.accounts.Account
import android.content.ContentUris
import android.provider.CalendarContract.Instances
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.jhadenfeldt.calendarbridge.core.IcsParser
import dev.jhadenfeldt.calendarbridge.core.SyncPlanner
import dev.jhadenfeldt.calendarbridge.core.TimezoneResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CalendarStoreTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = CalendarStore(context.contentResolver)
    private lateinit var account: Account
    private var calendarId = 0L

    private val events by lazy {
        val text = InstrumentationRegistry.getInstrumentation().context.assets.open("outlook-sample.ics").bufferedReader().readText()
        IcsParser(TimezoneResolver(IcuWindowsZoneLookup, ZoneId.of("Europe/Berlin"))).parse(text).events
    }

    @Before fun setUp() {
        account = store.ensureAccount(context)
        calendarId = store.createCalendar(account, "CB Test", 0xFF1E5AA8.toInt())
    }

    @After fun tearDown() = store.deleteCalendar(account, calendarId)

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun instancesBetween(from: String, to: String): List<Pair<String, Long>> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, ms(from)); ContentUris.appendId(it, ms(to))
        }.build()
        val out = mutableListOf<Pair<String, Long>>()
        context.contentResolver.query(uri, arrayOf(Instances.TITLE, Instances.BEGIN), "${Instances.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null)!!.use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getLong(1)
        }
        return out
    }

    @Test fun recurringOutlookEventKeepsWallClockAcrossDst() {
        store.apply(account, calendarId, SyncPlanner.plan(emptyList(), events, force = false), defaultReminderMinutes = null)

        // Before DST ends (PDT) and after (PST): 09:30 Pacific both times.
        assertEquals(listOf("Weekly sync" to ms("2026-10-19T09:30-07:00")), instancesBetween("2026-10-19T00:00-07:00", "2026-10-20T00:00-07:00"))
        assertEquals(listOf("Weekly sync" to ms("2026-11-16T09:30-08:00")), instancesBetween("2026-11-16T00:00-08:00", "2026-11-17T00:00-08:00"))
        // EXDATE on 10/26 and the cancelled exception on 11/9 remove those instances.
        assertTrue(instancesBetween("2026-10-26T00:00-07:00", "2026-10-27T00:00-07:00").isEmpty())
        assertTrue(instancesBetween("2026-11-09T00:00-08:00", "2026-11-10T00:00-08:00").isEmpty())
        // The moved exception shows at its new time, not the original one.
        assertEquals(listOf("Weekly sync (moved)" to ms("2026-11-02T11:00-08:00")), instancesBetween("2026-11-02T00:00-08:00", "2026-11-03T00:00-08:00"))
    }

    @Test fun resyncIsANoOp() {
        store.apply(account, calendarId, SyncPlanner.plan(emptyList(), events, force = false), defaultReminderMinutes = null)
        val again = SyncPlanner.plan(store.existingRows(calendarId), events, force = false)
        assertTrue("expected no changes, got $again", again.isEmpty)
    }

    @Test fun forceSyncLeavesNoDuplicates() {
        store.apply(account, calendarId, SyncPlanner.plan(emptyList(), events, force = false), defaultReminderMinutes = null)
        store.apply(account, calendarId, SyncPlanner.plan(store.existingRows(calendarId), events, force = true), defaultReminderMinutes = null)
        assertEquals(events.size, store.existingRows(calendarId).size)
    }
}
