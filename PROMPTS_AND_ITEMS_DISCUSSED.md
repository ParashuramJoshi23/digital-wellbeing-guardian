# Digital Wellbeing Guardian — Prompts & Items Discussed

_Last updated: 2026-02-09_

## 1) Initial product ask (MVP)
- Build an Android app MVP to reduce runaway usage and improve completion.
- Track selected distracting apps.
- Keep a clear flow: Awareness → Active (WIP limited) → Done mindset.

## 2) Functional requirements discussed
- Kotlin + Jetpack Compose app scaffold.
- Usage tracking via `UsageStatsManager` and usage-access permission flow.
- Default tracked apps seeded:
  - YouTube (`com.google.android.youtube`)
  - Clash of Clans (`com.supercell.clashofclans`)
  - X/Twitter (`com.twitter.android`)
  - WhatsApp (`com.whatsapp`)
- Runaway session detection threshold: **20 minutes**.
- Foreground monitor polling interval: **15 seconds** (while screen is on).
- Intervention notification actions:
  - **Stop now**
  - **Extend 5 min**
- Local persistence with Room:
  - tracked apps
  - session metadata (duration, threshold crossing, intervention action, optional reason)
- Compose screens:
  - Daily summary
  - Tracked apps management

## 3) Build/dev environment setup requested
- Install JDK 17.
- Install Android command line tools and SDK components.
- Configure `JAVA_HOME`, `ANDROID_SDK_ROOT`, and related PATH entries.
- Verify by building debug APK.

## 4) Distribution & installation issues discussed
- Discord attachment limit blocked direct APK upload.
- Shared downloadable APK links.
- Encountered install error: **“There was a problem while parsing the package.”**
- Rebuilt and re-signed variants; switched to a known-good debug install path.
- Copied APK to Desktop for direct transfer.

## 5) Runtime issues reported from device
- App auto-closing after permissions.
- “Start Monitoring” appeared to do nothing.
- Tracked Apps list not scrolling / not showing expected behavior.

## 6) Fixes implemented
- Added required foreground service permission updates for Android 14 behavior.
- Improved Start/Stop UX with immediate feedback (toasts).
- Added explicit usage-access re-check and redirect.
- Fixed tab/content layout to allow proper scroll in tracked-apps list.
- Rebuilt and reinstalled on connected Nothing Phone (2).

## 7) Testing requested
- Add tests.
- Added unit tests for duration formatter.
- Added instrumentation/UI tests for main screen:
  - controls and tab visibility
  - switching to Tracked Apps tab
- Ran connected Android tests on device; tests passed.

## 8) Current UX/product gap acknowledged
- Need stronger real-time visibility (service running state, currently tracked app, live timer).
- Need installed-app picker/search (instead of package-name-only add flow).
- Need clearer, more tangible feedback loop to support completion behavior.

## 9) Design follow-up requested
- Requested Figma-based preview before more coding.
- Discussed enabling Figma MCP flow and creating a task to set it up.

---

## Suggested next sprint items
1. Live tracking status card (current app + elapsed time).
2. Installed app picker with search + multi-select.
3. Better empty states and permission diagnostics.
4. Session timeline with “why intervention fired”.
5. Export/share daily summary.
