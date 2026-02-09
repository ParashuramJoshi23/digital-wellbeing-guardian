# Digital Wellbeing Guardian (Android MVP)

A Kotlin + Jetpack Compose MVP that monitors selected apps using `UsageStatsManager` and helps catch runaway sessions.

> ⚠️ **Important platform note:** Android does **not** provide a public Digital Wellbeing API for third-party apps. This project uses public usage access APIs (`PACKAGE_USAGE_STATS`) as an approximation.

## Features

- Configurable tracked app list (default seeded apps):
  - YouTube (`com.google.android.youtube`)
  - Clash of Clans (`com.supercell.clashofclans`)
  - X (`com.twitter.android`)
  - WhatsApp (`com.whatsapp`)
- Foreground monitoring service with 15-second polling while screen is on
- Session detection via `UsageStatsManager.queryEvents(...)`
- Runaway session detection at **>20 min**
- Intervention notification with quick actions:
  - **Stop now**
  - **Extend 5 min**
- Local persistence with Room database (`sessions`, `tracked_apps`)
- Daily summary screen:
  - Total tracked usage
  - Longest session
  - Sessions over threshold
- Optional default reason text (saved in-app) used with extension action

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- Room
- Foreground Service + Notifications
- UsageStatsManager

## Project Location

`/Users/parashuram/clawd/projects/digital-wellbeing-guardian`

## Build & Run

### Prerequisites

- Android Studio Iguana+ / Hedgehog+ (or newer)
- Android SDK installed (`compileSdk 34`)
- JDK 17

### Steps

1. Open project in Android Studio.
2. Let Gradle sync (wrapper included).
3. Ensure `local.properties` points to your SDK, for example:
   ```properties
   sdk.dir=/Users/<you>/Library/Android/sdk
   ```
4. Run on a real device (recommended).

CLI build (from project root):

```bash
./gradlew assembleDebug
```

## Required Permissions / Settings

1. **Usage Access**
   - App needs manual grant via system settings:
   - `Settings > Security/Privacy > Usage Access`
   - In app, tap **Grant Usage Access**.

2. **Notifications (Android 13+)**
   - `POST_NOTIFICATIONS` runtime permission requested at launch.

3. **Foreground service**
   - Start from app UI using **Start Monitoring**.

## Debug Instructions

- Start service and watch logs:
  ```bash
  adb logcat | grep -i guardian
  ```
- Verify the service notification is visible and ongoing.
- Open a tracked app for >20 min to trigger intervention notification.
- Press:
  - **Stop now** → session is closed immediately and stored.
  - **Extend 5 min** → threshold gets +5 min for active session.
- Confirm DB behavior via Android Studio App Inspection (Database Inspector):
  - `tracked_apps` rows seeded/updated
  - `sessions` rows inserted with duration/intervention metadata

## Notes / Limitations (MVP)

- Session tracking is best-effort and event-based; OEM behavior may vary.
- This app cannot force-close other apps (Android restriction).
- “Stop now” records intervention and nudges user behavior; it does not terminate the target app process.

## Modules

- `app/src/main/java/com/example/digitalwellbeingguardian/`
  - `MainActivity.kt` (Compose UI)
  - `ui/MainViewModel.kt`
  - `data/*` (Room entities/DAO/repository)
  - `service/UsageMonitorService.kt`
  - `util/TimeFormat.kt`
