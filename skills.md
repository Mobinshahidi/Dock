# Dock — Minimal Futuristic Charging Screensaver for Android

## Description
Dock is a native Android `DreamService` (system screensaver) inspired by the functionality of iOS StandBy mode: a large animated clock, optional date and battery readout, live home-screen widgets, and an optional crossfading photo background. It auto-activates while the device is charging/docked and is built to run on stock AOSP / GrapheneOS with no network, broad-storage, analytics, or ad permissions.

## Tech Stack
| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Kotlin (JVM target 17) | Modern native Android development |
| Platform | Android, `minSdk 26` (8.0), `targetSdk`/`compileSdk 34` | minSdk 26 for stable `AppWidgetHost` |
| Build | Gradle (Kotlin DSL) + Android Gradle Plugin, KAPT | Standard Android build; KAPT for Glide compiler |
| UI | Android Views + XML layouts (Compose disabled), ConstraintLayout, custom `View` | Lightweight; no Compose dependency |
| Settings | AndroidX `preference-ktx` (PreferenceFragmentCompat) | Declarative settings screen |
| Images | Glide 4.16 | Slideshow photo loading + crossfade |
| Widgets | `AppWidgetHost` / `AppWidgetManager` (framework) | Host third-party home-screen widgets inside the dream |
| CI | GitHub Actions (Temurin JDK 17, android-actions/setup-android) | Builds debug/release APKs, lint, tests, releases on tags |

Note: `applicationId` is `com.dock.app` while the source package/`namespace` is `com.nousresearch.dock` — relevant when issuing ADB screensaver-component commands.

## Architecture
Single Android app module (`app/`). Four functional packages under `com.nousresearch.dock`:

- `dream/` — the `DreamService` itself plus the custom animated clock view.
- `settings/` — launcher-visible settings Activity wiring every preference to SharedPreferences and the managers.
- `slideshow/` — singleton manager that crossfades photos behind the clock.
- `widget/` — singleton manager that hosts 1–3 live app widgets via `AppWidgetHost`.

The two managers are process-wide singletons (`getInstance`) so the `SettingsActivity` and the running `DockDreamService` share the same configuration/state and SharedPreferences. UI is XML-inflated; the clock is a custom-drawn `View`.

### File Map
- `app/src/main/java/com/nousresearch/dock/dream/DockDreamService.kt` — the `DreamService`. Inflates `dream_dock`, wires clock/date/battery/slideshow/widgets, handles orientation rebuilds, battery broadcasts, and clock customization from prefs.
- `app/src/main/java/com/nousresearch/dock/dream/AnimatedClockView.kt` — custom `View` that draws `HH:mm`, ticks each second, animates digit changes, and renders 5 styles (DEFAULT/BUBBLY/NEON/GRADIENT/OUTLINE).
- `app/src/main/java/com/nousresearch/dock/settings/SettingsActivity.kt` — launcher entry point. Hosts `DockSettingsFragment` with toggles, photo picker, widget pickers, RGB+hex color dialog, font upload, and the auto-start guide dialog.
- `app/src/main/java/com/nousresearch/dock/slideshow/PhotoSlideshowManager.kt` — singleton; Glide-backed two-ImageView crossfade engine with scrim overlay and configurable interval.
- `app/src/main/java/com/nousresearch/dock/widget/WidgetHostManager.kt` — singleton; manages `AppWidgetHost`, 1–3 slot containers, widget binding/permission flow, per-slot sizing, persistence in a private prefs file.
- `app/src/main/AndroidManifest.xml` — declares `DockDreamService` (BIND_DREAM_SERVICE), launcher `SettingsActivity`, and `<queries>` for app-widget provider visibility.
- `app/src/main/res/layout/dream_dock.xml` / `layout-land/dream_dock.xml` — portrait/landscape dream layouts (clock container, widget rail, slideshow front/back ImageViews, scrim).
- `app/src/main/res/xml/dream_config.xml` — dream metadata (settings activity link).
- `app/src/main/res/xml/settings_preferences.xml` — the preference tree.
- `app/src/main/res/values/{strings,colors,dimens,themes,attrs,arrays}.xml` — palette (`bg_dark` #1e1e1d, accent terracotta #d57455), preference keys, list-entry arrays, dimensions.
- `app/build.gradle.kts` — module config and dependencies. `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — project setup.
- `.github/workflows/build.yml` — CI: builds debug + (on main/tags) release APKs, runs lint/tests, creates GitHub Release on `v*` tags.
- `app/proguard-rules.pro` — R8 rules for the minified release build.

## How It Works
1. User installs the APK, opens **Dock** (the launcher icon → `SettingsActivity`), configures preferences, and uses the "Auto-start setup" guide to select Dock under **Settings → Display → Screen saver** with "While charging".
2. When charging/docked, the system binds `DockDreamService`. `onAttachedToWindow()` sets the dream non-interactive/fullscreen, hides system bars, loads default SharedPreferences, and calls `setupContentView()`.
3. `setupContentView()` detects physical rotation (`currentPhysicalOrientation()` via display rotation, working around stale DreamActivity orientation), inflates `dream_dock` against the correct config, finds all views, and obtains the `PhotoSlideshowManager` and `WidgetHostManager` singletons, initializing each.
4. `onDreamingStarted()` calls `loadModuleStates()` (date/battery visibility, slideshow + widget enable flags), applies clock position/customization/style, starts the clock ticking, registers the battery `BroadcastReceiver`, starts the slideshow if enabled, and starts the widget host.
5. `AnimatedClockView` ticks every second (synced to the second boundary), and on a minute change runs a 400ms slide/fade animation between old and new time, drawn in the selected style.
6. If photos are configured and slideshow is enabled, `PhotoSlideshowManager` loads images with Glide into alternating front/back ImageViews and crossfades them every interval, behind a dark scrim for legibility.
7. `WidgetHostManager` rebuilds 1–3 weighted slot containers (orientation-aware) and binds any persisted widgets via `AppWidgetHost`, posting real pixel dimensions back so widget providers render the correct RemoteViews layout.
8. On rotation, `onConfigurationChanged()` tears everything down and rebuilds against the new orientation (guarded so it no-ops if fired before attach). `onDreamingStopped()` stops the clock, unregisters the battery receiver, and stops both managers to release resources.

## Key Functions / Classes

### DockDreamService
- **Purpose**: The screensaver. Orchestrates clock, date, battery, slideshow, and widgets within the dream window.
- **Input**: System dream lifecycle callbacks; user preferences from default SharedPreferences.
- **Output**: The full-screen dock UI; persists slideshow photo URIs.
- **Quirks / notes**: `onConfigurationChanged` returns early if `widgetHostManager` isn't initialized (system can fire it before `onAttachedToWindow`, fixing a launch crash). Uses physical display rotation rather than the (sometimes stale) config orientation. `persistPhotoUris()` is `internal` for the settings side. Battery percent computed from `EXTRA_LEVEL`/`EXTRA_SCALE`; charging shows a ⚡ glyph.

### AnimatedClockView (extends View)
- **Purpose**: Custom-drawn `HH:mm` clock with per-second ticking, change animation, and 5 visual styles.
- **Input**: Public properties `clockStyle`, `clockColor`, `clockSize`, `clockTypeface`, `animEnabled`; `start()`/`stop()`.
- **Output**: Canvas drawing of the current time.
- **Quirks / notes**: `ClockStyle` enum = DEFAULT, BUBBLY, NEON, GRADIENT, OUTLINE. Tick re-syncs to the next second boundary via `1000 - now%1000`. `paint.alpha` is `Int` (a prior commit fixed Float→Int). Time format is fixed `HH:mm` (24h).

### WidgetHostManager (singleton)
- **Purpose**: Host third-party app widgets in 1–3 slots inside the dream.
- **Input**: `init(widgetRail, isLandscape)`, then `start()/stop()`, `setEnabled()`, `setSlotCount(1..3)`, `pickWidgetForSlot()`, `bindAppWidget()`, `finalizeWidgetBinding()`, `removeWidget()`, `notifySlotSizeChanged()`.
- **Output**: Rendered widget views; persists provider/id per slot in private prefs `dock_widget_prefs`.
- **Quirks / notes**: `HOST_ID = 0xD0CC`, `MAX_SLOTS = 3`. Slot weights from per-slot size pref (small 0.7 / large 1.3 / default 1.0). Two-step binding: tries direct bind, else `ACTION_APPWIDGET_BIND` permission request. After layout it posts real dp dimensions via `updateAppWidgetSize`/`updateAppWidgetOptions` so providers don't render 0×0. Includes `LoggingAppWidgetHost`/`LoggingHostView` for debugging RemoteViews delivery.

### PhotoSlideshowManager (singleton)
- **Purpose**: Crossfade photo background behind the clock.
- **Input**: `init(front, back, scrim)`, `setPhotoUris()`, `start()/stop()`, `setInterval(ms)`, `setEnabled()`.
- **Output**: Crossfaded images with a dark scrim.
- **Quirks / notes**: Two-ImageView ping-pong; Glide `centerCrop` into a `CustomTarget`, 500ms alpha crossfade. Default interval 60s. When disabled or no URIs, hides views and skips loading. `setContentResolverForTest` is a vestigial no-op kept for the testability pattern.

### SettingsActivity / DockSettingsFragment
- **Purpose**: All user configuration; launcher-visible entry point.
- **Input**: User taps; preference XML.
- **Output**: SharedPreferences writes; live updates to both managers.
- **Quirks / notes**: Photo picker uses `OpenMultipleDocuments` with `takePersistableUriPermission` (no broad storage permission); URIs stored pipe-separated in `slideshow_photo_uris`. Custom font copied to `filesDir/custom_font.ttf`. Color picker is a hand-built RGB-slider + hex `AlertDialog`. Auto-start guide dialog offers "Copy commands" (ADB) and "Open Settings" (`ACTION_DREAM_SETTINGS`). Slot manage/size prefs shown only up to the chosen slot count.

## Configuration
All user settings are AndroidX preferences. Default SharedPreferences keys (from `strings.xml`):

- `slideshow_enabled` (bool, default true), `slideshow_interval` (ms as string list; default 60000)
- `widgets_enabled` (bool, default true), `widget_slot_count` (string "1".."3"), per-slot `slot_size_1..3` (small/default/large), `manage_slot_1..3` (picker actions)
- `oled_mode` (bool → black vs #1e1e1d background), `brightness_override` (float 0..1, -1 = off)
- `clock_position` (left/center/right), `clock_font_size` (% int, default 100), `date_font_size`, `clock_color` (hex, default #c3c2b7), `clock_font` (default/serif/monospace/sans-serif-light/sans-serif-thin/custom), `clock_font_file` (abs path), `clock_style` (default/bubbly/neon/gradient/outline)
- `show_date` (bool), `battery_enabled` (bool), `transition_animation` (bool, default true)
- `night_dim_enabled` / `night_dim_start` / `night_dim_end` (keys declared; night-dim feature listed in README phase 5)
- Internal: `slideshow_photo_uris` (pipe-joined URI string) in default prefs; widget state in private prefs `dock_widget_prefs` (`widget_slot_count`, `widgets_enabled`, `widget_slot_N_provider`, `widget_slot_N_id`).

No environment variables or `.env` — pure Android app. ADB enablement (from the in-app guide):
```
adb shell settings put secure screensaver_enabled 1
adb shell settings put secure screensaver_components com.dock.app/.dream.DockDreamService
adb shell settings put secure screensaver_activate_on_dock 1
```

## Data Flow
- Photos: user picks via system document picker → persistable URI permission taken → URIs stored in prefs → `PhotoSlideshowManager` loads via Glide → crossfades into front/back ImageViews behind scrim.
- Widgets: user picks provider (`ACTION_APPWIDGET_PICK`) → bind directly or via `ACTION_APPWIDGET_BIND` permission → `AppWidgetHost.createView` → host view added to slot container → provider pushes RemoteViews → displayed in dream.
- Clock/battery: system clock (per-second tick) and `ACTION_BATTERY_CHANGED` broadcasts → drawn/updated on screen.
- No external network services; all data is local (device prefs, content-provider photo URIs, framework widget hosting).

## Entry Points
```bash
# install (CI-built APK recommended; no local build step required per README)
adb install app-debug.apk

# build (locally)
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease    # minified release APK (unsigned)

# test / lint
./gradlew test
./gradlew lint
```
Runtime entry: launcher icon opens `SettingsActivity`; the screensaver entry is `com.dock.app/.dream.DockDreamService` selected via Settings → Display → Screen saver.

## Known Issues / TODOs
- Release APK is **unsigned** (`app-release-unsigned.apk`) — no signing config in the build.
- `versionName` is `0.1.0-alpha`, `versionCode` 1 — early/alpha.
- `applicationId` (`com.dock.app`) differs from the code package (`com.nousresearch.dock`); ADB component string must use the applicationId.
- Night auto-dim has declared pref keys (`night_dim_*`) but no implementing logic is present in the read sources, despite README marking phase 5 done.
- `setContentResolverForTest` in `PhotoSlideshowManager` is a no-op placeholder.
- Auto-start reliability across OEM/AOSP variants is fragile — hence the in-app ADB fallback guide. Several past commits address dream-launch crashes and orientation handling.
- No unit/instrumentation test files were found in the tree (CI runs `./gradlew test` regardless).

## Recent Work (git log)
Recent commits focus on the animated clock and polish: fixing Float→Int paint alpha mismatches, adding the animated clock with custom styles and time-change animation plus a package rename, and a feature pack (launcher icon, clock-only mode, battery readout, themes, animations). Earlier work fixed dream-launch crashes from `onConfigurationChanged` firing before attach, reworked orientation detection (force/restore landscape, thread reliable orientation into the widget manager), and refined the settings UX (color picker with hex input, RGB picker, font-size sliders, custom font upload, auto-start guide). A run of layout/build fixes addressed landscape clock alignment, slot sizing, SeekBar/TextClock XML issues, missing imports, slideshow flicker, and added per-slot widget sizing and app-widget provider visibility queries.
```
