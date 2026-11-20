# Bloop: Android companion for the Whoop 4.0

Bloop is a Kotlin / Jetpack Compose app that talks to the Whoop 4.0 directly over Bluetooth, stores everything on the
phone and computes its own, clearly labelled metrics. No Whoop subscription or cloud account is needed.

- Plan, architecture, risks and decisions: [`../docs/ANDROID_APP_PLAN.md`](../docs/ANDROID_APP_PLAN.md)
- The original minimal Java app (live HR + session tracking) is kept in [`../android-legacy`](../android-legacy).
  Bloop has a different app ID (`dev.whoop4.bloop`), so both can be installed at once.

## Status

| Phase | State |
|---|---|
| 1. Audit & plan | ✅ Done (`docs/ANDROID_APP_PLAN.md`) |
| 2. Project setup & APK build | ✅ Done (see below) |
| 3. Discovery & connection | ⏳ Next. Needs the phone for on-device testing |
| 4. Live HR + RR ("Go live") | Not started |
| 5. Historical sync | Not started |
| 6. Kotlin ↔ Python decoder parity | Started: framing/CRC port with tests against Python output |
| 7. Database | Not started (Room toolchain proven with a skeleton table) |
| 8. Analytics | Not started |
| 9. UI | Navigation + visual language only; screens show "Not yet available" |
| 10. Background & reliability | Not started |
| 11. Release | Not started |
| 12. Concept2 PM5 | Planned (§8 of the plan) |

### What Phase 2 contains

- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`), AGP 9.1 with built-in Kotlin 2.4, Compose (BOM 2026.11),
  Material 3, Room 2.8 via KSP, Coroutines.
- Modules: `app` (UI), `core:protocol` (pure JVM), `core:ble`, `core:data` (Room), `core:analytics` (pure JVM).
- `core:protocol`: UUIDs, packet types, CRC8/CRC32 and frame builder ported from `src/whoop4/packet.py`. **6 unit tests**
  check the Kotlin output byte-for-byte against values produced by the Python reference.
- `core:data`: Room database with one `device` table; the schema is exported to `core/data/schemas/`.
- `core:analytics`: the `Metric` type (Measured / Estimated with method + confidence / Missing) that every displayed value will use.
- `app`: dark theme, original icon and nav icons, bottom navigation (Home, Recovery, Sleep, Strain, Live) and a Device screen.
  **No screen shows numbers yet.** Every card says what it will show and in which phase.
- Verified on the Android emulator (API 37): launches, navigates and has no crashes. No Bluetooth testing is possible on the emulator.

### Known issues / notes

- Current AndroidX libraries require `compileSdk = 37`. AGP 9.1 officially recommends up to 36 and prints a warning; the
  build and the app work. `targetSdk` stays at 36.
- No Bluetooth permissions are declared yet; they arrive with the code that needs them (Phase 3).

## Build

Requires the Android SDK (platform 37) and JDK 17+ (Android Studio's bundled JBR works):

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew test assembleRelease
# → app/build/outputs/apk/release/app-release.apk (debug-key signed, for sideloading)
```
