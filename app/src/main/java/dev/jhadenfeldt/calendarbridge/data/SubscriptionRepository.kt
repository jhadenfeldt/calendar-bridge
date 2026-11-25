package dev.jhadenfeldt.calendarbridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Subscriptions as one JSON array in SharedPreferences; a handful of rows needs nothing heavier. */
class SubscriptionRepository private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("subscriptions", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val changes: StateFlow<List<Subscription>> = state

    fun all(): List<Subscription> = state.value
    fun get(id: String): Subscription? = state.value.firstOrNull { it.id == id }

    @Synchronized fun upsert(s: Subscription) = save(state.value.filter { it.id != s.id } + s)
    @Synchronized fun update(id: String, change: (Subscription) -> Subscription) {
        val current = get(id) ?: return
        upsert(change(current))
    }
    @Synchronized fun delete(id: String) = save(state.value.filter { it.id != id })

    private fun save(list: List<Subscription>) {
        val sorted = list.sortedBy { it.name.lowercase() }
        prefs.edit().putString(KEY, JSONArray(sorted.map(::toJson)).toString()).apply()
        state.value = sorted
    }

    private fun load(): List<Subscription> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }

    private fun toJson(s: Subscription) = JSONObject().apply {
        put("id", s.id); put("url", s.url); put("name", s.name); put("color", s.color)
        put("intervalMinutes", s.intervalMinutes)
        s.defaultReminderMinutes?.let { put("defaultReminderMinutes", it) }
        s.calendarId?.let { put("calendarId", it) }
        s.etag?.let { put("etag", it) }
        s.lastModified?.let { put("lastModified", it) }
        s.lastSyncAt?.let { put("lastSyncAt", it) }
        s.lastError?.let { put("lastError", it) }
        put("eventCount", s.eventCount)
        put("warnings", JSONArray(s.warnings))
        put("tzidMappings", JSONObject(s.tzidMappings))
    }

    private fun fromJson(o: JSONObject) = Subscription(
        id = o.getString("id"),
        url = o.getString("url"),
        name = o.getString("name"),
        color = o.getInt("color"),
        intervalMinutes = o.optInt("intervalMinutes", 60),
        defaultReminderMinutes = if (o.has("defaultReminderMinutes")) o.getInt("defaultReminderMinutes") else null,
        calendarId = if (o.has("calendarId")) o.getLong("calendarId") else null,
        etag = o.optString("etag").ifEmpty { null },
        lastModified = o.optString("lastModified").ifEmpty { null },
        lastSyncAt = if (o.has("lastSyncAt")) o.getLong("lastSyncAt") else null,
        lastError = o.optString("lastError").ifEmpty { null },
        eventCount = o.optInt("eventCount"),
        warnings = o.optJSONArray("warnings")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        tzidMappings = o.optJSONObject("tzidMappings")?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty(),
    )

    companion object {
        private const val KEY = "list"
        @Volatile private var instance: SubscriptionRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: SubscriptionRepository(context).also { instance = it }
        }
    }
}
