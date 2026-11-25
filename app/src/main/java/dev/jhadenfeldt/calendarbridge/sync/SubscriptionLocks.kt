package dev.jhadenfeldt.calendarbridge.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One writer per calendar: periodic syncs, "Sync now" and "Force sync" for a subscription queue up. */
object SubscriptionLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    fun <T> withLock(subscriptionId: String, block: () -> T): T =
        locks.computeIfAbsent(subscriptionId) { ReentrantLock() }.withLock(block)
}
