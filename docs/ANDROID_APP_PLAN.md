# Android Companion App: Audit & Architecture Plan

Status: **Phase 1 approved.** Implementation progress is tracked in `android/README.md`.
Written 2026-11-20.

---

## 0. Audit scope and findings about the repo itself

Read: `README.md`, `CONTRIBUTING.md`, all of `docs/`, all of `src/whoop4/` (including `monitor/server.py`),
`examples/`, `web/index.html`, the existing `android/` app and the git history.

| Finding | Detail |
|---|---|
| **No `HOW-TO.md`** | The file does not exist in the repo or its history. The nearest equivalents are the README Quickstart and `docs/ANDROID.md`. |
| **No automated tests** | No `tests/` directory, no pytest config, no fixtures. The Python code was validated by hand on hardware (macOS + bleak). |
| **No captured fixtures** | `drain.py` *writes* raw captures (`data/raw/<run>/historical.bin`) but none are committed. `docs/SAMPLE_SESSION.md` has a few hex examples that can become seed test vectors. |
| **Three implementations already exist** | 1. Python (`src/whoop4/`): the reference. 2. Web Bluetooth (`web/index.html`): a JS port. 3. A native **Java** Android app (`android/`): live HR + start/stop session tracking in a foreground service. 2 and 3 were added in this fork (see below). |
| **Uncommitted change** | `android/.../MainActivity.java`: a more robust band picker (lists already-connected/paired bands, signal-sorted fallback). It builds; it has not been committed or released. |

### Work added by previous AI agents (this fork, commits `ca45272`…`052e647`)

| Item | Status / confidence |
|---|---|
| `web/index.html`: JS port of packet.py, drain.py and reader.py | Mirrors Python behaviour; commit-before-ACK sync. Reasonable confidence. |
| **`TOGGLE_GENERIC_HR_PROFILE` (0x0E) and `TOGGLE_REALTIME_HR` (0x03), payload `[0x01]`**, sent on every connect (web + Java app) | ⚠ **Unverified.** Taken from memory of the whoomp project's numbering; the source could not be fetched to confirm. They are **not** in this repo's PROTOCOL.md or STATUS.md. Live HR now works for the user, but it is unknown whether these commands are the reason. |
| `REALTIME_DATA` (0x28) HR read from `data[5]` | ⚠ **Guess.** Used only as a labelled "experimental" fallback. Must be verified against captures or removed. |
| `android/`: Java app with a foreground service, session recording to app storage and a resume-after-kill feature | Works on the emulator (no BLE there). The user found and connected their band with it. |

### Inconsistencies in the existing docs/code (do not fix silently; flag)

1. `docs/PROTOCOL.md` "Fix" snippet and the `set_clock.py` docstring still show the **4-byte** SET_CLOCK payload, which the same doc says is silently ignored. The executable code (`drain.py`, `set_clock.py` body, web, Java) uses 5 bytes.
2. `PROTOCOL.md` calls historical byte 15 "Flags"; `packet.py`/STATUS.md treat it as the RR count (0–4). The code is the reference.
3. `PROTOCOL.md` says the 0x180D HR service "works without subscription", while `reader.py` says HR Broadcast must be enabled in the Whoop app. The truth is unknown per firmware state (hence the unverified toggles above).
4. `PROTOCOL.md` lists scripts (`ble_drain.py`, `ble_probe_*.py`, …) that are not in this repo.
5. `monitor/server.py` exposes `send_command` / `send_raw` (arbitrary bytes to the band). Fine as a dev tool; **must not** be ported into the app.

---

## 1. Existing Bluetooth architecture

```
                    ┌────────────────────── Whoop 4.0 ──────────────────────┐
                    │  Standard Heart Rate Service 0x180D                    │
                    │    0x2A37 HR Measurement  (notify)  HR + RR, ~1 Hz      │
                    │                                                        │
                    │  Custom service 61080001-8d6d-82b8-614a-1c8cb0f8dcc6    │
                    │    61080002 CMD_TO     (write)   commands → band       │
                    │    61080003 CMD_FROM   (notify)  responses, metadata    │
                    │    61080005 DATA_FROM  (notify)  history, events, logs  │
                    └────────────────────────────────────────────────────────┘
```

**Framing** (both directions): `[0xAA][len u16 LE][crc8(len)][type][seq][cmd][data…][crc32 LE]`,
`len = payload + 4`. CRC8 poly 0x07; CRC32 standard on writes. The band's own outbound CRC32 uses a
non-standard init/xor and the parsers **do not validate it**. Responses are routed by `type`, then `cmd`.

**Two independent data paths:**

1. **Live**: subscribe to 0x2A37. Standard GATT, no custom protocol. HR (u8/u16) + RR in 1/1024 s.
   Not stored on the band by this path; it is a stream only.
2. **Historical drain**: the band records ~1 record/s to a circular flash buffer (~14 days, ~86k records/day).
   State machine (`drain.py`, the canonical implementation):
   ```
   connect → notify CMD_FROM + DATA_FROM
          → SET_CLOCK [ts u32][0x01]        (always; 4 bytes is silently ignored)
          → SEND_HISTORICAL_DATA [0x00]     (also unlocks COMMAND_RESPONSEs)
          → GET_CLOCK [0x00]                (verify RTC; reboot detection)
          ← HISTORICAL_DATA 0x2F ×N, EVENT 0x30, CONSOLE_LOGS 0x32
          ← METADATA HISTORY_END (trim u32 @ offset 10)
          → HISTORICAL_DATA_RESULT [01][trim][00000000]   (ACK; frees that batch on the band)
          … repeat …
          ← METADATA HISTORY_COMPLETE
   pass ends: COMPLETE | 20 s idle | 10 min cap → reconnect, repeat (≤30 passes, stop after 3 empty)
   ```
   The ACK is **destructive**: it lets the band discard the batch. The web/Java ports commit to storage *before* ACKing.

**RTC trap:** if the band's RTC is invalid, the firmware logs
`Flash: RTC timestamp X is invalid; not saving data to flash` and stores nothing. SET_CLOCK on every connect prevents this.

**Dedup:** `drain.py` keys records by `ISO second + ".NNN"` counter for same-second records. That key depends on
arrival order and resets per process, so it is fragile across interrupted syncs (see risks).

**Authentication/bonding:** none observed. The custom service is accessible after a plain GATT connect; the
official app's exclusive connection is the only obstacle. STATUS.md notes a possible higher-level auth for firmware
update; out of scope and never to be attempted.

---

## 2. Reusable protocol functionality (confidence from STATUS.md)

| Area | Functions (Python reference) | Confidence |
|---|---|---|
| Framing | `WhoopPacket.framed_packet`, `from_data`, `crc8`, `crc32` | High |
| Commands | SET_CLOCK (5 B), GET_CLOCK (1 B, drain-only), SEND_HISTORICAL_DATA, HISTORICAL_DATA_RESULT, ABORT_HISTORICAL_TRANSMITS | High |
| Commands | SET_READ_POINTER 0x21, GET_DATA_RANGE 0x22 (named, payloads undocumented) | Medium. Don't use until payloads are verified. |
| Metadata | HISTORY_START (offsets @4, @8), HISTORY_END (trim @10), HISTORY_COMPLETE | High |
| Historical record | seq @0, ts @4, subsec @8, flags @10, HR @14, RR count @15, RR @16–23, accel xyz f32 @33–44 | High (bytes 0–23, 33–44) |
| Historical record | accel #2 @49–60; bytes 24–32, 45–48, 61–92 | Medium / unknown. **Store raw, don't interpret.** |
| GET_CLOCK response | status, flag, hw_rtc @2, subsec @6 (32768 Hz) | High (0–7) |
| Events | BATTERY_LEVEL 3 (pct + skin temp), TEMPERATURE_LEVEL 17, WRIST_ON 9, WRIST_OFF 10, DOUBLE_TAP 14 | High |
| Events | SENSOR_SNAPSHOT 29 (battery), 32, 63, 101–103, 11, 12, 16 | Low. **Store raw.** |
| Console logs | ASCII from offset 11 of data; "RTC … invalid" detection | High |
| Live HR | standard 0x2A37 parser | High (standard) |
| Drain policy | multi-pass, 20 s idle, 10 min cap, 3 empty passes, reconnect between passes | High (field-tested) |

---

## 3. Proposed Android architecture

**Stack:** Kotlin, Jetpack Compose + Material 3, Coroutines/Flow, Room (KSP), Gradle Kotlin DSL with a version
catalog. Manual dependency injection (a small `AppContainer`) instead of Hilt: fewer moving parts and no kapt.

**Gradle modules** (protocol and analytics are pure Kotlin/JVM so they test fast, without a device):

```
android/
├── app/                 Compose UI, navigation, ViewModels, foreground service, notifications
├── core/protocol/       (JVM)  framing, CRC, packet + record + event parsers, command builders
├── core/ble/            (Android) scanner, GATT client with serialized op queue, connection state machine,
│                          LiveHrSource, SyncEngine (drain state machine), CompanionDeviceManager integration
├── core/data/           (Android) Room DB, DAOs, repositories, raw-packet archive, migrations
└── core/analytics/      (JVM)  RR cleaning, RMSSD, RHR, sleep detection, staging (estimated), strain/TRIMP,
                           zones, recovery, baselines, confidence scoring
```

Dependency direction: `app → ble, data, analytics → protocol`. The UI never touches GATT; it observes
repository/engine `Flow`s.

**BLE layer design**
- One `WhoopConnection` per band: a coroutine state machine (Disconnected → Connecting → Discovering → Ready → Syncing).
  It uses a **serialized GATT operation queue** with per-op timeouts, MTU request (247) and frame reassembly across notifications.
- `SyncEngine`: a faithful port of `drain.py` (passes, idle/cap timeouts, ACK after commit) with **checkpointing**
  in the DB, so an interrupted sync resumes cleanly.
- **CompanionDeviceManager association** for the band. On Samsung this is the most robust way to get
  device-presence wake-ups and permission to start the foreground service from the background, instead of relying on a
  never-ending connection.
- Command allow-list: the app can only send the High-confidence commands from §2. No raw/arbitrary command path in release builds.

**Background strategy (recommended)**: the band already logs everything to flash, so the app does **not** need a
permanent 1 Hz live stream to avoid data loss.
- *Periodic sync*: the foreground service connects, drains, disconnects, then sleeps. Triggered by device presence (CDM)
  plus a schedule (e.g. every 2–4 h) and on app open.
- *Live mode*: continuous HR/RR streaming only while the Live screen is open or a manual session/workout is recording
  (this is the existing Java app's feature, carried over).
- This is far easier on the battery and much more robust against Samsung's background killing than holding a
  connection indefinitely.

**Database (Room, all timestamps UTC epoch ms; local time only at display)**

| Table | Key / uniqueness | Notes |
|---|---|---|
| `device` | address | name, firmware strings if known, last RTC, last battery |
| `raw_packet` | (device, packet hash) | **Every historical/event frame stored raw**, so new decodings can be re-run later (as drain.py does) |
| `hr_sample` | unique (device, ts_ms, subsec, source) | source = HISTORICAL / LIVE; hr; accel x/y/z nullable |
| `rr_interval` | unique (device, beat_ts_ms) | ms value + source + artefact flag |
| `band_event` | unique (device, event clock, type) | battery, skin temp, wrist on/off, raw hex for unknown types |
| `sync_run` | id | start/end, passes, batches, records, last trim, RTC check, outcome, error |
| `sleep_session`, `sleep_stage` | id | method + version + confidence |
| `activity_session` | id | auto-detected or manual (migrated sessions from the Java app) |
| `daily_summary` | (device, local date) | metrics with `method`, `confidence`, `inputs_coverage` |

At ~86k historical rows/day, inserts are batched in transactions per ACK batch. A retention policy
(downsample >90-day-old 1 Hz data to per-minute, keep `raw_packet` optionally) is configurable.

**Analytics: published methods, labelled as estimates**

| Metric | Method | Measured / estimated |
|---|---|---|
| HR, RR | direct from band | Measured |
| RR cleaning | physiological range + successive-difference filter (±20%, Malik-style), artefact % reported | Quality metric |
| HRV | RMSSD (and ln RMSSD) over clean RR in sleep (whole night + last slow-wave-like window) | Measured-derived; confidence from clean-beat coverage |
| Resting HR | lowest stable 5-min mean HR during sleep (the monitor uses 5th percentile; both computable) | Measured-derived |
| Sleep/wake | actigraphy on accel magnitude (Cole–Kripke / Sadeh-style), gated by wrist-on and HR | Estimated |
| Sleep stages | HR + HRV + motion heuristics (cf. Walch et al. 2019). **Low accuracy**, shown only with a clear "estimate" badge, or deferred | Estimated, low confidence |
| Sleep performance | sleep obtained ÷ user-set sleep need | Estimated |
| Respiratory rate | from RR via respiratory sinus arrhythmia | Estimated; later phase, only if validation looks sane |
| HR zones | % heart-rate reserve (Karvonen); HRmax from observed max or Tanaka (208 − 0.7·age) | Derived |
| Strain / cardio load | Edwards / Banister TRIMP; optional log-scaled 0–21 display explicitly labelled as this app's own scale | Estimated |
| Recovery / readiness | z-scores of ln RMSSD and RHR vs a rolling 7–28-day personal baseline (Plews/Buchheit approach) + sleep. **Hidden until ≥4–7 valid nights** ("calibrating") | Estimated |

Each metric carries a `confidence` (from data coverage and artefact rate). A missing input produces "no data", never a number.

**UI**: a dark, premium, ring-and-card dashboard in the spirit of modern recovery apps, with bottom navigation
(Home, Recovery, Sleep, Strain, Live) plus Device settings. All assets, naming, iconography and layouts are original:
no WHOOP logo, wordmark, fonts or copied screens. I suggest an app name without the WHOOP trademark.

---

## 4. What must be ported to Kotlin

1. `packet.py`: CRC8/CRC32, frame build/parse, enums, `parse_historical_record`, `parse_event_packet`.
2. GET_CLOCK response parsing, METADATA parsing (HISTORY_START offsets, HISTORY_END trim), console log reassembly + RTC-invalid detection.
3. `drain.py`: the multi-pass drain state machine, ACK format, timeouts, RTC check, reboot detection (hw_rtc going backwards), raw capture.
4. `reader.py` / 0x2A37 parser: live HR + RR.
5. `scan.py`: identification by service UUID or `WHOOP` name prefix (plus the connected/paired-device handling from the Java app).
6. From the existing Java app: foreground service, session recording/resume, and **migration of the user's saved sessions**.
7. From `monitor/index.html` (analytics only): rolling RMSSD and the RHR 5th-percentile method, as reference formulas.

**Decoder verification:** a small Python script (`tools/golden.py`, added to the repo) runs `packet.py` over
captured raw frames and writes JSON. Kotlin unit tests parse the same frames and must match field-for-field.
Seed vectors come from `SAMPLE_SESSION.md`. Real vectors come from the app's `raw_packet` export after the first real sync on your phone.

---

## 5. Phased plan (each phase ends with tests, a build, a doc update and a commit)

| # | Phase | Deliverable / exit criteria |
|---|---|---|
| 1 | Audit & plan | This document. ✅ (pending approval) |
| 2 | Project setup | Kotlin/Compose/Room multi-module skeleton builds a signed debug APK; version catalog pinned; empty screens + nav; CI-style `./gradlew test assembleDebug` passes. Existing Java app handled per decision D1. |
| 3 | Discovery & connection | Scanner + CDM association, GATT op queue, reconnect, connection state in the UI. Tested on your Samsung. |
| 4 | Live HR + RR | 0x2A37 stream → Live screen; RR display; diagnostics for the unverified 0x03/0x0E toggles (confirm or drop them). |
| 5 | Historical sync | SyncEngine port with commit-before-ACK, checkpoints, resume, read-only mode, sync history. |
| 6 | Decoder parity | `tools/golden.py` + Kotlin tests: Kotlin output == Python output on all captured frames. |
| 7 | Database | Full Room schema, dedup constraints, migrations, raw-packet archive, export (JSONL compatible with drain.py). |
| 8 | Analytics | RR cleaning, RMSSD, RHR, sleep detection, zones, TRIMP, baselines, recovery; unit tests with synthetic + recorded nights; confidence model. |
| 9 | UI | Home / Recovery / Sleep / Strain / Live / Device screens bound to real data only; "calibrating" / "no data" states. |
| 10 | Background & reliability | Periodic + presence-triggered sync, Samsung battery guidance, multi-day soak test on your phone, crash/ANR-free. |
| 11 | Release | Final APK, install + Samsung setup guide, known limitations. |

Phases 3–5 and 10 need **your phone**. The emulator has no Bluetooth. With the phone connected over USB
(`adb`), I can install builds and read logs directly, which will speed things up a lot.

---

## 6. Risks and open technical questions

| Risk | Impact | Mitigation |
|---|---|---|
| 0x03 / 0x0E toggles and the `REALTIME_DATA` HR offset are unverified | Could be no-ops, or have side effects | Phase 4: test with console-log capture; keep only if their effect is demonstrated; never extend to other undocumented commands |
| ACK frees data on the band | Data loss if not committed first; the official app will no longer see ACKed data | Commit-before-ACK (DB transaction), read-only mode, clear warning |
| Live stream and drain at the same time | Unknown whether the band streams 0x2A37 while draining | Test in Phase 5; the design does not depend on it |
| Fragile dedup key (second + arrival counter) | Duplicates/mis-keys after interrupted syncs | Key on (ts, subsec) + band sequence counter; verify the counter's monotonicity on real data |
| Samsung background restrictions ("Sleeping apps", Adaptive battery) | Missed syncs | Periodic connect/drain instead of a permanent connection; CDM presence; in-app guide to Samsung "Never sleeping apps" |
| Android 14/15 foreground-service rules | Service start rejected from the background | Use the connectedDevice type plus a CDM association; handle rejection by deferring to the next foreground/presence event |
| Sleep staging & respiratory rate accuracy | Misleading numbers | Estimates only, with confidence badges; defer if validation fails |
| Recovery baseline needs history | No meaningful score in the first week | "Calibrating" state; never a fabricated score |
| Data volume (~1.2M rows / 14 days) | Slow inserts, large DB | Batched transactions, indices, retention/downsampling |
| Timezones / DST / travel | Wrong day boundaries | UTC storage; "physiological day" anchored to wake time; local time at display only |
| Band RTC drift/reboots | Shifted timestamps | SET_CLOCK each connect, GET_CLOCK drift + reboot detection logged per sync |
| Toolchain: AGP 9.1 (built-in Kotlin) + Compose compiler + KSP/Room | Build friction | Pinned in Phase 2 before any feature work; Google Maven is reachable |
| Trademark/design | Legal exposure if published | Original assets and name; "WHOOP-inspired" style only |

---

## 7. Decisions (approved 2026-11-20)

- **D1, keep both apps.** The Java app moved unchanged to `android-legacy/` (app ID `dev.whoop4.hr`). The new Kotlin app
  lives in `android/` with its own app ID (`dev.whoop4.bloop`), so both install side by side. Android sandboxing means
  Bloop cannot read the legacy app's sessions directly; an export/import path can be added later if wanted.
- **D2, background model.** Bloop connects, downloads and disconnects every few hours (plus when the band is detected
  nearby). Live heart rate/RR is **on demand**: a clear "Go live" control starts a live connection whenever the user wants
  and stops it when they leave or tap stop.
- **D3, ACK policy.** No answer yet. Default: ACK batches (frees band storage), with a read-only switch in settings.
- **D4, name: Bloop.**
- **D5, device testing.** Not available for now (the user works remotely). Device-dependent phases (3–5, 10) will be built
  and unit-tested, but their on-device checks are deferred and explicitly marked untested until the phone can be connected.
