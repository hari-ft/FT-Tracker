# FT-Tracker

A personal screen-time tracker for Android, tuned for vivo phones. It records how long each app is used, how often it is opened and at what time of day, and keeps that history for months on the phone, well beyond the week Android itself keeps.

Version 1.3.2 · Kotlin + Jetpack Compose · Android 9 (API 28) and newer · package `com.hari.screentime`

## Features

- **Today**: live screen time, app opens, and every app with its share of the day.
- **Report**: Today, Yesterday, 7 days, 30 days, This month or a custom range, with daily and hourly charts, a share-of-time donut and an app table (time, opens, %).
- **App details**: a month calendar shaded by use (tap a day to open it), session stats (count, average, longest), a split by session length, and every session with its start and end time.
- **Settings**
  - Keep history: 1, 3, 6 (default), 12 or 24 months, or forever.
  - Skip rarely used apps: hide apps used less than 1, 2 (default), 5 or 10 minutes in a day, or keep all.
  - Storage: database size, oldest record, days, records and sessions saved.
  - Background saving: every permission the app needs, marked Allowed / Not allowed, with a button to the right system screen.
  - App log: log level, log retention, viewer, share and clear.
- Dates, times and numbers are always Indian style: `16 Nov 2026`, `1:15 pm`, `1,00,000`, weeks starting on Sunday.

## How usage is measured

Android records an event log of apps opening and closing, the screen turning on and off, and the phone locking and unlocking. FT-Tracker reads that log with the Usage Access permission and counts an app's time only while all three hold:

1. the app is on screen,
2. the screen is on,
3. the phone is unlocked.

Leaving Instagram open and locking the phone stops the clock until it is unlocked again.

- **Sessions**: an app opened until it is left or the phone is locked. Returning within 5 seconds (moving between the app's own screens) continues the same session. A session that runs past midnight is split into one part per day; the open is counted on the day it started.
- **Ignored**: home launchers, System UI, `android` and FT-Tracker itself.
- **Today** is computed live from Android's log on every refresh (every 30 seconds while the app is open). **Past days** are read from the database.

## Data and storage

Android keeps its event log for only about 7 days, so the app saves totals into its own SQLite database, `usage.db`:

| Table | Holds |
|---|---|
| `daily_usage` | time and opens per app per day |
| `hourly_usage` | time per app per hour of each day |
| `sessions` | each session's start, end and counted time |

- An **hourly background job** saves the last 7 days, and the app saves on every refresh while open.
- A **midnight job** (12:00 AM daily) saves the day that just ended, then deletes apps whose total for that day stayed under the "Skip rarely used apps" minimum. During the day those apps are hidden but kept, so an app that later passes the minimum reappears.
- Rows older than the "Keep history" setting are deleted on every save.
- Hidden apps are left out of totals as well as lists, so totals always match what is shown.

### Backup

`allowBackup` is on. The database and settings are included in the phone's end-to-end encrypted Google backup and in phone-to-phone transfers (Google, vivo EasyShare), so history survives a reinstall or a new phone. The app log and the background-save times are excluded ([data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml), [backup_rules.xml](app/src/main/res/xml/backup_rules.xml)). The app has no network permission and never sends data anywhere itself.

## Permissions

| Permission | Why | How it is granted |
|---|---|---|
| Usage access | Read the app and lock-screen event log | Settings › Usage access (the app asks on first launch) |
| Unrestricted battery use | Stop battery saving delaying the background jobs | System prompt from Settings › Background saving |
| Background activity | Let the jobs run while the app is closed | App info page |
| Keep running if unused (Android 11+) | Stop Android pausing the app when not opened for a while | System switch from Settings › Background saving |
| Auto-start (vivo) | vivo can block background starts | App info page; see below |
| Start after restart | Reschedule the jobs after a reboot | Granted at install |
| See installed apps (Android 11+) | App names and icons | Granted at install |

Android offers no way to read vivo's auto-start switch, and vivo's own screen for it is closed to other apps. The app judges it by its effect instead: it records when the background jobs actually run and shows **Working** if a background save ran in the last 3 hours and the midnight job in the last 26 hours, **Not working** otherwise, or **Waiting** just after install.

## App log

Settings › App log writes one text file per day to the app's private storage (`files/logs`), capped at 5 MB a day.

- **Levels**: Off, Errors only, Warnings and errors, Info (default), Debug.
- **Retention**: 1, 3, 7 (default), 14 or 30 days.
- **Records**: app starts, background saves and their timing or errors, midnight cleanups, deleted records, settings and permission changes, reboot rescheduling, and crashes with the full stack trace.
- **Viewer**: one tab per day, coloured by level, selectable text, Share (through the system share sheet) and Clear.

vivo hides Logcat output from release apps, so these files are the reliable record. Lines look like:

```
16-11-2026 05:50:19.000 pm  INFO   [App] Started: FT-Tracker 1.3.2 on vivo V2511, Android 16 (API 36)
```

## Building

Requirements: JDK 17 and the Android SDK with platform 35 (set `sdk.dir` in `local.properties`, which is not committed).

```sh
./gradlew assembleDebug      # debug build, installs beside release as com.hari.screentime.debug
./gradlew assembleRelease    # minified, signed release build
./gradlew installDebug       # build and install the debug build on a connected phone
./gradlew testDebugUnitTest  # unit tests for the usage engine
./gradlew lintRelease        # lint; warnings are treated as errors
```

Every built APK is copied to `build/<variant>/FT-Tracker-v<version>-<variant>-<timestamp>.apk`, keeping the newest 3 per variant.

Install a release build on a connected phone with:

```sh
adb install -r build/release/FT-Tracker-v1.3.2-release-<timestamp>.apk
```

### Release signing

Release builds are signed with the keystore described in `keystore.properties` at the project root. Both that file and the `keystore/` folder are excluded from git; keep them backed up, because updates must be signed with the same key. Without the file, release builds come out unsigned.

```properties
storeFile=keystore/vivoscreen-release.jks
storePassword=...
keyAlias=vivoscreen
keyPassword=...
```

### Versioning

The version lives in `version.properties` as `major.minor.patch`:

```sh
./gradlew bumpMajor   # x.0.0  major release
./gradlew bumpMinor   # 1.x.0  new features
./gradlew bumpPatch   # 1.0.x  bug fixes
```

The version code is `major × 10000 + minor × 100 + patch`.

## Project layout

```
app/src/main/java/com/hari/screentime/
├── MainActivity.kt          tabs, navigation, opening system settings, sharing logs
├── ScreenTimeApp.kt         app start: logging and background job scheduling
├── data/
│   ├── UsageEngine.kt       turns raw events into daily, hourly and session usage (unit tested)
│   ├── UsageCalculator.kt   reads Android's event log for the engine
│   ├── UsageRepository.kt   SQLite storage, saving, cleanup and retention
│   ├── Settings.kt          user preferences and their options
│   ├── AppPermissions.kt    permission checks and the auto-start health check
│   ├── BackgroundHealth.kt  when the background jobs last ran
│   ├── AppLog.kt            the application log
│   └── Permissions.kt       usage access check
├── tracking/
│   ├── SyncWorker.kt            hourly background save
│   ├── MidnightCleanupWorker.kt daily 12:00 AM save and cleanup
│   └── BootReceiver.kt          reschedules after reboot, update, clock or time zone change
└── ui/                      Compose screens, charts, formatting and the view model
```

## Known limitations

- Session details exist only from version 1.2 onward; earlier days have totals and opens but no session list.
- Android keeps only about 7 days of events, so days missed by the background jobs for longer than that cannot be recovered.
- Usage of other FT-Tracker builds (for example a debug build) is counted like any other app.
- The custom date picker follows the phone's language rather than the fixed Indian format.
- `QUERY_ALL_PACKAGES` suits direct installs; publishing on Google Play would need a justification or a narrower query.
