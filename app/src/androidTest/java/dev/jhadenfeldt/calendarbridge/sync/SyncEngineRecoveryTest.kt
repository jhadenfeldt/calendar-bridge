package dev.jhadenfeldt.calendarbridge.sync

import android.Manifest
import android.accounts.Account
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import dev.jhadenfeldt.calendarbridge.android.CalendarStore
import dev.jhadenfeldt.calendarbridge.core.SyncPlan
import dev.jhadenfeldt.calendarbridge.data.Subscription
import dev.jhadenfeldt.calendarbridge.data.SubscriptionRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A write that fails partway must not leave validators behind, or the next sync gets a 304 and never repairs the calendar. */
@RunWith(AndroidJUnit4::class)
class SyncEngineRecoveryTest {
    @get:Rule val permissions: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = SubscriptionRepository.get(context)
    private val ics = InstrumentationRegistry.getInstrumentation().context.assets.open("outlook-sample.ics").bufferedReader().readText()
    private val sub = Subscription(id = "recovery-test", url = "https://example.invalid/cal.ics", name = "CB Recovery Test", color = 0)

    private val okFetcher = object : FeedFetcher() {
        override fun fetch(url: String, etag: String?, lastModified: String?, force: Boolean) = FetchResult.Ok(ics, "\"v2\"", "Wed, 25 Nov 2026 10:00:00 GMT")
    }

    @After fun cleanUp() {
        repo.get(sub.id)?.calendarId?.let { CalendarStore(context.contentResolver).run { deleteCalendar(ensureAccount(context), it) } }
        repo.delete(sub.id)
    }

    @Test fun failedWriteClearsValidators() {
        repo.upsert(sub.copy(etag = "\"v1\"", lastModified = "Tue, 24 Nov 2026 10:00:00 GMT"))
        val failingStore = object : CalendarStore(context.contentResolver) {
            override fun apply(account: Account, calendarId: Long, plan: SyncPlan, defaultReminderMinutes: Int?) =
                throw IllegalStateException("simulated provider failure")
        }
        SyncEngine(context, repo, failingStore, okFetcher).sync(sub.id, force = true)

        val after = repo.get(sub.id)!!
        assertNotNull(after.lastError)
        assertNull("etag must be cleared so the next sync refetches", after.etag)
        assertNull(after.lastModified)
    }
}
