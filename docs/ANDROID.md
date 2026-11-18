# Running on Android (Web Bluetooth)

`web/index.html` is a single-file web app that talks to the Whoop 4.0 strap directly from **Chrome on Android** using Web Bluetooth. You don't need a PC, Python or an app install, and the phone is the BLE central.

It is a JavaScript port of `src/whoop4/packet.py`, `drain.py` and `reader.py`, with the same protocol, the same 5-byte `SET_CLOCK`, the same drain/ACK state machine and the same `hr.jsonl` output format.

```
┌───────────────┐   BLE   ┌──────────────────────────┐  download  ┌──────────────┐
│  Whoop 4.0    │◄───────►│ Chrome on Android        │──────────►│ hr.jsonl     │
│  (your strap) │         │  web/index.html          │           │ events.jsonl │
└───────────────┘         │  └ IndexedDB (on phone)  │           └──────────────┘
                          └──────────────────────────┘
```

## Why not just run the Python?

The Python toolkit uses [`bleak`](https://github.com/hbldh/bleak), which has no working backend under Termux. The browser monitor (`src/whoop4/monitor/`) doesn't help either, because its page only talks to `server.py` over a WebSocket and `server.py` does the BLE. Chrome on Android, however, exposes BLE to web pages through `navigator.bluetooth`, so the protocol was ported to JS.

## Requirements

| | |
|---|---|
| **Phone** | Android 6+ with Bluetooth LE |
| **Browser** | **Chrome** (or another Chromium browser with Web Bluetooth). Firefox and in-app browsers (Gmail, Telegram, …) do **not** support it. |
| **Origin** | The page must be served over **HTTPS** or from **`localhost`**. A plain `http://192.168.x.x` URL will not work. |
| **Permissions** | Chrome will ask for Bluetooth "Nearby devices" permission. On older Android versions **Location** must be on for BLE scanning. |
| **Strap** | Not connected to the official Whoop app (see below). |

## Step 1: Free the strap from the Whoop app

The official app holds the strap's BLE connection. Before connecting, do one of the following:

- Unpair the strap in the Whoop app, **or**
- Force-stop the Whoop app: *Settings → Apps → WHOOP → Force stop*. Disabling its Bluetooth permission keeps it from reconnecting.

If the connection fails or the strap doesn't appear in the picker, this is almost always the cause.

## Step 2: Host the page

Pick one option.

### Option A: GitHub Pages (recommended)

You get a permanent HTTPS URL you can bookmark on the phone.

1. Fork this repo (or push it to a repo you own).
2. On GitHub: *Settings → Pages → Build and deployment → Source: Deploy from a branch → `main` / `(root)`*.
3. Wait a minute, then open on the phone:
   ```
   https://<your-github-user>.github.io/whoop4-ble/web/
   ```

The page is static. Nothing is sent to GitHub or anywhere else, and all data stays in the phone's browser.

### Option B: Serve from your PC over USB (`adb reverse`)

Chrome treats `localhost` as secure, so you can serve the file from your PC and tunnel the port to the phone.

1. On the phone, enable *Developer options → USB debugging* and connect it by USB.
2. On the PC, from the repo root:
   ```bash
   python -m http.server 8000
   adb reverse tcp:8000 tcp:8000
   ```
3. On the phone, open `http://localhost:8000/web/` in Chrome.

The tunnel only exists while USB is connected, but once the page is loaded the BLE session itself doesn't need the PC.

### Option C: Any other HTTPS static host

Netlify, Cloudflare Pages, your own server and so on all work. Upload `web/index.html`; it has no dependencies.

> Opening the file directly (`file:///…/index.html`) does not work on Android. Chrome blocks Web Bluetooth there.

## Step 3: Connect

1. Open the page in Chrome and tap **Connect**.
2. Pick the strap in the system dialog. It is matched by the Whoop service UUID `61080001-…` or a name starting with `WHOOP`.
   - If it isn't listed, tap **"Band not showing in the picker? Show all devices"** at the bottom and pick it from the full list.
3. The status dot turns green and the log shows `Connected`.

Live heart rate starts immediately through the standard Heart Rate Service (`0x180D`).

## Using the page

### Live heart rate

- **BPM**: from the GATT Heart Rate Measurement characteristic, about 1 Hz.
- **Last RR**: most recent beat-to-beat interval, converted from 1/1024 s to ms.
- **RMSSD 60s**: rolling HRV over the RR intervals received in the last 60 seconds.
- The sparkline shows the last ~3 minutes of HR.

Live samples are **not** stored; only synced historical data is. This matches `reader.py` vs `drain.py`.

### Band clock: "Fix & check clock"

1. Sends `SET_CLOCK` with the **5-byte payload** (`[LE u32 unix_ts][0x01]`). The 4-byte form is silently ignored by the firmware; see [PROTOCOL.md](PROTOCOL.md#rtc-management).
2. Briefly opens a historical transmit, because `GET_CLOCK` only answers in that context, reads the RTC back and then aborts.
3. Shows the RTC time with ✓/✗ and the drift against the phone clock.

Use this if a sync comes back with zero records although you've been wearing the strap, or if the log shows:

```
band: Flash: RTC timestamp 32154354 is invalid; not saving data to flash.
```

The page flags that log line automatically. Every sync also sends `SET_CLOCK` first, the same way `drain.py` and the official app do.

### Sync stored data

Tap **Sync** to pull the historical records the strap has stored in flash. About 14 days fit in its buffer.

What happens on each pass (same as `drain.py`):

1. `SET_CLOCK` (5-byte), then `SEND_HISTORICAL_DATA`, then `GET_CLOCK`.
2. The strap streams `HISTORICAL_DATA` packets, each batch ending with a `METADATA / HISTORY_END`.
3. For every `HISTORY_END`, the page **first writes the batch to IndexedDB** and **then** sends the `HISTORICAL_DATA_RESULT` ACK with the trim offset (`<I` at byte 10).
4. The pass ends on `HISTORY_COMPLETE`, after 20 s without data, or after 10 minutes.
5. If not complete, the page disconnects, waits 2 s, reconnects and runs the next pass. It stops after `HISTORY_COMPLETE`, after 3 consecutive passes with no new records, or after 30 passes.

The status panel shows the pass and batch count, records added this sync, the newest record's timestamp and the total stored on the phone.

**ACK batches checkbox.** The ACK is what makes the strap advance to the next batch, and it also frees that data on the strap. Leave it on for normal use. Turned off, you get a single read-only pass and the strap will keep re-sending the same first batch.

> **Why commit-before-ACK matters:** once a batch is ACKed, the strap may discard it. Writing to IndexedDB first means that if Chrome is killed or the connection drops mid-sync, at worst a batch is downloaded twice. It's never lost. Duplicates are skipped by key.

**Keep the screen on and the tab in the foreground during a sync.** The page requests a screen wake lock, but Android may still throttle or drop BLE for a background tab. A full 14-day buffer can take several minutes.

**Stop** aborts the current transmit (`ABORT_HISTORICAL_TRANSMITS`). Batches already received are kept.

### Download and clear

- **hr.jsonl** has one record per line, the same schema as `drain.py`, so the files can be merged:
  ```json
  {"ts":"2026-05-11T09:31:04.000+00:00","hr":60,"rr_ms":[993],"accel":[0.5319,0.3221,0.806]}
  ```
  `ts` is the record's second plus a `.NNN` counter for multiple records in the same second. It is also the dedup key.
- **events.jsonl** holds decoded `EVENT` packets: battery %, skin temperature, `wrist_on` / `wrist_off`, state markers and unknown types with `_raw` hex.
- **Clear** deletes everything stored in this browser. Download first.

Data lives in the browser's IndexedDB for this site's origin. It survives reloads, but it is lost if you clear Chrome's site data. The page asks Chrome for persistent storage to reduce the risk of eviction. A GitHub Pages URL and a `localhost` URL count as different origins, so each has its own separate store.

### Log

Connection events, per-pass results, RTC readings, battery and skin-temp events, and the strap's own firmware console output (`band: …`).

## What about sleep, recovery and strain?

The strap does **not** compute these; Whoop's cloud does. What you get is the raw material:

- `wrist_on` / `wrist_off` events in `events.jsonl`
- ~1 Hz HR and RR intervals, plus accelerometer data, in `hr.jsonl`
- Skin temperature from battery and temperature events

From these you can estimate sleep windows (long low-motion, low-HR periods), resting HR, overnight HRV (RMSSD over RR) and so on. Those are your own estimates, not Whoop's sleep staging.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Web Bluetooth unavailable" banner | Use Chrome and open the page over HTTPS or `localhost`. |
| Strap not in the picker | Force-stop or unpair the Whoop app, move the strap close, tap the strap to wake it, use *Show all devices*, and check that Bluetooth and Location are on. |
| `Connect failed: … GATT …` | The Whoop app still has the connection, or Android has a stale bond. Toggle Bluetooth off and on, then retry. |
| `Heart rate service unavailable` | Live HR won't work, but sync still does. Usually harmless. |
| Sync gets 0 records while you've been wearing the strap | RTC trap. Tap **Fix & check clock**, wear the strap 5–10 min, then sync again. |
| RTC shows ✗ even after fixing | Retry once. If it persists, see [PROTOCOL.md](PROTOCOL.md#rtc-management) and file an issue with the log. |
| Sync stalls or disconnects | Keep the screen on and the tab in the foreground, stay close to the strap, then tap Sync again. Stored batches are kept, so it resumes. |
| The same records every pass with ACK off | Expected; that's read-only mode. Turn ACK on. |

## Debugging from a PC

With the phone on USB and USB debugging enabled, open `chrome://inspect` in desktop Chrome to get the page's DevTools console. `chrome://bluetooth-internals` on the phone shows the raw BLE state.

## Other platforms

The same page works in desktop Chrome and Edge on Windows, macOS, Linux and ChromeOS. It does **not** work on iOS: Safari has no Web Bluetooth, and every iOS browser is Safari underneath. Third-party browsers such as Bluefy are untested.
