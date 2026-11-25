package dev.jhadenfeldt.calendarbridge.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        SyncEngine(applicationContext).sync(id, inputData.getBoolean(KEY_FORCE, false))
        // Errors are shown on the subscription; the next periodic run is the retry.
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_FORCE = "force"
        fun input(id: String, force: Boolean) = workDataOf(KEY_ID to id, KEY_FORCE to force)
    }
}
