# Dock — Minimal Futuristic Charging Screensaver for Android

A native Android screensaver (DreamService) inspired by the *functionality* of iOS StandBy mode — clock, live widgets, optional photo background, auto-activates while charging — with its own **original minimal/futuristic visual identity**.

> **Status:** All phases complete (DreamService, settings, slideshow, widgets, polish).

---

## Features (Planned)

| Phase | Feature | Status |
|-------|---------|--------|
| 1 | DreamService registers as system screensaver; clock + date on warm dark background | ✅ Done |
| 2 | Settings screen with `slideshow_enabled` / `widgets_enabled` toggles | ✅ Done |
| 3 | Photo slideshow: picker, crossfade, scrim overlay, interval | ✅ Done |
| 4 | Live widgets: `AppWidgetHost` integration, slot management, layout resize | ✅ Done |
| 5 | Night auto-dim (accent tint), animations, app icon, F-Droid metadata | ✅ Done |

---

## Design Language

- **Background:** `#1e1e1d` (warm near-black) or `#000000` (OLED option)
- **Primary text:** `#c3c2b7` (warm off-white)
- **Accent:** `#d57455` (terracotta — active states, glow, progress)
- **Typography:** Thin/light weight (`fontWeight=300`), large clock (~120sp)
- **No gradients, shadows, skeuomorphism, or colors outside the palette**

---

## Requirements

- Android 8.0+ (API 26) — `minSdk 26` for stable `AppWidgetHost`
- Target Android 14 (API 34)
- GrapheneOS / stock AOSP compatible
- **No network permission** — the app makes zero network calls
- **No broad storage permission** — uses system photo picker only
- **No analytics / telemetry / ads** — fully open source

---

## Build

```bash
# CI builds automatically on push to main via GitHub Actions.
# No local Gradle build step required — download APK from Actions artifacts.
```

### GitHub Actions

Workflow: `.github/workflows/build.yml`  
Triggers: push to `main`, PRs, version tags (`v*`)  
Artifacts: `Dock-debug.apk`, `Dock-release.apk` (on tags)

---

## Project Structure

```
Dock/
├── app/
│   ├── src/main/
│   │   ├── java/com/nousresearch/dock/
│   │   │   ├── dream/           # DreamService + content
│   │   │   ├── settings/        # SettingsActivity + prefs
│   │   │   ├── slideshow/       # PhotoSlideshowManager — crossfade engine
│   │   │   └── widget/          # WidgetHostManager — AppWidgetHost integration
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── dream_dock.xml
│   │   │   │   └── preference_category_custom.xml
│   │   │   ├── xml/
│   │   │   │   ├── dream_config.xml
│   │   │   │   └── settings_preferences.xml
│   │   │   ├── values/
│   │   │   │   ├── colors.xml
│   │   │   │   ├── strings.xml
│   │   │   │   ├── themes.xml
│   │   │   │   └── attrs.xml
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── .github/workflows/build.yml
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── LICENSE
```

---

## Installing on Device

1. Download `Dock-debug.apk` from the latest GitHub Actions run
2. Install via `adb install Dock-debug.apk` or transfer to device
3. Open **Settings → Display → Screen saver** → select **Dock**
4. Set "When to start screen saver" → **While charging**
5. Plug in and enjoy

---

## License

Apache 2.0 — see [LICENSE](LICENSE)