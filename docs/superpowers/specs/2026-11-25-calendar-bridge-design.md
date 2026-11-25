# Calendar Bridge — design

An Android app that subscribes to iCalendar (`.ics`) URLs and syncs them into the
system calendar, like ICSx5, but with correct timezone handling for
Outlook/Exchange-published feeds.

## Intent and why

The user subscribes to an Outlook 365 published calendar
(`outlook.office365.com/owa/calendar/.../calendar.ics`) with ICSx5 and gets wrong
event times that cannot be fixed from ICSx5's settings.

Inspection of the real feed (147 events) shows why:

- Every timed event uses a **Windows timezone name** as TZID:
  `DTSTART;TZID=Pacific Standard Time` (105), `TZID=Eastern Standard Time` (33).
- The feed carries matching `VTIMEZONE` blocks, but in Outlook's minimal form:
  `DTSTART:16010101T020000` plus a single yearly RRULE per STANDARD/DAYLIGHT.
- 9 all-day events (`DTSTART;VALUE=DATE`).
- Many recurring events: `RRULE:FREQ=WEEKLY;UNTIL=...Z;INTERVAL=n;BYDAY=..`, plus
  `EXDATE`/`RECURRENCE-ID` exceptions as Outlook produces them.

Android's `CalendarContract` only understands IANA zone IDs (`America/Los_Angeles`).
An unrecognized TZID ends up stored as a fixed offset, UTC or the device zone, so
times are wrong, and recurring events drift by an hour across DST transitions.

**Success:** every event from the user's Outlook feed shows up in the Android
calendar (Google Calendar etc.) at the same wall-clock time Outlook shows,
including recurring events on both sides of a DST change and all-day events on
the right day, and it stays correct after background syncs.

## Goals

- Subscribe to one or more ICS URLs (http/https, including `webcal://`, which is rewritten to `https://`).
- Each subscription becomes its own read-only Android calendar, visible in any
  calendar app.
- Correct timezone resolution (see below) as the core feature.
- Background sync on an interval, plus manual "Sync now".
- Recurring events and their exceptions handled correctly.

## Non-goals

- Two-way sync / editing events (calendars are read-only).
- CalDAV, authentication beyond credentials embedded in the URL (Basic auth can be added later).
- Play Store publishing; this is sideloaded via adb for one user.
- Importing local `.ics` files.
- Widgets, notifications other than the system calendar's own reminders.

## Anti-goals (failure even if it "works")

- Any event shown at a different wall-clock time than in Outlook.
- Duplicate events after repeated syncs.
- Draining battery or data (re-downloading/rewriting everything every few minutes).
- Silently dropping events it fails to parse (must be logged and counted, and visible
  as an error on the subscription).

## Constraints

- Device: user's own Android phone, reachable over adb wireless debugging. Android
  version unknown until connected; target minSdk 26 (java.time available).
- Mac has Android SDK, adb, Java, Gradle installed.
- Feed URL contains a secret token: never logged in full, never committed to a repo,
  never used as a test fixture. Test fixtures are synthetic, modeled on its shape.
- Coexists with ICSx5 on the device; while both subscribe to the same feed the
  user will see duplicates. The user disables ICSx5's subscription once Calendar Bridge is
  verified.

## Approaches considered

1. **Native ICSx5-style app (chosen).** Kotlin app with its own account, writes
   events into `CalendarContract`, background sync via WorkManager, timezones fixed
   during parsing. Self-contained on the phone; no server.
2. **Same app scoped to a single hard-coded feed.** Slightly less UI, but adding a
   second calendar needs a code change. Rejected: the multi-subscription UI is small.
3. **Rewriting proxy + keep ICSx5.** A service rewrites TZIDs to IANA and ICSx5
   subscribes to the proxy. Least code, but needs an always-on host reachable from
   the phone (the Mac sleeps). Rejected.

User picked approach 1.

## Design

### Tech stack (builder's calls, stated)

- Kotlin, Gradle (Kotlin DSL), Jetpack Compose + Material 3 for UI.
- minSdk 26, targetSdk/compileSdk 35.
- WorkManager for scheduling, OkHttp for HTTP, Room (or DataStore) for subscription
  settings.
- **ICS parsing: [biweekly](https://github.com/mangstadt/biweekly)** (user's choice
  after library research). Mature, lightweight, Android-compatible; handles line
  folding, escaping and parameter quoting. We read each date's raw local value and
  its original TZID string from biweekly (its timezone assignment), and do the TZID
  resolution ourselves; biweekly's own VTIMEZONE-based conversion is not trusted
  for Outlook's 1601-style definitions except as input to step 4 below.
  Rejected: ical4j (heavy on Android, base of ICSx5), ical4android/synctools (the
  layer that mis-handles these feeds; GPL), a hand-written parser (more edge cases
  to own). Recurrence expansion is *not* done by us: RRULE/EXDATE/RDATE are passed
  to `CalendarContract`, which expands them.
- **Windows timezone names: Android's built-in ICU**,
  `android.icu.util.TimeZone.getIDForWindowsID(winId, null)` (API 24+). No embedded
  CLDR table; the mapping updates with the OS. JVM unit tests use the `icu4j`
  artifact (same API under `com.ibm.icu.util`) behind a small interface.
- App id: `dev.jhadenfeldt.calendarbridge`, display name "Calendar Bridge".

### Account and calendars

- Register a custom account type (`dev.jhadenfeldt.calendarbridge.account`) via a stub
  `AbstractAccountAuthenticator`, and a sync adapter stub so the system treats the
  calendars as owned by a sync adapter. One account ("Calendar Bridge").
- One `Calendars` row per subscription: `ACCOUNT_TYPE`=our type,
  `CALENDAR_ACCESS_LEVEL=CAL_ACCESS_READ`, `SYNC_EVENTS=1`, `VISIBLE=1`,
  display name and color from the subscription, `CALENDAR_TIME_ZONE` = device zone.
- All writes use the sync-adapter URI (`CALLER_IS_SYNCADAPTER=true`).
- Runtime permissions: `READ_CALENDAR`, `WRITE_CALENDAR` (requested on first add).
  `INTERNET`, `ACCESS_NETWORK_STATE`. `POST_NOTIFICATIONS` not needed.

### Timezone resolution (the core)

For each TZID referenced by a DTSTART/DTEND/EXDATE/RECURRENCE-ID, resolve to an IANA
ID in this order and cache per feed:

1. **Already IANA:** `ZoneId.of(tzid)` succeeds and it's a region ID → use it.
2. **Windows name:** `getIDForWindowsID` (ICU's copy of CLDR `windowsZones`,
   territory `001`), e.g. `Pacific Standard Time` → `America/Los_Angeles`,
   `Eastern Standard Time` → `America/New_York`, `W. Europe Standard Time` →
   `Europe/Berlin`. Lookup is case-insensitive, trimmed, and tolerant of surrounding
   quotes.
3. **Outlook/Exchange decorated names:** strip known wrappers
   (`tzone://Microsoft/`, `/Microsoft/`, `(UTC-08:00) Pacific Time (US & Canada)` style
   display names → match via CLDR display-name table or offset rules), then retry 1–2.
4. **Match the VTIMEZONE definition:** compute the STANDARD/DAYLIGHT offsets and
   transition rules from the feed's own VTIMEZONE and pick an IANA zone whose rules
   produce the same offsets for the current and next year (prefer the device zone if
   it matches, then zones in the same CLDR territory set).
5. **Fallback:** use a fixed-offset zone (`GMT-08:00`) built from the VTIMEZONE's
   standard offset (or treat as floating in the device zone if there is no VTIMEZONE)
   and record a warning on the subscription naming the TZID. The event is kept, not
   skipped. Recurring events in this case may be an hour off across DST, which is
   why the warning is shown prominently. *(Builder's call: steps 2–4 cover every
   TZID seen in practice from Outlook, so this path should be rare and visible
   rather than silently wrong.)*

Value handling:

- `DTSTART;VALUE=DATE` (all-day): store `ALL_DAY=1`, `EVENT_TIMEZONE=UTC`,
  DTSTART/DTEND at UTC midnight. DTEND exclusive as per RFC; if missing, +1 day.
- `...Z` (UTC): `EVENT_TIMEZONE=UTC`.
- Floating (no TZID, no Z): interpret in device zone (`EVENT_TIMEZONE` = device zone).
  (The user's feed has none.)
- Events keep their real zone, so the stored *moment* is correct wherever the phone
  is; the on-device check compares moments (UTC), and wall-clock in the event's
  zone, not the phone's.
- `EVENT_END_TIMEZONE` set from DTEND's resolved zone.

The VTIMEZONE's 1601 DTSTART is irrelevant once a TZID maps to IANA; it is only used
in step 4/5.

### Event mapping

| ICS | CalendarContract.Events |
|---|---|
| UID (+ RECURRENCE-ID) | `_SYNC_ID`: UID for masters; exceptions get `_SYNC_ID`=`UID` + `#` + instance time (UTC ms) and `ORIGINAL_SYNC_ID`=UID. In every batch masters are inserted/updated before their exceptions. |
| SUMMARY / DESCRIPTION / LOCATION | `TITLE` / `DESCRIPTION` / `EVENT_LOCATION` |
| DTSTART / DTEND / DURATION | `DTSTART`, `DTEND` (non-recurring) or `DURATION` with `DTEND` null (recurring: `P<n>S` for timed, `P<n>D` for all-day) |
| RRULE / RDATE / EXDATE | `RRULE` passed through; `RDATE` and `EXDATE` values converted to UTC `yyyyMMdd'T'HHmmss'Z'`, comma-joined (date-only values stay `yyyyMMdd` for all-day series). Android's recurrence parser does not understand Windows TZIDs, so nothing with a TZID parameter is passed through raw. |
| RECURRENCE-ID | separate exception row: `ORIGINAL_SYNC_ID`, `ORIGINAL_INSTANCE_TIME` (ms UTC), `ORIGINAL_ALL_DAY` |
| STATUS:CANCELLED on exception | exception row with `STATUS=STATUS_CANCELED` |
| TRANSP / X-MICROSOFT-CDO-BUSYSTATUS | `AVAILABILITY` (busy/free/tentative) |
| CLASS | `ACCESS_LEVEL` |
| VALARM (DISPLAY, relative TRIGGER) | `Reminders` rows; plus optional per-subscription default reminder |

RRULE `UNTIL` values with `Z` are passed unchanged; date-only UNTIL is left as is.

### Sync algorithm

1. GET the URL with `If-None-Match`/`If-Modified-Since` from the last sync. 304 →
   done, update "last checked".
2. Parse; on parse failure of the whole document → mark subscription error, change
   nothing in the calendar.
3. For each VEVENT compute a content hash over the normalized fields we store
   (after timezone resolution). Store the hash in `SYNC_DATA1`.
4. Diff against existing rows of that calendar keyed by `_SYNC_ID` (+ original
   instance time for exceptions): insert new, update rows whose hash changed
   (replace reminders), delete rows no longer present. The calendar mirrors the feed
   exactly: events that leave Outlook's rolling window (currently one year back to
   ~14 months ahead) are deleted too. *(User's decision.)* Apply via
   `ContentProviderOperation` batches.
5. Record last sync time, event count, warnings (unresolved TZIDs, skipped events).

A change in our mapping code (version bump of a `MAPPING_VERSION` constant
folded into the hash) forces a full rewrite once, so fixes apply to existing events.

### Scheduling

- WorkManager unique periodic work per subscription, network-required constraint.
  Interval per subscription: 15 min / 1 h (default) / 6 h / 24 h / manual only.
- "Sync now" button enqueues a one-time work.
- **"Force sync"** (per subscription and "Force sync all" in the overflow menu):
  ignores ETag/Last-Modified and the stored hashes, re-downloads the feed and
  rewrites every event of that calendar (delete-all + reinsert in one batch).
  For when the calendar looks wrong and the user wants a clean slate. *(User's request.)*
- The system sync adapter's `onPerformSync` (e.g. pull-to-refresh in Google
  Calendar) also triggers a sync of all subscriptions.

### UI

Single activity, Compose:

```
Calendar Bridge                                 [Sync all]
┌──────────────────────────────────────────────┐
│ ● Work (Outlook)            synced 10:42     │
│   147 events · 1 h                           │
│   ⚠ 0 warnings                               │
└──────────────────────────────────────────────┘
                                          ( + )
```

- Tap a subscription → edit screen: name, URL (shown masked after save), color,
  interval, default reminder (none/5/10/15/30/60 min), "Sync now", "Force sync", "Delete"
  (removes the calendar and its events), and a "Details" section listing warnings
  and the TZID → IANA mapping used for this feed.
- "+" → add screen with URL field; name is prefilled from `X-WR-CALNAME` after a
  test fetch; validation fetch happens before saving.

### Diagnostics

- A "Details" list per subscription: resolved TZID mappings, count of events per
  zone, skipped events with reason.
- Logcat tag `CalBridge`; URL logged with path redacted.

### Testing

- During development the real feed may be fetched read-only on the Mac (kept in a
  temp dir outside the repo, never committed) to run the parser over all 147 events
  before installing on the phone. Done that way already during design.

- JVM unit tests: line unfolding/escaping, Windows→IANA mapping, VTIMEZONE matching,
  all-day handling, EXDATE conversion, RECURRENCE-ID exceptions, hash stability,
  diffing. Fixtures are synthetic ICS files modeled on the Outlook feed's shape.
- Key regression test: a weekly event `TZID=Pacific Standard Time` spanning the
  2026-11-01 DST end must keep the same Pacific wall-clock time before and after.
- On-device check via adb: install, add the real feed, query `content://com.android.calendar/instances`
  for a sample of events and compare wall-clock times against the raw feed
  interpreted in the IANA zone; spot-check in the Google Calendar app with the user.

## Left to the builder

- Exact project structure/module split, DI approach (none needed; keep it simple).
- Room vs DataStore for subscription storage.
- App icon (simple generated vector).
