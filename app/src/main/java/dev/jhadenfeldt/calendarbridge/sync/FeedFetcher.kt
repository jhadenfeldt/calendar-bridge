package dev.jhadenfeldt.calendarbridge.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface FetchResult {
    data class Ok(val body: String, val etag: String?, val lastModified: String?) : FetchResult
    data object NotModified : FetchResult
    data class Failed(val message: String) : FetchResult
}

/** `webcal://` and `webcals://` are just HTTPS for calendar apps. */
fun normalizeUrl(url: String): String {
    val u = url.trim()
    return when {
        u.startsWith("webcals://", ignoreCase = true) -> "https://" + u.substring(10)
        u.startsWith("webcal://", ignoreCase = true) -> "https://" + u.substring(9)
        else -> u
    }
}

/** Published-calendar URLs carry a secret in the path; only scheme and host are safe to show or log. */
fun redact(url: String): String {
    val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]+)").find(url.trim()) ?: return "…"
    return m.groupValues[1] + "/…"
}

open class FeedFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    /** Blocking; call from a worker thread. */
    open fun fetch(url: String, etag: String?, lastModified: String?, force: Boolean): FetchResult {
        val request = Request.Builder()
            .url(normalizeUrl(url))
            .header("Accept", "text/calendar, */*;q=0.5")
            .header("User-Agent", "CalendarBridge (Android)")
            .apply {
                if (!force) {
                    etag?.let { header("If-None-Match", it) }
                    lastModified?.let { header("If-Modified-Since", it) }
                }
            }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> FetchResult.NotModified
                    !response.isSuccessful -> FetchResult.Failed("Server answered HTTP ${response.code}")
                    else -> {
                        val body = response.body?.string().orEmpty()
                        if (!body.contains("BEGIN:VCALENDAR")) FetchResult.Failed("The URL did not return a calendar (got ${response.header("Content-Type") ?: "unknown content"})")
                        else FetchResult.Ok(body, response.header("ETag"), response.header("Last-Modified"))
                    }
                }
            }
        } catch (e: IOException) {
            FetchResult.Failed("Network error: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            FetchResult.Failed("Invalid URL")
        }
    }
}
