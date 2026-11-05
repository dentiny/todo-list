# Daymark

**Make room for what matters.**

A quiet Android planner for daily priorities, upcoming events, and a record of your days. A shared planner for a small group, with one shared-password sign-in backed by Supabase Free.

## Design principles

- **Put the day first.** Must-do tasks and the next event come before decoration. A compact header leaves room for the work itself.
- **Keep priorities clear.** P0 is a must-do; P1 and P2 are nice to do. Every unfinished task carries forward until it is completed, marked stale, or deleted.
- **Make calm useful.** Warm paper, forest green, serif headings, and simple icons create a consistent visual language. Clear labels and restrained spacing keep it practical.
- **Respect attention and intent.** Add or complete a task in a few taps. Protect unsaved changes, preserve edits through rotation, and let layouts adapt to larger system text.
- **Choose what to share.** Original local data stays on the phone. Shared lists use Supabase Auth and PostgreSQL, with isolated SQLite caches and per-device reminder state.

## Screenshots

<table>
  <tr>
    <th>Today</th>
    <th>Events</th>
    <th>Journal</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/today.png" alt="Today with a compact event preview and automatically carried daily priorities" width="250"></td>
    <td><img src="docs/screenshots/events.png" alt="Upcoming events with their exact times and recurrence" width="250"></td>
    <td><img src="docs/screenshots/journal.png" alt="Journal with daily open, completed, carried, and stale task counts" width="250"></td>
  </tr>
  <tr>
    <td>See what matters today.</td>
    <td>Keep important dates close.</td>
    <td>Look back without losing context.</td>
  </tr>
</table>

Screenshots use illustrative local data.

## Features

### Daily priorities

Each day has three task sections and an Events section:

| Priority | Meaning | Unfinished tasks |
| --- | --- | --- |
| **P0** | Must do | Automatically carried to the next day |
| **P1** | Nice to do | Automatically carried to the next day |
| **P2** | If there is room | Automatically carried to the next day |

- Add, edit, delete, complete, or mark a task **Stale**. Switch freely between P0, P1, and P2.
- **Stale** means no longer needed: the record stays in history and stops carrying forward. Deletion removes that day's entry.
- Days change at **midnight in your phone's local time**. Unfinished P0, P1, and P2 tasks catch up to today even after several days away.
- Tap the date to open the calendar. **Journal** shows previous days with open, completed, carried, and stale counts, including quiet days.

### Events and reminders

- Create one-time events or repeats that run **daily, weekly, monthly, or yearly**. Every event has a date and a time precise to the minute.
- Receive two notifications: **24 hours before** and **30 minutes before** each occurrence. Local events use the phone's time zone; shared events use the shared list's time zone.
- Today's Events section shows today's occurrences and tomorrow's preview. A compact preview near the top highlights the next event.
- Monthly and yearly repeats retain their original date: January 31 returns to March 31 after February, and February 29 returns in leap years.
- Edit or delete an event to update its future reminders. Past one-time events remain available in Events.

Allow notifications and precise reminders through the bell button. Android may delay reminders without precise alarm access; it cannot deliver them while the phone is off or the app is force-stopped.

### Shared list

Install the APK and enter the shared App password on the opening screen. The project, account, and shared list are preconfigured. You stay signed in until you sign out; the password is never bundled in the APK. Everyone using this account can read and edit the same tasks and events.

On Today, tap **Refresh** or pull down from the top of the list to load other people's saved changes. The foreground app also checks for updates every 15 seconds and refreshes on resume and after saves. Saving shared edits requires internet; previously synchronized data is available offline. Refresh progress is shown, and overlapping refresh requests are coalesced.

Shared tasks carry forward until completed or marked Stale, with history preserved. Rollover and shared event times use the list's fixed time zone. Notification state remains local to each device. The app does not continuously sync while closed, so reminders then use the last synchronized events. Concurrent edits are checked before saving rather than silently overwriting someone else's changes.

### Original data and privacy

Original local tasks and events remain in app-private SQLite until explicitly imported through **Shared list → Import original local data**. The original database and shared cache remain separate. Sign-in is required before the planner interface opens.

Shared content is uploaded to the configured Supabase project and is accessible to the shared account and project administrators. The APK contains a public project key, not a database administrator password or privileged API key. Access and refresh tokens are encrypted with Android Keystore. There is no analytics. Android cloud backup and device transfer remain disabled. Install updates over the existing app; uninstalling deletes local data and unsynced state.

The interface is entirely in English. Android 8.0 (API 26) or later is supported.
