# MaidFree — free Android storage cleaner (SD Maid alternative)

Free forever. No ads, no paywall, no tracking. MIT licensed.

## Features
- **Junk cleaner** — per-app cache overview (tap to clear in system settings),
  temp/log/backup files, strictly-empty folders, `.thumbnails` cache
- **Corpse finder** — orphaned `Android/data` / `Android/obb` folders from uninstalled apps
- **Duplicate finder** — SHA-256 identical-file detection
- **Large files** — top-50 space hogs
- **App manager** — per-app cache/data sizes, search, app info, uninstall
- **Daily scan** — optional AlarmManager-based reminder notification when junk > 50 MB
- Storage dashboard with used/free overview

## Honest limitations (no root)
- Cannot silently clear other apps' caches — Android only lets each app clear
  its own cache. MaidFree lists them and deep-links you to each app's settings.
- On Android 11+, `Android/data` / `Android/obb` of other apps are restricted;
  the corpse finder reports what the OS lets it see.
- No system/protected file access without root — same as every non-root cleaner.

## Build (no Gradle needed)
Gradle's daemon IPC is broken in this sandbox, so MaidFree is built directly
with the SDK command-line tools. Zero external dependencies — only
`android.jar` + the Kotlin stdlib. No AndroidX, no Maven downloads.

Prereqs (one-time):
- JDK 17 at `~/toolchain/jdk17`
- Android SDK at `~/android-sdk` (`platforms;android-34`, `build-tools;34.0.0`)
- Kotlin compiler at `~/toolchain/kotlin/kotlinc`

Then:
```bash
./build-apk.sh   # -> build-out/maidfree.apk (signed with debug key)
```

Pipeline: `aapt2 compile` → `aapt2 link` (+R.java) → `kotlinc` →
`d8` → add `classes.dex` → `zipalign` → `apksigner`.

## Install
Copy `maidfree.apk` to your phone, open it, allow "install unknown apps".
Grant **All files access** and **Usage access** when prompted for full cleaning.

## Permissions used
- `MANAGE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` — scan & delete junk
- `PACKAGE_USAGE_STATS` — per-app cache/data sizes
- `QUERY_ALL_PACKAGES` — app list
- `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` — daily scan reminder
