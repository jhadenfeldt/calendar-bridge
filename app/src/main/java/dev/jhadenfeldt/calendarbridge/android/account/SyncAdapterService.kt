package dev.jhadenfeldt.calendarbridge.android.account

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import dev.jhadenfeldt.calendarbridge.sync.SyncScheduler

/** Lets calendar apps' "refresh" reach us; the actual work runs in WorkManager. */
class SyncAdapterService : Service() {
    private lateinit var adapter: Adapter
    override fun onCreate() { adapter = Adapter(applicationContext) }
    override fun onBind(intent: Intent?): IBinder = adapter.syncAdapterBinder

    private class Adapter(context: Context) : AbstractThreadedSyncAdapter(context, true) {
        override fun onPerformSync(account: Account?, extras: Bundle?, authority: String?, provider: ContentProviderClient?, result: SyncResult?) {
            SyncScheduler.syncAll(context, force = false)
        }
    }
}
