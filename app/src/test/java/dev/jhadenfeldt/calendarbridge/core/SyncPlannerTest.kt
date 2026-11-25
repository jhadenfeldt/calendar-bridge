package dev.jhadenfeldt.calendarbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    private fun ev(id: String, title: String = "t", original: String? = null) = EventRow(
        syncId = id, originalSyncId = original, originalInstanceTime = original?.let { 1L }, originalAllDay = original?.let { false },
        title = title, description = null, location = null, dtStart = 0, dtEnd = 1, duration = null, allDay = false,
        eventTimezone = "UTC", eventEndTimezone = "UTC", rrule = null, rdate = null, exdate = null, status = null,
        availability = Cal.AVAIL_BUSY, accessLevel = Cal.ACCESS_DEFAULT, reminderMinutes = emptyList(),
    )

    @Test fun newEventsInserted() {
        val plan = SyncPlanner.plan(emptyList(), listOf(ev("a")), force = false)
        assertEquals(listOf("a"), plan.inserts.map { it.syncId })
        assertTrue(plan.updates.isEmpty() && plan.deletes.isEmpty())
    }

    @Test fun unchangedSkipped() {
        val a = ev("a")
        val plan = SyncPlanner.plan(listOf(ExistingRow(7, "a", a.contentHash())), listOf(a), force = false)
        assertEquals(SyncPlan(emptyList(), emptyList(), emptyList()), plan)
    }

    @Test fun changedUpdated() {
        val plan = SyncPlanner.plan(listOf(ExistingRow(7, "a", ev("a").contentHash())), listOf(ev("a", "new")), force = false)
        assertEquals(listOf(7L to ev("a", "new")), plan.updates)
        assertTrue(plan.inserts.isEmpty() && plan.deletes.isEmpty())
    }

    @Test fun missingDeleted() {
        val plan = SyncPlanner.plan(listOf(ExistingRow(7, "gone", "h")), emptyList(), force = false)
        assertEquals(listOf(7L), plan.deletes)
    }

    @Test fun forceRewritesAll() {
        val a = ev("a")
        val plan = SyncPlanner.plan(listOf(ExistingRow(7, "a", a.contentHash()), ExistingRow(8, "b", "x")), listOf(a), force = true)
        assertEquals(listOf(7L, 8L), plan.deletes)
        assertEquals(listOf(a), plan.inserts)
        assertTrue(plan.updates.isEmpty())
    }

    @Test fun mastersBeforeExceptions() {
        val plan = SyncPlanner.plan(emptyList(), listOf(ev("a#1", original = "a"), ev("a")), force = false)
        assertEquals(listOf("a", "a#1"), plan.inserts.map { it.syncId })
    }

    @Test fun duplicateExistingRowsCollapse() {
        // Two provider rows with the same sync id (e.g. after a crash): keep one, delete the other.
        val a = ev("a")
        val plan = SyncPlanner.plan(listOf(ExistingRow(7, "a", a.contentHash()), ExistingRow(9, "a", a.contentHash())), listOf(a), force = false)
        assertEquals(listOf(9L), plan.deletes)
        assertTrue(plan.inserts.isEmpty() && plan.updates.isEmpty())
    }

    @Test fun hashStableAcrossCalls() = assertEquals(ev("a").contentHash(), ev("a").contentHash())

    @Test fun hashDiffersWhenTitleDiffers() = assertNotEquals(ev("a").contentHash(), ev("a", "other").contentHash())
}
