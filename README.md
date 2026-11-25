# Calendar Bridge

An Android app that subscribes to iCalendar (`.ics` / `webcal://`) URLs and syncs them into read-only calendars that every calendar app can show. In that respect it's like ICSx5, but it gets timezones right for Outlook/Exchange published calendars.

Outlook writes Windows timezone names (`TZID=Pacific Standard Time`) that Android doesn't understand, which shifts events, especially recurring ones across daylight-saving changes. Calendar Bridge maps them to IANA zones (`America/Los_Angeles`) using Android's built-in ICU data, falls back to matching the feed's own `VTIMEZONE` rules, and stores every event with its real zone so Android expands recurrences correctly.

- One calendar per subscription, synced in the background (15 min to daily, or manual)
- Sync now / Force sync (re-download and rewrite everything)
- Recurring events, moved and cancelled occurrences, all-day events, busy/free, reminders
- Mirrors the feed: events that disappear from the feed are removed

Install the APK from [Releases](../../releases). See [docs/RELEASING.md](docs/RELEASING.md) for how releases are built.
