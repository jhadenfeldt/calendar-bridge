package dev.jhadenfeldt.calendarbridge.sync

import android.content.Context
import android.util.Log
import dev.jhadenfeldt.calendarbridge.android.CalendarStore
import dev.jhadenfeldt.calendarbridge.android.IcuWindowsZoneLookup
import dev.jhadenfeldt.calendarbridge.core.IcsParseException
import dev.jhadenfeldt.calendarbridge.core.IcsParser
import dev.jhadenfeldt.calendarbridge.core.SyncPlanner
import dev.jhadenfeldt.calendarbridge.core.TimezoneResolver
import dev.jhadenfeldt.calendarbridge.data.SubscriptionRepository
import java.time.ZoneId

/** fetch → parse → plan → write, for one subscription. Failures are stored on the subscription, never thrown. */
class SyncEngine(
    private val context: Context,
    private val repo: SubscriptionRepository = SubscriptionRepository.get(context),
    private val store: CalendarStore = CalendarStore(context.contentResolver),
    private val fetcher: FeedFetcher = FeedFetcher(),
) {
    fun sync(subscriptionId: String, force: Boolean): Result<Unit> = SubscriptionLocks.withLock(subscriptionId) {
        val sub = repo.get(subscriptionId) ?: return@withLock Result.success(Unit)
        val tag = "${sub.name} (${redact(sub.url)})"
        try {
            val account = store.ensureAccount(context)
            val calendarId = sub.calendarId?.takeIf(store::calendarExists)
                ?: store.createCalendar(account, sub.name, sub.color).also { id -> repo.update(sub.id) { it.copy(calendarId = id) } }
            val freshCalendar = calendarId != sub.calendarId

            when (val fetched = fetcher.fetch(sub.url, sub.etag, sub.lastModified, force = force || freshCalendar)) {
                is FetchResult.Failed -> {
                    Log.w(TAG, "$tag: ${fetched.message}")
                    repo.update(sub.id) { it.copy(lastError = fetched.message) }
                    Result.failure(IllegalStateException(fetched.message))
                }
                FetchResult.NotModified -> {
                    repo.update(sub.id) { it.copy(lastSyncAt = System.currentTimeMillis(), lastError = null) }
                    Result.success(Unit)
                }
                is FetchResult.Ok -> {
                    val parsed = IcsParser(TimezoneResolver(IcuWindowsZoneLookup, ZoneId.systemDefault())).parse(fetched.body)
                    val plan = SyncPlanner.plan(store.existingRows(calendarId), parsed.events, force)
                    // Writes span several transactions. Drop the validators first so a write that fails
                    // partway gets a full refetch + diff next time instead of a 304 that never repairs it.
                    repo.update(sub.id) { it.copy(etag = null, lastModified = null) }
                    store.apply(account, calendarId, plan, sub.defaultReminderMinutes)
                    Log.i(TAG, "$tag: +${plan.inserts.size} ~${plan.updates.size} -${plan.deletes.size}, ${parsed.warnings.size} warnings")
                    repo.update(sub.id) {
                        it.copy(
                            etag = fetched.etag, lastModified = fetched.lastModified,
                            lastSyncAt = System.currentTimeMillis(), lastError = null,
                            eventCount = parsed.events.size, warnings = parsed.warnings, tzidMappings = parsed.tzidMappings,
                        )
                    }
                    Result.success(Unit)
                }
            }
        } catch (e: IcsParseException) {
            Log.w(TAG, "$tag: ${e.message}")
            repo.update(sub.id) { it.copy(lastError = e.message) }
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "$tag: sync failed", e)
            repo.update(sub.id) { it.copy(lastError = "Sync failed: ${e.javaClass.simpleName}") }
            Result.failure(e)
        }
    }

    /** Applies name/color changes to an existing calendar. */
    fun updateCalendar(subscriptionId: String) = SubscriptionLocks.withLock(subscriptionId) {
        val sub = repo.get(subscriptionId) ?: return@withLock
        val id = sub.calendarId?.takeIf(store::calendarExists) ?: return@withLock
        store.updateCalendar(store.ensureAccount(context), id, sub.name, sub.color)
    }

    /** Removes the subscription, its calendar and all its events. */
    fun remove(subscriptionId: String) = SubscriptionLocks.withLock(subscriptionId) {
        SyncScheduler.cancel(context, subscriptionId)
        repo.get(subscriptionId)?.calendarId?.let { store.deleteCalendar(store.ensureAccount(context), it) }
        repo.delete(subscriptionId)
    }

    companion object { const val TAG = "CalBridge" }
}
