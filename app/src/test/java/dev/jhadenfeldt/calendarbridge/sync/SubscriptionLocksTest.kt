package dev.jhadenfeldt.calendarbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SubscriptionLocksTest {
    @Test fun sameSubscriptionNeverRunsConcurrently() {
        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val start = CountDownLatch(1)
        val threads = (1..8).map {
            thread {
                start.await()
                SubscriptionLocks.withLock("sub-1") {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
                    Thread.sleep(20)
                    inside.decrementAndGet()
                }
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, maxInside.get())
    }

    @Test fun differentSubscriptionsRunInParallel() {
        val bothInside = CountDownLatch(2)
        val threads = listOf("a", "b").map { id ->
            thread {
                SubscriptionLocks.withLock(id) {
                    bothInside.countDown()
                    check(bothInside.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "locks for different ids blocked each other" }
                }
            }
        }
        threads.forEach { it.join() }
        assertEquals(0L, bothInside.count)
    }
}
