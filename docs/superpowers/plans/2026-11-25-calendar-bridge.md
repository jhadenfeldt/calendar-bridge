# Calendar Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An Android app that subscribes to `.ics` URLs and mirrors them into read-only system calendars with correct timezones for Outlook feeds.

**Architecture:** Pure-Kotlin core (parsing with biweekly, TZID resolution, event mapping, diff planning) with no Android imports, so it is unit-tested on the JVM. A thin Android layer writes plans into `CalendarContract` as a sync adapter, schedules syncs with WorkManager, and shows a Compose UI.

**Tech Stack:** Same toolchain as the user's `jhadenfeldt/ringer` app (which already releases from GitHub Actions): Kotlin 2.1.20, Android Gradle Plugin 8.11.1, Gradle wrapper 8.13, Java 17 target (CI: Temurin 17; locally the Android Studio JBR 21), compileSdk/targetSdk 36, minSdk 26, Jetpack Compose + Material 3, WorkManager, OkHttp 4.12, biweekly 0.6.8, icu4j 77.1 (JVM tests only), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-25-calendar-bridge-design.md`

## Global Constraints

- applicationId and namespace: `dev.jhadenfeldt.calendarbridge`; display name "Calendar Bridge".
- Account type: `dev.jhadenfeldt.calendarbridge.account`; one account named "Calendar Bridge".
- minSdk 26. Local builds use `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`.
- Package `dev.jhadenfeldt.calendarbridge.core` must not import `android.*` (enforced by it compiling in JVM unit tests).
- Calendars are read-only (`CAL_ACCESS_READ`); all provider writes use the sync-adapter URI (`CALLER_IS_SYNCADAPTER=true`, account name/type query params).
- The real feed URL and its contents are never committed, logged in full, or used as a fixture. Logs redact the URL path (`https://outlook.office365.com/…`). Logcat tag `CalBridge`.
- Mirror semantics: events missing from the feed are deleted.
- Git: work on `feat/initial-app`. **Never commit without the user saying "commit it"**; every "Commit" step below means: stage the files and ask.

## Review Focus

1. DTSTART and DTEND in different TZIDs → `eventTimezone` and `eventEndTimezone` resolved independently (test in Task 3).
2. A `RECURRENCE-ID` exception whose master UID is not in the feed → stored as a standalone event, not an orphan exception (test in Task 3).
3. Server returns 200 with HTML (expired link / login page) → sync fails with an error, calendar untouched (test in Task 5).
4. Same UID (no RECURRENCE-ID) appears twice → last one wins, one warning (test in Task 3).
5. "Force sync" pressed while a periodic sync for the same subscription runs → never two concurrent writers for one calendar (unique work name per subscription, Task 6 test).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew…
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/xml/authenticator.xml, syncadapter.xml
app/src/main/java/dev/jhadenfeldt/calendarbridge/
  core/Model.kt              EventRow, ParseResult, Warning, constants
  core/WindowsZoneLookup.kt  interface
  core/TimezoneResolver.kt   TZID → ZoneId (steps 1–5)
  core/VTimezoneRules.kt     offsets/transition rules extracted from a VTIMEZONE
  core/IcsParser.kt          biweekly → List<EventRow> + warnings
  core/SyncPlanner.kt        hash + diff → SyncPlan
  android/IcuWindowsZoneLookup.kt
  android/CalendarStore.kt   Calendars/Events/Reminders provider access
  android/account/AuthenticatorService.kt, SyncAdapterService.kt
  data/Subscription.kt, SubscriptionRepository.kt (SharedPreferences + org.json)
  sync/FeedFetcher.kt        OkHttp, conditional GET, webcal rewrite, redaction
  sync/SyncEngine.kt         fetch → parse → plan → apply → status
  sync/SyncWorker.kt, SyncScheduler.kt
  ui/MainActivity.kt, ui/SubscriptionListScreen.kt, ui/EditSubscriptionScreen.kt
app/src/test/java/dev/jhadenfeldt/calendarbridge/…   JVM tests
app/src/test/resources/outlook-sample.ics            synthetic fixture
app/src/androidTest/java/…/CalendarStoreTest.kt       on-device test
```

---

### Task 1: Project scaffold

**Files:** Create root Gradle files, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `ui/MainActivity.kt` (Compose "Calendar Bridge" text), `.gitignore` (Android standard + `local.properties`, `*.ics` outside `src/test/resources`), `app/src/test/.../SmokeTest.kt`.

**Interfaces:** Produces the module `:app`, package root `dev.jhadenfeldt.calendarbridge`.

- [ ] **Step 1:** Copy ringer's Gradle layout (`gh api repos/jhadenfeldt/ringer/contents/<path>`): `settings.gradle.kts` (rootProject.name = "CalendarBridge"), root `build.gradle.kts`, `gradle.properties`, `.gitignore` (+ `*.ics` outside `src/test/resources`), `gradle/wrapper/*` and `gradlew`/`gradlew.bat` (Gradle 8.13), `app/proguard-rules.pro`.
- [ ] **Step 2:** Start from ringer's `gradle/libs.versions.toml` and `app/build.gradle.kts` (same plugins `android.application`, `kotlin.android`, `kotlin.compose`; same versions; same `versionName`/`versionCode` Gradle properties defaulting to `0.1.0`/`1`; same env-based release signing, renamed `CALBRIDGE_KEYSTORE`, `CALBRIDGE_KEYSTORE_PASSWORD`, `CALBRIDGE_KEY_ALIAS`, `CALBRIDGE_KEY_PASSWORD`; `isMinifyEnabled = true`). Add: material-icons-extended, work-runtime-ktx, okhttp 4.12.0, `net.sf.biweekly:biweekly:0.6.8`; test deps junit 4.13.2, `com.ibm.icu:icu4j:77.1`, `com.squareup.okhttp3:mockwebserver:4.12.0`; androidTest deps `androidx.test.ext:junit`, `androidx.test:runner`, `androidx.test:rules`. `testOptions.unitTests.isReturnDefaultValues = true`.
- [ ] **Step 3:** `proguard-rules.pro`: `-keep class biweekly.** { *; }` and `-dontwarn com.fasterxml.jackson.**` (biweekly's optional JSON support), `-dontwarn org.slf4j.**`.
- [ ] **Step 4:** `SmokeTest.assertTrue(true)`; run `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` → BUILD SUCCESSFUL (release unsigned locally is fine).
- [ ] **Step 5: Commit** `chore: scaffold Android project` (after "commit it").

### Task 2: Timezone resolution

**Files:** Create `core/WindowsZoneLookup.kt`, `core/VTimezoneRules.kt`, `core/TimezoneResolver.kt`, `android/IcuWindowsZoneLookup.kt`; Test `core/TimezoneResolverTest.kt`, `test/.../Icu4jWindowsZoneLookup.kt`.

**Interfaces:**
- `fun interface WindowsZoneLookup { fun ianaFor(windowsId: String): String? }` — Android impl calls `android.icu.util.TimeZone.getIDForWindowsID(id, null)`; test impl calls `com.ibm.icu.util.TimeZone.getIDForWindowsID(id, null)`.
- `data class Observance(val offsetTo: ZoneOffset, val month: Int, val weekOfMonth: Int /* 1..5, -1 = last */, val dayOfWeek: DayOfWeek, val localTime: LocalTime)`
- `data class VTimezoneRules(val tzid: String, val standard: Observance?, val daylight: Observance?)` — `standard == null` means "no rule", `daylight == null` means no DST.
- `sealed interface Resolution { data class Zone(val zoneId: ZoneId, val via: String /* "iana"|"windows"|"decorated"|"vtimezone" */) ; data class FixedOffset(val offset: ZoneOffset) ; object Floating }`
- `class TimezoneResolver(private val windows: WindowsZoneLookup, private val deviceZone: ZoneId) { fun resolve(tzid: String?, vtimezone: VTimezoneRules?): Resolution }` — results cached per instance (one instance per sync).

- [ ] **Step 1: Write failing tests** in `TimezoneResolverTest` (use `Icu4jWindowsZoneLookup`, deviceZone `Europe/Berlin`):
  - `ianaPassesThrough`: `resolve("America/Chicago", null)` == `Zone(America/Chicago, "iana")`.
  - `windowsPacific`: `"Pacific Standard Time"` → `America/Los_Angeles`, via `"windows"`; `"Eastern Standard Time"` → `America/New_York`; `"W. Europe Standard Time"` → `Europe/Berlin`.
  - `windowsIsTrimmedQuotedCaseInsensitive`: `"\"pacific standard time \""` → `America/Los_Angeles`.
  - `decoratedNames`: `"tzone://Microsoft/Pacific Standard Time"` and `"/Microsoft/Pacific Standard Time"` → `America/Los_Angeles`, via `"decorated"`.
  - `vtimezoneMatch`: unknown tzid `"Custom Zone 1"` with rules std −08:00 (Nov, 1st SU, 02:00) / dst −07:00 (Mar, 2nd SU, 02:00) → a `Zone` whose rules give −08:00 on 2027-01-15 and −07:00 on 2027-07-15 and switch on 2026-11-01 and 2027-03-14; via `"vtimezone"`.
  - `vtimezonePrefersDeviceZone`: resolver with deviceZone `America/Los_Angeles` and the rules above → exactly `America/Los_Angeles`.
  - `noMatchFallsBackToFixedOffset`: tzid `"Mars Time"`, rules std +05:17 no DST → `FixedOffset(+05:17)`.
  - `noTzid`: `resolve(null, null)` == `Floating`.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*TimezoneResolverTest'` → FAIL (unresolved references).
- [ ] **Step 3: Implement** `TimezoneResolver.resolve`. Order: normalize (trim, strip surrounding quotes) → (1) `ZoneId.of` succeeds and id contains `/` or equals `UTC` → iana; (2) `windows.ianaFor(id)`; if null and the id differs from its word-capitalized form, retry with each word capitalized (`"pacific standard time"` → `"Pacific Standard Time"`), since ICU lookup is case-sensitive; (3) strip prefixes `tzone://Microsoft/`, `/Microsoft/`, then retry 1–2 → via "decorated"; (4) if `vtimezone != null`, candidates = `ZoneId.getAvailableZoneIds()` filtered to ids containing `/` and not starting with `Etc/`, `SystemV/`; deviceZone first, rest sorted alphabetically; first candidate matching (see algorithm) wins; (5) `vtimezone?.standard?.offsetTo` → `FixedOffset`, else `daylight?.offsetTo`, else `Floating`.

  Matching algorithm for step 4 (years Y = current year and Y+1):
  ```
  for each year: for each observance o in (standard, daylight) non-null:
      local = o.month/weekOfMonth/dayOfWeek in year at o.localTime   // "last" when weekOfMonth == -1
      other = the other observance (or o itself if no DST)
      instant = local at other.offsetTo
      require zone.rules.getOffset(instant - 2h) == other.offsetTo
      require zone.rules.getOffset(instant + 2h) == o.offsetTo
  if daylight == null: require offset at Jan 15 and Jul 15 of both years == standard.offsetTo
  ```
- [ ] **Step 4:** Implement `IcuWindowsZoneLookup` (one line) and `Icu4jWindowsZoneLookup` (test sources).
- [ ] **Step 5:** Run tests → PASS.
- [ ] **Step 6: Commit** `feat(core): TZID resolution with ICU Windows mapping and VTIMEZONE matching`.

### Task 3: Parsing and event mapping

**Files:** Create `core/Model.kt`, `core/IcsParser.kt`; Test `core/IcsParserTest.kt`, `src/test/resources/outlook-sample.ics`.

**Interfaces:**
- Consumes: `TimezoneResolver`, `VTimezoneRules`, `Resolution` (Task 2).
- Produces (`core/Model.kt`):
  ```kotlin
  data class EventRow(
      val syncId: String, val originalSyncId: String? = null,
      val originalInstanceTime: Long? = null, val originalAllDay: Boolean? = null,
      val title: String, val description: String?, val location: String?,
      val dtStart: Long, val dtEnd: Long?, val duration: String?,
      val allDay: Boolean, val eventTimezone: String, val eventEndTimezone: String?,
      val rrule: String?, val rdate: String?, val exdate: String?,
      val status: Int?, val availability: Int, val accessLevel: Int,
      val reminderMinutes: List<Int>,
  )
  data class ParseResult(val calendarName: String?, val events: List<EventRow>, val warnings: List<String>, val tzidMappings: Map<String, String>)
  object Cal { const val STATUS_CANCELED = 2; const val AVAIL_BUSY = 0; const val AVAIL_FREE = 1; const val AVAIL_TENTATIVE = 2
               const val ACCESS_DEFAULT = 0; const val ACCESS_CONFIDENTIAL = 1; const val ACCESS_PRIVATE = 2; const val ACCESS_PUBLIC = 3 }
  class IcsParseException(message: String) : Exception(message)
  ```
  (Constants mirror `CalendarContract` values; Task 4 asserts they match.)
- `class IcsParser(private val resolver: TimezoneResolver) { fun parse(text: String): ParseResult }` — throws `IcsParseException` if the text contains no `BEGIN:VCALENDAR` or biweekly yields no calendar.

Mapping rules (from spec): `_SYNC_ID` = UID for masters; exceptions `"$uid#$originalInstanceTimeMs"`, `originalSyncId = uid`. Timed values: local components from biweekly `ICalDate.getRawComponents()` + resolved zone → epoch ms; `eventTimezone` = zone id, FixedOffset → `"GMT±hh:mm"`, Floating → device zone; `Z` values → `"UTC"`. All-day: UTC midnight, `eventTimezone = "UTC"`, missing DTEND → +1 day. Non-recurring: `dtEnd` set, `duration` null. Recurring (has RRULE or RDATE): `dtEnd` null, `duration` = `"P${seconds}S"` timed / `"P${days}D"` all-day. RRULE text written by biweekly's `RecurrenceRuleScribe` (V2_0) unchanged. EXDATE/RDATE: each value → UTC `yyyyMMdd'T'HHmmss'Z'` (all-day → `yyyyMMdd`), comma-joined. Availability: `X-MICROSOFT-CDO-BUSYSTATUS` FREE/TENTATIVE/BUSY|OOF, else TRANSP TRANSPARENT→FREE, else BUSY. CLASS PUBLIC/PRIVATE/CONFIDENTIAL → access level, else DEFAULT. VALARM DISPLAY/AUDIO with relative negative TRIGGER → minutes. Warnings: unresolved TZID (FixedOffset), duplicate UID, skipped event (missing DTSTART), each with UID.

- [ ] **Step 1: Write fixture** `outlook-sample.ics`: Outlook-shaped (`PRODID:Microsoft Exchange Server 2010`, `X-WR-CALNAME:Work`, both 1601-style VTIMEZONEs for Pacific/Eastern copied from the spec's description), events:
  `weekly` (TZID=Pacific Standard Time, DTSTART 20261019T093000, DTEND 20261019T100000, `RRULE:FREQ=WEEKLY;UNTIL=20271115T173000Z;INTERVAL=1;BYDAY=MO`, `EXDATE;TZID=Pacific Standard Time:20261026T093000`),
  `weekly-ex` (same UID, `RECURRENCE-ID;TZID=Pacific Standard Time:20261102T093000`, DTSTART 20261102T110000, DTEND 20261102T113000),
  `eastern-single` (TZID=Eastern Standard Time 20261203T140000–150000, `X-MICROSOFT-CDO-BUSYSTATUS:TENTATIVE`, `CLASS:PRIVATE`, VALARM TRIGGER:-PT15M),
  `allday` (`DTSTART;VALUE=DATE:20261224`, `DTEND;VALUE=DATE:20261225`, `TRANSP:TRANSPARENT`),
  `utc` (DTSTART 20261201T120000Z, DTEND 20261201T130000Z),
  `mixed-tz` (DTSTART;TZID=Pacific Standard Time 20261210T080000, DTEND;TZID=Eastern Standard Time 20261210T120000),
  `orphan-ex` (UID `orphan`, only a RECURRENCE-ID instance),
  `dup` twice (second has SUMMARY "Dup v2"),
  `cancelled-ex` (exception of `weekly` on 20261109 with `STATUS:CANCELLED`).
- [ ] **Step 2: Write failing tests** in `IcsParserTest` (resolver with deviceZone `Europe/Berlin`):
  - `calendarName` == "Work".
  - `weeklyKeepsPacificWallClockAcrossDstEnd`: master `dtStart` == `2026-10-19T09:30-07:00` epoch ms, `eventTimezone == "America/Los_Angeles"`, `dtEnd == null`, `duration == "P1800S"`, `rrule == "FREQ=WEEKLY;UNTIL=20271115T173000Z;INTERVAL=1;BYDAY=MO"`, `exdate == "20261026T163000Z"`. Also: `ZonedDateTime.of(2026,11,9,9,30,0,0, LA)` (after DST end) has the same local time as master start in LA — i.e. the stored zone, not an offset, carries DST.
  - `exceptionRow`: `syncId == "weekly#" + epoch(2026-11-02T09:30-08:00)`, `originalSyncId == "weekly"`, `originalInstanceTime` same epoch, `dtStart == epoch(2026-11-02T11:00-08:00)`, `dtEnd` set, `rrule == null`.
  - `cancelledException`: `status == Cal.STATUS_CANCELED`.
  - `easternSingle`: `eventTimezone == "America/New_York"`, `availability == AVAIL_TENTATIVE`, `accessLevel == ACCESS_PRIVATE`, `reminderMinutes == listOf(15)`.
  - `allDay`: `allDay`, `dtStart == epoch(2026-12-24T00:00Z)`, `dtEnd == epoch(2026-12-25T00:00Z)`, `eventTimezone == "UTC"`, `availability == AVAIL_FREE`.
  - `utc`: `eventTimezone == "UTC"`.
  - `mixedTz` (Review Focus 1): `eventTimezone == "America/Los_Angeles"`, `eventEndTimezone == "America/New_York"`, `dtEnd == epoch(2026-12-10T12:00-05:00)`.
  - `orphanException` (Review Focus 2): row with `syncId == "orphan"`, `originalSyncId == null`.
  - `duplicateUid` (Review Focus 4): exactly one row `"dup"`, title "Dup v2", one warning containing "dup".
  - `tzidMappings == mapOf("Pacific Standard Time" to "America/Los_Angeles", "Eastern Standard Time" to "America/New_York")`.
  - `notIcsThrows`: `parse("<html>login</html>")` throws `IcsParseException`.
- [ ] **Step 3:** Run → FAIL.
- [ ] **Step 4: Implement** `IcsParser.parse`. Build `VTimezoneRules` from each biweekly `VTimezone` (first STANDARD/DAYLIGHT observance's `TZOFFSETTO` and RRULE BYMONTH/BYDAY). For each property's TZID use `ical.timezoneInfo.getTimezone(property)` to get the original TZID string (biweekly moves it off the parameter). Orphan exceptions become masters with `syncId = uid`.
- [ ] **Step 5:** Run → PASS.
- [ ] **Step 6: Commit** `feat(core): parse Outlook feeds with biweekly into calendar rows`.

### Task 4: Sync planner

**Files:** Create `core/SyncPlanner.kt`; Test `core/SyncPlannerTest.kt`.

**Interfaces:**
- `const val MAPPING_VERSION = 1`
- `fun EventRow.contentHash(): String` — SHA-256 hex of `MAPPING_VERSION` + all fields in declaration order (`toString()` of the data class is acceptable as input).
- `data class ExistingRow(val id: Long, val syncId: String, val hash: String?)`
- `data class SyncPlan(val inserts: List<EventRow>, val updates: List<Pair<Long, EventRow>>, val deletes: List<Long>)` — `inserts` and `updates` ordered masters (originalSyncId == null) before exceptions.
- `object SyncPlanner { fun plan(existing: List<ExistingRow>, incoming: List<EventRow>, force: Boolean): SyncPlan }`

- [ ] **Step 1: Failing tests:** `newEventsInserted`; `unchangedSkipped` (same hash → nothing); `changedUpdated` (keeps existing id); `missingDeleted` (mirror); `forceRewritesAll` (force → every existing id in deletes, every incoming in inserts); `mastersBeforeExceptions` (incoming order exception, master → inserts order master, exception); `hashChangesWithMappingVersion` is not testable without changing the const — instead `hashStableAcrossCalls` and `hashDiffersWhenTitleDiffers`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(core): diff planner with mirror semantics and force mode`.

### Task 5: Fetching and subscriptions

**Files:** Create `sync/FeedFetcher.kt`, `data/Subscription.kt`, `data/SubscriptionRepository.kt`; Test `sync/FeedFetcherTest.kt` (MockWebServer).

**Interfaces:**
- `data class Subscription(val id: String /* UUID */, val url: String, val name: String, val color: Int, val intervalMinutes: Int /* 0 = manual; 15, 60, 360, 1440 */, val defaultReminderMinutes: Int? , val calendarId: Long?, val etag: String?, val lastModified: String?, val lastSyncAt: Long?, val lastError: String?, val eventCount: Int, val warnings: List<String>, val tzidMappings: Map<String,String>)`; defaults: interval 60, reminder null.
- `class SubscriptionRepository(context: Context) { fun all(): List<Subscription>; fun get(id: String): Subscription?; fun upsert(s: Subscription); fun delete(id: String); val changes: StateFlow<List<Subscription>> }` — SharedPreferences `"subscriptions"`, one JSON array via `org.json`.
- `sealed interface FetchResult { data class Ok(val body: String, val etag: String?, val lastModified: String?) : FetchResult; object NotModified : FetchResult; data class Failed(val message: String) : FetchResult }`
- `class FeedFetcher(private val client: OkHttpClient = OkHttpClient()) { fun fetch(url: String, etag: String?, lastModified: String?, force: Boolean): FetchResult }` (blocking; called from worker thread).
- `fun normalizeUrl(url: String): String` (`webcal://` → `https://`, `webcals://` → `https://`), `fun redact(url: String): String` (scheme + host + `/…`).

- [ ] **Step 1: Failing tests:** `sendsConditionalHeaders` (etag/lastModified → `If-None-Match`/`If-Modified-Since`); `forceOmitsConditionalHeaders`; `304IsNotModified`; `htmlBodyIsFailed` (Review Focus 3: 200 with `Content-Type: text/html` and no `BEGIN:VCALENDAR` → `Failed`); `http500IsFailed` with message containing "500" and not containing the URL path; `webcalRewritten`; `redactHidesPath` (`redact("https://outlook.office365.com/owa/calendar/secret/calendar.ics") == "https://outlook.office365.com/…"`).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5: Commit** `feat(sync): feed fetcher and subscription storage`.

### Task 6: Calendar provider, account, sync engine, scheduling

**Files:** Create `android/CalendarStore.kt`, `android/account/AuthenticatorService.kt`, `android/account/SyncAdapterService.kt`, `res/xml/authenticator.xml`, `res/xml/syncadapter.xml`, `sync/SyncEngine.kt`, `sync/SyncWorker.kt`, `sync/SyncScheduler.kt`; modify `AndroidManifest.xml` (permissions `READ_CALENDAR`, `WRITE_CALENDAR`, `INTERNET`, `ACCESS_NETWORK_STATE`, `GET_ACCOUNTS`, `READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`, `AUTHENTICATE_ACCOUNTS`; services with `android.accounts.AccountAuthenticator` and `android.content.SyncAdapter` intent filters, `contentAuthority="com.android.calendar"`, `supportsUploading=false`, `userVisible=true`). Test: `androidTest/.../CalendarStoreTest.kt`, `test/.../CalConstantsTest.kt`.

**Interfaces:**
- `class CalendarStore(private val resolver: ContentResolver) { fun ensureAccount(context: Context): Account; fun createCalendar(account: Account, name: String, color: Int): Long; fun updateCalendar(id: Long, name: String, color: Int); fun deleteCalendar(account: Account, id: Long); fun existingRows(account: Account, calendarId: Long): List<ExistingRow>; fun apply(account: Account, calendarId: Long, plan: SyncPlan, defaultReminderMinutes: Int?) }` — stores hash in `Events.SYNC_DATA1`; reminders = event VALARMs, else default reminder if set; updates replace that event's reminders; operations batched ≤ 400 per `applyBatch`; exceptions resolve parent via `ORIGINAL_SYNC_ID`.
- `class SyncEngine(context: Context, repo: SubscriptionRepository, store: CalendarStore, fetcher: FeedFetcher) { fun sync(subscriptionId: String, force: Boolean): Result<Unit> }` — creates calendar if `calendarId == null` or row missing; on `NotModified` only updates `lastSyncAt`; on `Failed`/`IcsParseException` sets `lastError`, leaves calendar untouched; on success writes `eventCount`, `warnings`, `tzidMappings`, `etag`, `lastModified`, clears `lastError`. Logs with `redact(url)`.
- `object SyncScheduler { fun schedule(context, s: Subscription); fun cancel(context, id: String); fun syncNow(context, id: String, force: Boolean); fun syncAll(context, force: Boolean) }` — periodic name `"periodic-$id"` (`ExistingPeriodicWorkPolicy.UPDATE`, network constraint, skipped when interval 0); one-time name `"sync-$id"`; the worker itself runs under a per-subscription `Mutex` held in a companion `ConcurrentHashMap<String, Mutex>` so periodic and one-time never write concurrently (Review Focus 5); force uses `ExistingWorkPolicy.REPLACE`, normal uses `KEEP`.
- `SyncWorker(inputData: "id", "force")` → `SyncEngine.sync`; `SyncAdapterService.onPerformSync` → `SyncScheduler.syncAll(context, force = false)`.

- [ ] **Step 1: Failing JVM test** `CalConstantsTest`: `Cal.STATUS_CANCELED == CalendarContract.Events.STATUS_CANCELED` etc. — runs with `isReturnDefaultValues`; these are compile-time constants so they inline correctly.
- [ ] **Step 2: Failing instrumented test** `CalendarStoreTest` (requires calendar permissions granted with `GrantPermissionRule`): create calendar "CB Test"; apply a plan built by `IcsParser` from `outlook-sample.ics` (copy fixture into `androidTest/assets`); query `CalendarContract.Instances` for 2026-11-09 09:00–10:00 LA time → the weekly occurrence is absent (cancelled exception) and 2026-11-16 09:30 LA → present at that exact epoch; re-apply same plan → `existingRows` hashes unchanged and plan empty; delete calendar at the end.
- [ ] **Step 3:** Implement `CalendarStore`, account services, `SyncEngine`, `SyncWorker`, `SyncScheduler`.
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` → PASS. Instrumented test runs in Task 8 once the phone is connected (`./gradlew :app:connectedDebugAndroidTest`).
- [ ] **Step 5: Commit** `feat: write feeds into Android calendars via sync adapter`.

### Task 7: UI

**Files:** Create `ui/SubscriptionListScreen.kt`, `ui/EditSubscriptionScreen.kt`; modify `ui/MainActivity.kt`.

**Interfaces:** Consumes `SubscriptionRepository.changes`, `SyncScheduler`, `CalendarStore`, `FeedFetcher`, `IcsParser`.

- [ ] **Step 1:** List screen per spec sketch: color dot, name, "synced HH:mm" or error text in error color, "N events · interval", warning count; top bar actions "Sync all" and overflow "Force sync all"; FAB "+". Empty state text: "No calendars yet. Tap + to subscribe to an .ics URL."
- [ ] **Step 2:** Edit/add screen: URL field (masked via `redact` once saved), name (prefilled from `ParseResult.calendarName` after the validation fetch on "Add"), color picker (8 preset swatches), interval (Manual/15 min/1 h/6 h/24 h), default reminder (None/5/10/15/30/60), buttons "Sync now", "Force sync", "Delete" (confirm dialog "Delete calendar and all its events?"). Details section: TZID → IANA mappings, warnings list, last error.
- [ ] **Step 3:** Request `READ_CALENDAR`/`WRITE_CALENDAR` before the first add; if denied show "Calendar permission is needed to create calendars."
- [ ] **Step 4:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `feat(ui): subscription list and editor`.

### Task 8: On-device verification

**Files:** Create `app/src/test/.../RealFeedCheck.kt` (JVM test, `@Ignore`-free but `assumeTrue(System.getenv("CB_FEED_FILE") != null)`); nothing else committed.

- [ ] **Step 1:** Fetch the real feed into the session scratchpad (never the repo). Run `CB_FEED_FILE=<path> ./gradlew :app:testDebugUnitTest --tests '*RealFeedCheck'`: parses all events, asserts 0 warnings, every TZID mapped, event count == number of distinct masters + exceptions, and prints 10 sample events as `title-hash | local time in eventTimezone | UTC` for comparison (titles hashed, not printed).
- [ ] **Step 2:** Pair and connect the phone over wireless debugging (`adb pair <ip:port> <code>`, `adb connect <ip:port>`); `adb devices` shows it.
- [ ] **Step 3:** `./gradlew :app:connectedDebugAndroidTest` → PASS; `./gradlew :app:installDebug`. A debug-signed install cannot be updated by signed releases, so uninstall it before installing the first release APK from Task 9.
- [ ] **Step 4:** User adds the real feed in the app. Compare via `adb shell content query --uri content://com.android.calendar/instances/when/<begin>/<end> --projection title:begin:eventTimezone` for the two weeks around 2027-03-14 (DST start) against Step 1's expected times. Every instance matches.
- [ ] **Step 5:** User spot-checks in Google Calendar against Outlook, then disables the ICSx5 subscription.
- [ ] **Step 6: Commit** `test: real-feed check (env-gated)`.

### Task 9: Release process (copied from ringer)

**Files:** Create `.github/workflows/release.yml`, `docs/RELEASING.md`, `fastlane/metadata/android/en-US/changelogs/100.txt` ("First release: subscribe to .ics URLs with correct Outlook timezones."), `README.md` (what it is, install from GitHub Releases, link to RELEASING.md).

- [ ] **Step 1:** Copy ringer's `release.yml` and adapt: secret names `CALBRIDGE_KEYSTORE_BASE64`/`_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`, env `CALBRIDGE_KEYSTORE`, file names `calendar-bridge-<version>.apk`, release title `Calendar Bridge <version>`. Keep: tag `v*` + `workflow_dispatch` with optional version, versionCode = major·10000 + minor·100 + patch, prerelease for suffixed versions, refuse unsigned tagged builds, unit tests before build, print signing certificate, changelog from `fastlane/.../changelogs/<versionCode>.txt`. **Drop** Google Play: `bundleRelease`, `play/*.aab`, `HAS_PLAY_ACCOUNT`, and the upload step (spec non-goal: sideloaded only).
- [ ] **Step 2:** Copy ringer's `docs/RELEASING.md`, adapted the same way, without the Google Play and screenshots sections.
- [ ] **Step 3:** Validate: `actionlint .github/workflows/release.yml` if installed, else `python3 -c "import yaml,sys; yaml.safe_load(open(sys.argv[1]))" .github/workflows/release.yml`.
- [ ] **Step 4:** Signing secrets: the user decides whether to reuse ringer's key or create a new keystore (`keytool -genkeypair -v -keystore calendar-bridge.jks -keyalg RSA -keysize 4096 -validity 10000 -alias calendar-bridge`, kept outside the repo and backed up) and adds the four secrets in GitHub themselves; Claude does not handle the passwords.
- [ ] **Step 5: Commit** `ci: release workflow copied from ringer`. After push/merge (each gated by the user's phrase), "Run workflow" without a version gives a test APK; `v0.1.0` gives the first release.
