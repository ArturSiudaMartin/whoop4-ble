# Whoop HR — Android app

A small native Android app (plain Java, no libraries) that connects to the Whoop 4.0 strap and records
heart-rate sessions **with the screen off or the app in the background**. The web app in `web/` can't do
that, because browsers suspend Web Bluetooth when the tab isn't visible.

## What it does

- **Live heart rate** from the strap: the standard Heart Rate service (0x180D), switched on with the same
  `TOGGLE_GENERIC_HR_PROFILE` / `TOGGLE_REALTIME_HR` commands the web app sends. If that service stays
  silent, it falls back to the strap's `REALTIME_DATA` stream (experimental).
- **Start / Stop tracking**: records every reading until you stop it, then shows the duration, average,
  min and max, plus a chart of HR over time.
- **Runs in the background**: a foreground service (with an ongoing notification that shows live HR and the
  running average, plus a *Stop tracking* button) keeps the Bluetooth connection alive. It holds a partial
  wake lock only while a session is recording. If the band drops out, it reconnects automatically.
- **Survives restarts**: samples are written to disk as they arrive. If Android kills the app or the phone
  reboots mid-session, the session is picked up again the next time the service or the app starts.
- **Past sessions**: tap one to see its chart, share it as CSV, or delete it.

Sessions are stored in the app's private storage (`files/sessions/`).

## Install

Sideload `WhoopHR.apk` (allow *Install unknown apps* for your browser or file manager when asked).

Then, in the app:
1. Force-stop the official Whoop app (it holds the strap's Bluetooth connection).
2. Tap **Connect** and pick the band. The address is remembered.
3. Tap **Allow unrestricted battery use**. Some phones (Samsung, Xiaomi, Huawei…) otherwise kill
   background apps even when they have a foreground service.
4. **Start tracking**. You can now lock the phone.

## Build

Requires the Android SDK (platform 36) and a JDK 17+ (Android Studio's bundled JBR works):

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
JAVA_HOME="/path/to/Android Studio/jbr" ./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk (signed with the debug key, for sideloading)
```
