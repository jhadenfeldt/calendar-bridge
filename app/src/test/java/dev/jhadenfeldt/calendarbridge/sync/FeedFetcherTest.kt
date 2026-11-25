package dev.jhadenfeldt.calendarbridge.sync

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedFetcherTest {
    private val server = MockWebServer().apply { start() }
    private val fetcher = FeedFetcher()
    private val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"
    private fun url() = server.url("/owa/calendar/secret-token/calendar.ics").toString()

    @After fun stop() = server.shutdown()

    @Test fun okReturnsBodyAndValidators() {
        server.enqueue(MockResponse().setBody(ics).setHeader("ETag", "\"v1\"").setHeader("Last-Modified", "Wed, 25 Nov 2026 10:00:00 GMT"))
        assertEquals(FetchResult.Ok(ics, "\"v1\"", "Wed, 25 Nov 2026 10:00:00 GMT"), fetcher.fetch(url(), null, null, force = false))
    }

    @Test fun sendsConditionalHeaders() {
        server.enqueue(MockResponse().setResponseCode(304))
        fetcher.fetch(url(), "\"v1\"", "Wed, 25 Nov 2026 10:00:00 GMT", force = false)
        val req = server.takeRequest()
        assertEquals("\"v1\"", req.getHeader("If-None-Match"))
        assertEquals("Wed, 25 Nov 2026 10:00:00 GMT", req.getHeader("If-Modified-Since"))
    }

    @Test fun forceOmitsConditionalHeaders() {
        server.enqueue(MockResponse().setBody(ics))
        fetcher.fetch(url(), "\"v1\"", "Wed, 25 Nov 2026 10:00:00 GMT", force = true)
        val req = server.takeRequest()
        assertNull(req.getHeader("If-None-Match"))
        assertNull(req.getHeader("If-Modified-Since"))
    }

    @Test fun notModified() {
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(FetchResult.NotModified, fetcher.fetch(url(), "\"v1\"", null, force = false))
    }

    @Test fun htmlBodyIsFailed() {
        server.enqueue(MockResponse().setBody("<html>Sign in</html>").setHeader("Content-Type", "text/html"))
        val r = fetcher.fetch(url(), null, null, force = false)
        assertTrue(r is FetchResult.Failed)
    }

    @Test fun http500IsFailedWithoutLeakingPath() {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = fetcher.fetch(url(), null, null, force = false) as FetchResult.Failed
        assertTrue(r.message, "500" in r.message)
        assertFalse(r.message, "secret-token" in r.message)
    }

    @Test fun webcalRewritten() {
        assertEquals("https://example.com/a.ics", normalizeUrl("webcal://example.com/a.ics"))
        assertEquals("https://example.com/a.ics", normalizeUrl("webcals://example.com/a.ics"))
        assertEquals("https://example.com/a.ics", normalizeUrl("  https://example.com/a.ics "))
    }

    @Test fun redactHidesPath() =
        assertEquals("https://outlook.office365.com/…", redact("https://outlook.office365.com/owa/calendar/secret/calendar.ics"))
}
