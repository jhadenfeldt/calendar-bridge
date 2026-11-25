package dev.jhadenfeldt.calendarbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.jhadenfeldt.calendarbridge.data.Subscription
import dev.jhadenfeldt.calendarbridge.data.SubscriptionRepository
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context, s: Subscription) {
        val wm = WorkManager.getInstance(context)
        if (s.intervalMinutes <= 0) {
            wm.cancelUniqueWork(periodicName(s.id))
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(s.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(network)
            .setInputData(SyncWorker.input(s.id, force = false))
            .build()
        wm.enqueueUniquePeriodicWork(periodicName(s.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context, id: String) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(periodicName(id))
        wm.cancelUniqueWork(oneTimeName(id))
    }

    /** A forced sync replaces a queued normal one; a normal one never displaces anything already queued. */
    fun syncNow(context: Context, id: String, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setInputData(SyncWorker.input(id, force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            oneTimeName(id), if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request,
        )
    }

    fun syncAll(context: Context, force: Boolean) =
        SubscriptionRepository.get(context).all().forEach { syncNow(context, it.id, force) }

    private fun periodicName(id: String) = "periodic-$id"
    private fun oneTimeName(id: String) = "sync-$id"
}
