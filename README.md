# Daymark

A quiet, English-language Android planner. Warm paper, forest green, generous spacing, and a little intention for every day.

## Run

Open this directory in Android Studio, let Gradle sync, and run `app` on Android 8.0 (API 26) or later.

For command-line builds, install JDK 17 and Android SDK 36, set `ANDROID_HOME` (or create `local.properties` with `sdk.dir=...`), then run:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a small APK to install on your phone, use the optimized release build:

```sh
./gradlew assembleRelease lintRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Release builds remove unused code and resources with R8 and include only English resources. The app bundles only its 17 selected vector icons; it has no full icon library, preview tooling, or UI inspector. The personal sideload build uses the local debug signing key, matching earlier APKs built on the same machine so they can be updated in place without clearing SQLite data. Store distribution requires a separate private signing configuration.

The Gradle wrapper is included. The first build downloads its dependencies. Runtime use is entirely offline; the manifest has no internet permission.

## Your day

- **Today** always has P0, P1, P2, and Events sections. Choose a date from the week strip or calendar to view or plan another day.
- **P0 / Must do:** unfinished items automatically carry over at local midnight. Missed days are reconciled when the app runs again, with a separate historical snapshot for each day.
- **P1 / Nice to do** and **P2 / If there's room:** older open items wait in “A fresh look.” Expand it, select individual items or select all, then choose **Move to today** or **Keep in history**. Unreviewed items remain available on later days. Kept items can still be moved from their original day.
- Tap a task to edit its title, switch freely between priorities, or change its status. The current priority controls subsequent carry-over. Changing a past open item to P0 immediately reconciles it through today.
- **Done** and **Stale** stay in history and do not carry over. Stale means no longer needed. Either can be reopened.
- Carried snapshots show where the item went and are read-only. Deleting a task permanently removes that day's entry; other historical snapshots remain.
- **Journal** lists recorded task days and the last two weeks, including quiet days. The calendar can open any other date.

## Events and reminders

- One-time, daily, weekly, monthly, and yearly events, all with a required date and minute-level time. There are no all-day events.
- The Today Events section shows occurrences on the selected day and previews the next day. Events lists the next occurrence of each repeating event and retains past one-time events.
- Monthly and yearly repeats retain their original anchor: January 31 → February 28 → March 31. February 29 birthdays use February 28 in non-leap years and return to February 29 in leap years.
- Each occurrence gets independent reminders **24 elapsed hours** and **30 minutes** before it starts. A reminder whose scheduled time has already passed is skipped when creating an event. With daily events, tomorrow's 24-hour reminder and today's 30-minute reminder are scheduled independently.
- Times follow the phone's current local time zone. Across DST, a nonexistent local time is moved forward by the gap; a repeated local time uses its earlier offset. The 24-hour lead is an elapsed duration rather than “same time yesterday.”
- Android 13+ asks for notification permission after saving an event. Tap the bell to manage notifications and, on Android 12+, allow precise reminders. Without precise alarm access, Android may delay reminders; this state is shown in the app.
- Schedules are restored after reboot, app update, clock/time-zone changes, and granting precise-alarm access. Foreground rollover also checks the date at midnight. Editing or deleting an event replaces or cancels its alarms; a SQLite delivery ledger prevents duplicate reminders.
- Android cannot deliver reminders while the phone is off or the app is force-stopped. Open the app after force-stopping it to restore scheduling. Manufacturer battery restrictions can also affect delivery.

## Storage and structure

Everything lives in app-private SQLite (`daymark.db`) via `SQLiteOpenHelper`: task snapshots, event anchors, and delivered-reminder keys. Writes use transactions; a unique partial index prevents duplicate open instances of a task. No server, account, analytics, export, cloud backup, or device transfer is configured. Uninstalling clears the data.

- `domain/Models.kt`: models, anchor-based recurrence and elapsed reminder calculations; no Android dependencies.
- `data/DaymarkStore.kt`: SQLite schema, task history, transactional rollover, optional carry-over and event persistence.
- `reminders/ReminderScheduler.kt`: per-event/per-lead alarms, notification delivery, and restoration receivers.
- `ui/`: Compose theme, Today/Events/Journal, editors, reminder permissions, and ViewModel state.

Native Kotlin + Jetpack Compose, with Android platform SQLite and AlarmManager. No backend and no background network dependency.

## Verification

Unit tests exercise recurrence anchors, leap years, DST reminder leads, missed-day P0 snapshots, idempotence, optional carry-over, priority changes, stale/done behavior, persistence, and reminder invalidation. Android lint and a debug APK build run in CI.

For device acceptance: create a task in each priority; switch P1 ↔ P2; mark tasks done/stale; cross midnight or leave several days; review optional tasks individually and in bulk; navigate old dates; create/edit/delete one-time and recurring events; grant and deny both reminder permissions; verify both reminder offsets and scheduling after reboot/time-zone changes.
