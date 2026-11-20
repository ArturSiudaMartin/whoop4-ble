package dev.whoop4.hr;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private TextView status, hrBig, hrSrc, rr, trkDur, trkAvg, trkMinMax, trkInfo, logView, batteryHint;
    private Button btnConnect, btnPick, btnTrack, btnBattery;
    private ChartView chart;
    private LinearLayout history;
    private int historyCount = -1;

    // ------------------------------------------------------------------ setup
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(HrService.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        setContentView(buildUi());
        // A session that was still recording when the app was force-stopped or the phone rebooted: pick it back up.
        if (prefs.getLong("activeSession", 0) > 0 && !HrService.Live.running) {
            try { HrService.send(this, HrService.ACTION_RESUME, null); } catch (Exception ignored) {}
        }
    }

    @Override protected void onResume() { super.onResume(); tick.run(); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        // Edge-to-edge (enforced on Android 15+): pad content away from system bars.
        scroll.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars());
                top = i.top; bottom = i.bottom;
            } else { top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom(); }
            scroll.setPadding(0, top, 0, 0);   // content scrolls under nothing: clipped below the status bar
            root.setPadding(p, p, p, p + bottom);
            return in;
        });

        // header
        LinearLayout head = Ui.row(this);
        TextView title = Ui.text(this, 20, Ui.FG, true);
        title.setText("Whoop HR");
        head.addView(title, Ui.weight(this, 0));
        btnConnect = Ui.button(this, "Connect", true);
        head.addView(btnConnect);
        root.addView(head);
        status = Ui.text(this, 13, Ui.MUTED, false);
        status.setPadding(0, Ui.dp(this, 4), 0, 0);
        root.addView(status);

        // live HR
        LinearLayout live = Ui.card(this, "Live heart rate");
        LinearLayout hrRow = Ui.row(this);
        hrRow.setGravity(Gravity.BOTTOM);
        hrBig = Ui.text(this, 56, Ui.ACCENT, true);
        hrBig.setText("--");
        hrBig.setIncludeFontPadding(false);
        TextView bpm = Ui.text(this, 15, Ui.MUTED, false);
        bpm.setText("  bpm");
        hrRow.addView(hrBig); hrRow.addView(bpm);
        live.addView(hrRow);
        rr = Ui.text(this, 13, Ui.MUTED, false);
        live.addView(rr);
        hrSrc = Ui.text(this, 13, Ui.MUTED, false);
        live.addView(hrSrc);
        root.addView(live);

        // tracking
        LinearLayout trk = Ui.card(this, "Tracking session");
        LinearLayout stats = Ui.row(this);
        trkDur = Ui.stat(this, stats, "Duration");
        trkAvg = Ui.stat(this, stats, "Average");
        trkMinMax = Ui.stat(this, stats, "Min / Max");
        trk.addView(stats);
        chart = new ChartView(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190));
        clp.topMargin = Ui.dp(this, 10);
        trk.addView(chart, clp);
        trkInfo = Ui.text(this, 13, Ui.MUTED, false);
        trkInfo.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 10));
        trk.addView(trkInfo);
        btnTrack = Ui.button(this, "Start tracking", true);
        trk.addView(btnTrack);
        TextView bgNote = Ui.text(this, 12, Ui.MUTED, false);
        bgNote.setText("Recording continues with the screen off or the app in the background. Stop it here or from the notification.");
        bgNote.setPadding(0, Ui.dp(this, 8), 0, 0);
        trk.addView(bgNote);
        root.addView(trk);

        // battery optimisation
        LinearLayout bat = Ui.card(this, "Background");
        batteryHint = Ui.text(this, 13, Ui.MUTED, false);
        bat.addView(batteryHint);
        btnBattery = Ui.button(this, "Allow unrestricted battery use", false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 10);
        bat.addView(btnBattery, blp);
        root.addView(bat);

        // history
        LinearLayout hist = Ui.card(this, "Past sessions");
        history = new LinearLayout(this);
        history.setOrientation(LinearLayout.VERTICAL);
        hist.addView(history);
        root.addView(hist);

        // band + log
        LinearLayout band = Ui.card(this, "Band");
        btnPick = Ui.button(this, "Choose a different band", false);
        band.addView(btnPick);
        logView = Ui.text(this, 11, Ui.MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0, Ui.dp(this, 10), 0, 0);
        logView.setTextIsSelectable(true);
        band.addView(logView);
        TextView tip = Ui.text(this, 12, Ui.MUTED, false);
        tip.setText("Force-stop the official Whoop app first — it holds the band's Bluetooth connection.");
        tip.setPadding(0, Ui.dp(this, 10), 0, 0);
        band.addView(tip);
        root.addView(band);

        btnConnect.setOnClickListener(v -> onConnectClick());
        btnPick.setOnClickListener(v -> withPermissions(this::scanAndPick));
        btnTrack.setOnClickListener(v -> onTrackClick());
        btnBattery.setOnClickListener(v -> requestBatteryExemption());
        return scroll;
    }

    // ------------------------------------------------------------------ actions
    private void onConnectClick() {
        if (HrService.Live.running && !HrService.Live.status.equals("Not connected")) {
            if (HrService.Live.tracking) {
                new AlertDialog.Builder(this).setMessage("Disconnecting stops and saves the current tracking session.")
                        .setPositiveButton("Disconnect", (d, w) -> HrService.send(this, HrService.ACTION_DISCONNECT, null))
                        .setNegativeButton("Cancel", null).show();
            } else HrService.send(this, HrService.ACTION_DISCONNECT, null);
            return;
        }
        withPermissions(() -> {
            String addr = prefs.getString("address", null);
            if (addr != null) HrService.send(this, HrService.ACTION_CONNECT, addr);
            else scanAndPick();
        });
    }

    private void onTrackClick() {
        if (HrService.Live.tracking) { HrService.send(this, HrService.ACTION_STOP_TRACKING, null); return; }
        withPermissions(() -> {
            HrService.send(this, HrService.ACTION_START_TRACKING, null);
            String addr = prefs.getString("address", null);
            if (!HrService.Live.connected && addr != null && HrService.Live.status.equals("Not connected"))
                HrService.send(this, HrService.ACTION_CONNECT, addr);
            if (!isIgnoringBattery()) toast("Tip: allow unrestricted battery use so Android never pauses recording.");
        });
    }

    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private boolean isIgnoringBattery() {
        return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
    }

    private void toast(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show(); }

    // ------------------------------------------------------------------ permissions
    private Runnable afterPerms;

    private void withPermissions(Runnable r) {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) { need.add(Manifest.permission.BLUETOOTH_SCAN); need.add(Manifest.permission.BLUETOOTH_CONNECT); }
        else need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) { r.run(); return; }
        afterPerms = r;
        requestPermissions(missing.toArray(new String[0]), 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean btOk = true;
        for (int i = 0; i < perms.length; i++) {
            if (res[i] != PackageManager.PERMISSION_GRANTED && !perms[i].equals(Manifest.permission.POST_NOTIFICATIONS)) btOk = false;
        }
        if (btOk && afterPerms != null) afterPerms.run();
        else if (!btOk) toast("Bluetooth permission is needed to talk to the band.");
        afterPerms = null;
    }

    // ------------------------------------------------------------------ scanning
    private void scanAndPick() {
        BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
        if (ad == null) { toast("This phone has no Bluetooth."); return; }
        if (!ad.isEnabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) { toast("Bluetooth scanner unavailable."); return; }

        Map<String, String> whoop = new LinkedHashMap<>(), other = new LinkedHashMap<>();
        TextView msg = Ui.text(this, 14, Ui.FG, false);
        int p = Ui.dp(this, 20);
        msg.setPadding(p, p, p, 0);
        msg.setText("Searching for your band… (10 s)");
        AlertDialog[] dlg = new AlertDialog[1];
        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String name = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null;
                if (name == null) name = d.getName();
                List<ParcelUuid> ids = r.getScanRecord() != null ? r.getScanRecord().getServiceUuids() : null;
                boolean isWhoop = (name != null && name.toUpperCase(Locale.US).contains("WHOOP"))
                        || (ids != null && ids.contains(new ParcelUuid(Whoop.SVC)));
                if (isWhoop) whoop.put(d.getAddress(), name != null ? name : d.getAddress());
                else if (name != null) other.put(d.getAddress(), name);
                msg.setText("Searching for your band… found " + whoop.size() + " Whoop band(s)");
            }
        };
        sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb);
        dlg[0] = new AlertDialog.Builder(this).setTitle("Choose band").setView(msg)
                .setNegativeButton("Cancel", (d, w) -> {}).setOnDismissListener(d -> { try { sc.stopScan(cb); } catch (Exception ignored) {} })
                .show();
        ui.postDelayed(() -> {
            try { sc.stopScan(cb); } catch (Exception ignored) {}
            if (!dlg[0].isShowing()) return;
            dlg[0].dismiss();
            Map<String, String> list = whoop.isEmpty() ? other : whoop;
            if (list.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("No band found")
                        .setMessage("Make sure the band is nearby and the official Whoop app is force-stopped, then try again.")
                        .setPositiveButton("OK", null).show();
                return;
            }
            String[] addrs = list.keySet().toArray(new String[0]);
            String[] labels = new String[addrs.length];
            for (int i = 0; i < addrs.length; i++) labels[i] = list.get(addrs[i]) + "\n" + addrs[i];
            new AlertDialog.Builder(this).setTitle(whoop.isEmpty() ? "No Whoop found — all devices" : "Choose band")
                    .setItems(labels, (d, i) -> {
                        prefs.edit().putString("address", addrs[i]).apply();
                        HrService.send(this, HrService.ACTION_CONNECT, addrs[i]);
                    }).setNegativeButton("Cancel", null).show();
        }, 10000);
    }

    // ------------------------------------------------------------------ rendering
    private void refresh() {
        boolean active = HrService.Live.running && !HrService.Live.status.equals("Not connected");
        status.setText(HrService.Live.running ? HrService.Live.status : "Not connected");
        status.setTextColor(HrService.Live.connected ? Ui.OK : Ui.MUTED);
        btnConnect.setText(active ? "Disconnect" : "Connect");

        long now = System.currentTimeMillis();
        boolean fresh = now - HrService.Live.hrAt < 10000 && HrService.Live.hr > 0;
        hrBig.setText(fresh ? String.valueOf(HrService.Live.hr) : "--");
        rr.setText(fresh && HrService.Live.rr > 0 ? "Last RR " + HrService.Live.rr + " ms" : "");
        if (HrService.Live.connected) {
            hrSrc.setText(fresh ? "Source: " + HrService.Live.source
                    : HrService.Live.hrAt == 0 ? "Waiting for the band… (wear it snugly, sensor on skin)" : "No reading for " + (now - HrService.Live.hrAt) / 1000 + " s");
        } else hrSrc.setText("");

        // tracking
        Samples s = HrService.Live.samples;
        boolean tracking = HrService.Live.tracking;
        btnTrack.setText(tracking ? "Stop tracking" : "Start tracking");
        if (HrService.Live.trackStart > 0) {
            long end = tracking ? now : HrService.Live.trackEnd;
            trkDur.setText(fmtDur(end - HrService.Live.trackStart));
            trkAvg.setText(s.n > 0 ? Math.round(s.avg()) + " bpm" : "--");
            trkMinMax.setText(s.n > 0 ? s.min + " / " + s.max : "--");
            chart.set(s, HrService.Live.trackStart, tracking ? 0 : HrService.Live.trackEnd,
                    tracking ? "Waiting for heart rate…" : "No heart rate recorded");
            trkInfo.setText(tracking
                    ? "Recording since " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(HrService.Live.trackStart))
                      + " · " + s.n + " samples" + (HrService.Live.connected ? "" : " · ⚠ band not connected")
                    : "Last session saved below.");
        } else {
            trkDur.setText("0:00"); trkAvg.setText("--"); trkMinMax.setText("--");
            chart.set(null, 0, 0, "No session yet");
            trkInfo.setText("Press Start to record your heart rate until you press Stop.");
        }

        boolean ign = isIgnoringBattery();
        batteryHint.setText(ign ? "✓ Unrestricted battery use is allowed — Android won't pause recording."
                : "Android may pause the app in the background to save battery. Allow unrestricted use so long sessions aren't cut off.");
        btnBattery.setVisibility(ign ? View.GONE : View.VISIBLE);

        List<Store.Session> sessions = Store.sessions(this);
        if (sessions.size() != historyCount) { historyCount = sessions.size(); renderHistory(sessions); }

        StringBuilder sb = new StringBuilder();
        Object[] lines = HrService.Live.log.toArray();
        for (int i = Math.max(0, lines.length - 12); i < lines.length; i++) sb.append(lines[i]).append('\n');
        logView.setText(sb.toString().trim());
    }

    private void renderHistory(List<Store.Session> sessions) {
        history.removeAllViews();
        if (sessions.isEmpty()) {
            TextView t = Ui.text(this, 13, Ui.MUTED, false);
            t.setText("No sessions yet.");
            history.addView(t);
            return;
        }
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        for (Store.Session s : sessions) {
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView a = Ui.text(this, 14, Ui.FG, false);
            a.setText(df.format(new Date(s.start)));
            TextView b = Ui.text(this, 12, Ui.MUTED, false);
            b.setText(fmtDur(s.end - s.start) + " · min " + s.min + " · max " + s.max);
            left.addView(a); left.addView(b);
            r.addView(left, Ui.weight(this, 0));
            TextView avg = Ui.text(this, 18, Ui.ACCENT, true);
            avg.setText(Math.round(s.avg) + " bpm");
            r.addView(avg);
            r.setOnClickListener(v -> showSession(s));
            history.addView(r);
        }
    }

    private void showSession(Store.Session s) {
        Samples data = Store.loadSamples(this, s.start);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p / 2, p, 0);
        TextView info = Ui.text(this, 14, Ui.FG, false);
        info.setText(String.format(Locale.getDefault(), "Average %d bpm\nMin %d · Max %d\nDuration %s · %d samples",
                Math.round(s.avg), s.min, s.max, fmtDur(s.end - s.start), s.n));
        box.addView(info);
        ChartView cv = new ChartView(this);
        cv.set(data, s.start, s.end, "No samples");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220));
        lp.topMargin = Ui.dp(this, 12);
        box.addView(cv, lp);
        new AlertDialog.Builder(this)
                .setTitle(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(s.start)))
                .setView(box)
                .setPositiveButton("Close", null)
                .setNeutralButton("Share CSV", (d, w) -> shareCsv(s, data))
                .setNegativeButton("Delete", (d, w) -> new AlertDialog.Builder(this).setMessage("Delete this session?")
                        .setPositiveButton("Delete", (d2, w2) -> { Store.delete(this, s.start); historyCount = -1; refresh(); })
                        .setNegativeButton("Cancel", null).show())
                .show();
    }

    private void shareCsv(Store.Session s, Samples data) {
        StringBuilder sb = new StringBuilder("time_iso,elapsed_s,hr_bpm\n");
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        for (int i = 0; i < data.n; i++)
            sb.append(iso.format(new Date(data.t[i]))).append(',')
              .append(String.format(Locale.US, "%.1f", (data.t[i] - s.start) / 1000.0)).append(',').append(data.v[i]).append('\n');
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "HR session " + new Date(s.start))
                .putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(send, "Share session CSV"));
    }

    static String fmtDur(long ms) {
        long sec = Math.max(0, ms / 1000), h = sec / 3600, m = (sec / 60) % 60, ss = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, ss) : String.format(Locale.US, "%d:%02d", m, ss);
    }
}
