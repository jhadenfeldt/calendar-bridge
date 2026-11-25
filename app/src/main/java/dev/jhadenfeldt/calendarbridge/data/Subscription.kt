package dev.jhadenfeldt.calendarbridge.data

data class Subscription(
    val id: String,
    val url: String,
    val name: String,
    val color: Int,
    /** 0 = manual only; otherwise 15, 60, 360 or 1440. */
    val intervalMinutes: Int = 60,
    val defaultReminderMinutes: Int? = null,
    val calendarId: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val eventCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val tzidMappings: Map<String, String> = emptyMap(),
) {
    companion object {
        val INTERVALS = listOf(0, 15, 60, 360, 1440)
        val REMINDERS = listOf(null, 5, 10, 15, 30, 60)
        val COLORS = listOf(0xFF1E5AA8, 0xFF0B8043, 0xFFD50000, 0xFFF4511E, 0xFFF6BF26, 0xFF8E24AA, 0xFF039BE5, 0xFF616161)
            .map { it.toInt() }
    }
}
