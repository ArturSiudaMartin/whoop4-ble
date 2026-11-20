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
| 6. Kotlin ↔ Python decoder parity | ✅ Done (see below) |
| 7. Database | ✅ Done (see below) |
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

### What Phase 6 contains

- `core:protocol` ports every decoder from the Python reference: frame parsing (`Packet.parse`), HISTORICAL_DATA records,
  EVENT packets (battery, skin temp, wrist on/off, sensor snapshot, state markers, unknown types kept as raw hex),
  METADATA (HISTORY_START/END/COMPLETE, trim offset), GET_CLOCK responses, CONSOLE_LOGS text (including detection of the
  "RTC timestamp … is invalid" message) and the standard 0x2A37 heart-rate measurement.
- `Commands` builds **only** the five verified commands (SET_CLOCK with its 5-byte payload, GET_CLOCK,
  SEND_HISTORICAL_DATA, ABORT_HISTORICAL_TRANSMITS, HISTORICAL_DATA_RESULT). There is no way to send arbitrary bytes.
  The unverified 0x03/0x0E "enable HR" commands used by the web and legacy apps are deliberately **not** ported; Phase 4 decides on them.
- **Parity tests:** `tools/golden.py` runs the Python reference (`packet.py`, and `reader.py`'s HR parser) over 360 frames:
  4 real captures from `docs/SAMPLE_SESSION.md`, about 55 edge cases and a seeded random sweep of 300 records. It writes
  `core/protocol/src/test/resources/golden/vectors.json`. `GoldenVectorsTest` checks that the Kotlin decoder matches it
  field for field. Regenerate the vectors with `python -I tools/golden.py` whenever the Python code changes.
- **Deliberate differences from Python:** accelerometer values keep the raw float32 (Python rounds to 4 decimals; tests
  allow for that); RR from the HR service stays in raw 1/1024 s units with an `rrMs` view; malformed HR packets return
  `null` where `reader.py` would raise.
- **Bug found and fixed:** frame reassembly now validates the header CRC-8 before treating a notification as a new
  frame. The SOF-only check in the web and legacy apps loses a frame when a continuation chunk starts with `0xAA`
  (caught by the test that splits all vectors into 20-byte notifications). The band's header CRC-8 was verified on real captures.

### What Phase 7 contains

- **Room schema v2** (`core/data`), exported to `core/data/schemas/2.json`. All timestamps are UTC epoch ms.
  Tables: `device`, `hr_sample`, `rr_interval`, `band_event`, `raw_packet`, `sync_run`, `activity_session`,
  `sleep_session`, `sleep_stage`, `daily_summary`, `metric_value`. Bloop-computed values always carry
  `method`, `methodVersion` and `confidence`; missing data is no row, never a guessed number.
- **No duplicates:** unique indices act as dedup keys and rows are inserted with IGNORE.
  - Historical samples are keyed on (device, timestamp, sub-second).
  - Live samples are keyed on the phone's receive time.
  - Events are keyed on their raw bytes, which include the band's event clock.
  - Raw frames are keyed on a SHA-256-derived 64-bit hash.
  - Re-syncing the same data adds nothing (tested).
- **Commit-before-ACK:** `IngestRepository.ingestFrames` stores a batch (raw frames, samples, RR, events) in **one
  transaction**, so the Phase 5 sync engine can ACK a batch only after it is durably stored. Atomicity is tested.
- **Raw archive:** every sync frame is kept verbatim (as `drain.py` does) so it can be re-decoded later. It can be
  switched off, and there is a retention query.
- **Interrupted syncs:** runs left `RUNNING` by a crash or kill are marked `PARTIAL` on the next start.
- **Export:** `JsonlExporter` writes `hr.jsonl` in `drain.py`'s exact format (same keys, `.NNN` same-second counter,
  Python-style 4-decimal rounding of accel), so Bloop data merges with data from the Python toolkit or the web app.
- **Upgrade from the Phase 2 preview:** Room auto-migration 1 → 2, tested with `MigrationTestHelper`. Destructive
  fallback is never enabled.
- **Tests:** 11 Robolectric tests (`./gradlew :core:data:testDebugUnitTest`); 23 tests across the project.
- **Open point for Phase 5:** the historical dedup key assumes (timestamp, sub-second) is unique per record. The
  band's record sequence counter is stored too, so this can be checked on the first real sync.

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
