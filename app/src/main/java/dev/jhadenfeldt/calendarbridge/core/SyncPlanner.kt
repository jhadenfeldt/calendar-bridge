package dev.jhadenfeldt.calendarbridge.core

import java.security.MessageDigest

/** Bump when the ICS → row mapping changes, so existing events get rewritten once with the fix. */
const val MAPPING_VERSION = 1

fun EventRow.contentHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest("$MAPPING_VERSION|$this".toByteArray())
        .joinToString("") { "%02x".format(it) }

data class ExistingRow(val id: Long, val syncId: String, val hash: String?)

/** Inserts and updates are ordered masters first, so exceptions always find their series. */
data class SyncPlan(
    val inserts: List<EventRow>,
    val updates: List<Pair<Long, EventRow>>,
    val deletes: List<Long>,
) {
    val isEmpty get() = inserts.isEmpty() && updates.isEmpty() && deletes.isEmpty()
}

object SyncPlanner {
    /** Mirror [incoming] onto [existing]; with [force], replace everything. */
    fun plan(existing: List<ExistingRow>, incoming: List<EventRow>, force: Boolean): SyncPlan {
        val ordered = incoming.sortedBy { it.originalSyncId != null }
        if (force) return SyncPlan(ordered, emptyList(), existing.map { it.id })

        val bySyncId = LinkedHashMap<String, ExistingRow>()
        val deletes = mutableListOf<Long>()
        for (row in existing) {
            if (bySyncId.putIfAbsent(row.syncId, row) != null) deletes += row.id
        }
        val inserts = mutableListOf<EventRow>()
        val updates = mutableListOf<Pair<Long, EventRow>>()
        for (event in ordered) {
            val old = bySyncId.remove(event.syncId)
            when {
                old == null -> inserts += event
                old.hash != event.contentHash() -> updates += old.id to event
            }
        }
        deletes += bySyncId.values.map { it.id }
        return SyncPlan(inserts, updates, deletes)
    }
}
