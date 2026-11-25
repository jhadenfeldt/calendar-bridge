package dev.jhadenfeldt.calendarbridge.android

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import dev.jhadenfeldt.calendarbridge.core.EventRow
import dev.jhadenfeldt.calendarbridge.core.ExistingRow
import dev.jhadenfeldt.calendarbridge.core.SyncPlan
import dev.jhadenfeldt.calendarbridge.core.contentHash
import java.util.TimeZone

/** Everything that touches the system calendar provider. Calls block; use a background thread. */
open class CalendarStore(private val resolver: ContentResolver) {

    fun ensureAccount(context: Context): Account {
        val am = AccountManager.get(context)
        val account = am.getAccountsByType(ACCOUNT_TYPE).firstOrNull() ?: Account(ACCOUNT_NAME, ACCOUNT_TYPE).also {
            am.addAccountExplicitly(it, null, null)
        }
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
        return account
    }

    fun createCalendar(account: Account, name: String, color: Int): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT}")
            put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE},${Events.AVAILABILITY_TENTATIVE}")
        }
        val uri = resolver.insert(asSyncAdapter(Calendars.CONTENT_URI, account), values)
            ?: error("Calendar provider refused to create a calendar")
        return uri.lastPathSegment!!.toLong()
    }

    fun calendarExists(id: Long): Boolean =
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), "${Calendars._ID}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(id.toString(), ACCOUNT_TYPE), null)?.use { it.count > 0 } ?: false

    fun updateCalendar(account: Account, id: Long, name: String, color: Int) {
        val values = ContentValues().apply {
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.CALENDAR_COLOR, color)
        }
        resolver.update(asSyncAdapter(Calendars.CONTENT_URI, account), values, "${Calendars._ID}=?", arrayOf(id.toString()))
    }

    /** Deletes the calendar; the provider removes its events with it. */
    fun deleteCalendar(account: Account, id: Long) {
        resolver.delete(asSyncAdapter(Calendars.CONTENT_URI, account), "${Calendars._ID}=?", arrayOf(id.toString()))
    }

    fun existingRows(calendarId: Long): List<ExistingRow> {
        val rows = mutableListOf<ExistingRow>()
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null)?.use { c ->
            while (c.moveToNext()) rows += ExistingRow(c.getLong(0), c.getString(1) ?: "", c.getString(2))
        }
        return rows
    }

    open fun apply(account: Account, calendarId: Long, plan: SyncPlan, defaultReminderMinutes: Int?) {
        val eventsUri = asSyncAdapter(Events.CONTENT_URI, account)
        val remindersUri = asSyncAdapter(Reminders.CONTENT_URI, account)

        /** One event's operations, built once its position in the batch is known (for back-references). */
        class Group(val size: Int, val build: (offset: Int) -> List<ContentProviderOperation>)

        fun reminderOp(minutes: Int, eventId: Long?, eventOpIndex: Int) =
            ContentProviderOperation.newInsert(remindersUri)
                .withValue(Reminders.MINUTES, minutes)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .apply { if (eventId != null) withValue(Reminders.EVENT_ID, eventId) else withValueBackReference(Reminders.EVENT_ID, eventOpIndex) }
                .build()

        val groups = mutableListOf<Group>()
        // Deletes first, so a force sync can reuse the same sync ids.
        plan.deletes.forEach { id ->
            groups += Group(1) { listOf(ContentProviderOperation.newDelete(eventsUri).withSelection("${Events._ID}=?", arrayOf(id.toString())).build()) }
        }
        plan.inserts.forEach { row ->
            val minutes = reminders(row, defaultReminderMinutes)
            groups += Group(1 + minutes.size) { offset ->
                listOf(ContentProviderOperation.newInsert(eventsUri).withValues(values(row, calendarId, defaultReminderMinutes)).build()) +
                    minutes.map { reminderOp(it, eventId = null, eventOpIndex = offset) }
            }
        }
        plan.updates.forEach { (id, row) ->
            val minutes = reminders(row, defaultReminderMinutes)
            groups += Group(2 + minutes.size) {
                listOf(
                    ContentProviderOperation.newUpdate(eventsUri).withSelection("${Events._ID}=?", arrayOf(id.toString()))
                        .withValues(values(row, calendarId, defaultReminderMinutes)).build(),
                    ContentProviderOperation.newDelete(remindersUri).withSelection("${Reminders.EVENT_ID}=?", arrayOf(id.toString())).build(),
                ) + minutes.map { reminderOp(it, eventId = id, eventOpIndex = 0) }
            }
        }

        val batch = ArrayList<ContentProviderOperation>()
        for (group in groups) {
            if (batch.isNotEmpty() && batch.size + group.size > MAX_BATCH) flush(batch)
            batch += group.build(batch.size)
        }
        flush(batch)
    }

    private fun flush(batch: ArrayList<ContentProviderOperation>) {
        if (batch.isEmpty()) return
        resolver.applyBatch(CalendarContract.AUTHORITY, batch)
        batch.clear()
    }

    private fun reminders(row: EventRow, defaultMinutes: Int?): List<Int> =
        row.reminderMinutes.ifEmpty { listOfNotNull(defaultMinutes) }

    private fun values(row: EventRow, calendarId: Long, defaultReminderMinutes: Int?) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events._SYNC_ID, row.syncId)
        put(Events.SYNC_DATA1, row.contentHash())
        put(Events.TITLE, row.title)
        put(Events.DESCRIPTION, row.description)
        put(Events.EVENT_LOCATION, row.location)
        put(Events.DTSTART, row.dtStart)
        put(Events.DTEND, row.dtEnd)
        put(Events.DURATION, row.duration)
        put(Events.ALL_DAY, if (row.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, row.eventTimezone)
        put(Events.EVENT_END_TIMEZONE, row.eventEndTimezone)
        put(Events.RRULE, row.rrule)
        put(Events.RDATE, row.rdate)
        put(Events.EXDATE, row.exdate)
        put(Events.STATUS, row.status)
        put(Events.AVAILABILITY, row.availability)
        put(Events.ACCESS_LEVEL, row.accessLevel)
        put(Events.HAS_ALARM, if (reminders(row, defaultReminderMinutes).isEmpty()) 0 else 1)
        put(Events.ORIGINAL_SYNC_ID, row.originalSyncId)
        put(Events.ORIGINAL_INSTANCE_TIME, row.originalInstanceTime)
        put(Events.ORIGINAL_ALL_DAY, row.originalAllDay?.let { if (it) 1 else 0 })
    }

    companion object {
        const val ACCOUNT_TYPE = "dev.jhadenfeldt.calendarbridge.account"
        const val ACCOUNT_NAME = "Calendar Bridge"
        private const val MAX_BATCH = 400

        fun asSyncAdapter(uri: Uri, account: Account): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
            .build()
    }
}
