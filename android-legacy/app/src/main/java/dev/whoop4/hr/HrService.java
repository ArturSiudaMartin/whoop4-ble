package dev.whoop4.hr;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Foreground service that owns the BLE connection to the band and records tracking sessions.
 * Because it runs as a foreground service (with an ongoing notification), Android keeps it alive
 * with the screen off and the app in the background. All state lives in {@link Live} and is only
 * touched on the main thread.
 */
@SuppressLint("MissingPermission")
public class HrService extends Service {
    static final String TAG = "WhoopHR";
    static final String ACTION_CONNECT = "connect", ACTION_DISCONNECT = "disconnect",
            ACTION_START_TRACKING = "start_tracking", ACTION_STOP_TRACKING = "stop_tracking", ACTION_RESUME = "resume";
    static final String EXTRA_ADDRESS = "address";
    static final String PREFS = "whoop";
    private static final String CHANNEL = "tracking";
    private static final int NOTIF_ID = 1;

    /** Shared state read by the activity (main thread only). */
    static final class Live {
        static boolean running, connected;
        static String status = "Not connected", deviceName = "", source = "";
        static int hr, rr;
        static long hrAt;
        static boolean tracking;
        static long trackStart, trackEnd;
        static final Samples samples = new Samples();
        static final ArrayDeque<String> log = new ArrayDeque<>();
        static void log(String s) {
            String line = new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + s;
            log.addLast(line);
            while (log.size() > 200) log.removeFirst();
            Log.i(TAG, s);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private BluetoothGatt gatt;
    private String address;
    private boolean userDisconnect;
    private BluetoothGattCharacteristic cmdTo;
    private boolean rediscovered, hrSubscribed;
    private long lastHrsAt;
    private int rtLogged;
    private BufferedWriter sampleOut;
    private int unflushed;
    private long lastNotifAt;
    private PowerManager.WakeLock wakeLock;

    private final Whoop.Assembler asmCmd = new Whoop.Assembler(this::onFrame);
    private final Whoop.Assembler asmData = new Whoop.Assembler(this::onFrame);

    // ------------------------------------------------------------------ lifecycle
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Heart rate tracking", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Live.running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String action = intent != null ? intent.getAction() : null;
        if (action == null || action.equals(ACTION_RESUME)) {
            // Restarted by the system after the process was killed: resume connection + active session.
            address = prefs.getString("address", null);
            long active = prefs.getLong("activeSession", 0);
            if (active > 0) resumeTracking(active);
            if (address != null) connect(); else stopSelfIfIdle();
            return START_STICKY;
        }
        switch (action) {
            case ACTION_CONNECT:
                address = intent.getStringExtra(EXTRA_ADDRESS);
                prefs.edit().putString("address", address).apply();
                connect();
                break;
            case ACTION_DISCONNECT:
                if (Live.tracking) stopTracking();
                userDisconnect = true;
                closeGatt();
                Live.connected = false; Live.status = "Not connected";
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                break;
            case ACTION_START_TRACKING: startTracking(); break;
            case ACTION_STOP_TRACKING:
                stopTracking();
                stopSelfIfIdle();
                break;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (Live.tracking) stopTracking();
        closeGatt();
        Live.running = false; Live.connected = false; Live.status = "Not connected";
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void stopSelfIfIdle() {
        if (!Live.tracking && gatt == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    }

    // ------------------------------------------------------------------ notification
    private void goForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIF_ID, n);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title, text;
        boolean fresh = System.currentTimeMillis() - Live.hrAt < 15000;
        String hrTxt = fresh && Live.hr > 0 ? Live.hr + " bpm" : "-- bpm";
        if (Live.tracking) {
            long dur = System.currentTimeMillis() - Live.trackStart;
            title = "Tracking · " + MainActivity.fmtDur(dur);
            text = hrTxt + (Live.samples.n > 0 ? String.format(Locale.US, " · avg %.0f · min %d · max %d",
                    Live.samples.avg(), Live.samples.min, Live.samples.max) : " · waiting for heart rate");
            if (!Live.connected) text += " · reconnecting…";
        } else {
            title = Live.connected ? "Connected · " + hrTxt : "Whoop HR";
            text = Live.status;
        }
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setContentIntent(open);
        if (Live.tracking) {
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, HrService.class).setAction(ACTION_STOP_TRACKING),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Stop tracking", stop).build());
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void updateNotification(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotifAt < 2000) return;
        lastNotifAt = now;
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
    }

    private void setStatus(String s) { Live.status = s; Live.log(s); updateNotification(true); }

    // ------------------------------------------------------------------ connection
    private void connect() {
        closeGatt();
        userDisconnect = false;
        BluetoothAdapter ad = getSystemService(BluetoothManager.class).getAdapter();
        if (ad == null || !ad.isEnabled()) { setStatus("Bluetooth is off"); retryLater(); return; }
        BluetoothDevice dev;
        try { dev = ad.getRemoteDevice(address); } catch (IllegalArgumentException e) { setStatus("Bad device address"); return; }
        Live.deviceName = dev.getName() != null ? dev.getName() : address;
        setStatus("Connecting to " + Live.deviceName + "…");
        // autoConnect=true: Android keeps trying in the background until the band is in range.
        gatt = dev.connectGatt(this, true, cb, BluetoothDevice.TRANSPORT_LE);
    }

    private void retryLater() {
        main.postDelayed(() -> { if (!userDisconnect && address != null && (gatt == null)) connect(); }, 5000);
    }

    private void closeGatt() {
        ops.clear(); opBusy = false;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        cmdTo = null;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            main.post(() -> {
                if (g != gatt) return;
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Live.connected = true;
                    rediscovered = false; hrSubscribed = false; rtLogged = 0;
                    setStatus("Connected, setting up…");
                    ops.clear(); opBusy = false;
                    enqueue(() -> g.requestMtu(247));
                    enqueue(() -> g.discoverServices());
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Live.connected = false;
                    ops.clear(); opBusy = false; cmdTo = null;
                    if (userDisconnect) return;
                    setStatus("Disconnected (status " + status + "), reconnecting…");
                    // Re-create the GATT client: after some errors autoConnect stops retrying on the old one.
                    try { g.close(); } catch (Exception ignored) {}
                    gatt = null;
                    main.postDelayed(() -> { if (!userDisconnect && gatt == null) connect(); }, 3000);
                }
            });
        }

        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) { main.post(() -> opDone()); }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> { if (g == gatt) { setupServices(g); } opDone(); });
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { main.post(() -> opDone()); }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) { main.post(() -> opDone()); }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            byte[] v = value.clone();
            main.post(() -> onNotify(c.getUuid(), v));
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT >= 33) return;   // the overload above is used on 33+
            byte[] v = c.getValue() != null ? c.getValue().clone() : new byte[0];
            main.post(() -> onNotify(c.getUuid(), v));
        }
    };

    private void setupServices(BluetoothGatt g) {
        BluetoothGattService svc = g.getService(Whoop.SVC);
        if (!rediscovered) {
            if (svc != null) {
                cmdTo = svc.getCharacteristic(Whoop.CMD_TO);
                enableNotify(g, svc.getCharacteristic(Whoop.CMD_FROM));
                enableNotify(g, svc.getCharacteristic(Whoop.DATA_FROM));
                write(g, Whoop.setClock());
                write(g, Whoop.frame(Whoop.CMD_TOGGLE_GENERIC_HR_PROFILE, (byte) 1));
                write(g, Whoop.frame(Whoop.CMD_TOGGLE_REALTIME_HR, (byte) 1));
            } else Live.log("Whoop service not found on this device");
        }
        BluetoothGattService hrs = g.getService(Whoop.HR_SVC);
        if (hrs != null && !hrSubscribed) {
            enableNotify(g, hrs.getCharacteristic(Whoop.HR_MEAS));
            hrSubscribed = true;
            enqueue(() -> { setStatus("Connected · listening for heart rate"); return false; });   // runs after setup, no GATT op
        } else if (hrs == null && !rediscovered) {
            // The HR service may only appear after the toggle commands: look again shortly.
            rediscovered = true;
            enqueue(() -> { main.postDelayed(() -> { if (g == gatt && !g.discoverServices()) opDone(); }, 2500); return true; });
        } else if (hrs == null) {
            setStatus("Connected · no standard HR service, using band stream");
        }
    }

    // ------------------------------------------------------------------ serialized GATT operations
    // Android allows one outstanding GATT operation at a time; each op completes in a callback.
    interface Op { boolean run(); }
    private final ArrayDeque<Op> ops = new ArrayDeque<>();
    private boolean opBusy;
    private int opSeq;

    private void enqueue(Op op) { ops.add(op); next(); }

    private void next() {
        if (opBusy || ops.isEmpty()) return;
        Op op = ops.poll();
        opBusy = true;
        final int seq = ++opSeq;
        boolean started;
        try { started = op.run(); } catch (Exception e) { Live.log("GATT op failed: " + e.getMessage()); started = false; }
        if (!started) { opBusy = false; next(); return; }
        main.postDelayed(() -> { if (opBusy && opSeq == seq) { opBusy = false; next(); } }, 6000);   // safety timeout
    }

    private void opDone() { opBusy = false; next(); }

    @SuppressWarnings("deprecation")
    private void enableNotify(BluetoothGatt g, BluetoothGattCharacteristic ch) {
        if (ch == null) return;
        enqueue(() -> {
            if (!g.setCharacteristicNotification(ch, true)) return false;
            BluetoothGattDescriptor d = ch.getDescriptor(Whoop.CCCD);
            if (d == null) return false;
            byte[] val = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) return g.writeDescriptor(d, val) == BluetoothStatusCodes.SUCCESS;
            d.setValue(val);
            return g.writeDescriptor(d);
        });
    }

    @SuppressWarnings("deprecation")
    private void write(BluetoothGatt g, byte[] bytes) {
        enqueue(() -> {
            BluetoothGattCharacteristic ch = cmdTo;
            if (ch == null) return false;
            int type = (ch.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            if (Build.VERSION.SDK_INT >= 33) return g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS;
            ch.setWriteType(type);
            ch.setValue(bytes);
            return g.writeCharacteristic(ch);
        });
    }

    // ------------------------------------------------------------------ incoming data
    private void onNotify(java.util.UUID uuid, byte[] v) {
        if (Whoop.HR_MEAS.equals(uuid)) {
            int[] p = Whoop.parseHrMeasurement(v);
            lastHrsAt = System.currentTimeMillis();
            onHr(p[0], p.length > 1 ? p[p.length - 1] : 0, "Heart Rate service");
        } else if (Whoop.CMD_FROM.equals(uuid)) asmCmd.feed(v);
        else if (Whoop.DATA_FROM.equals(uuid)) asmData.feed(v);
    }

    private void onFrame(byte[] f) {
        int hr = Whoop.realtimeHr(f);
        if (hr < 0) return;
        if (rtLogged < 3) { rtLogged++; Live.log("realtime pkt: " + hex(f)); }
        if (hr >= 30 && hr <= 220 && System.currentTimeMillis() - lastHrsAt > 3000) onHr(hr, 0, "band stream (experimental)");
    }

    private void onHr(int hr, int rr, String src) {
        if (hr <= 0) return;   // 0 = no skin contact
        long now = System.currentTimeMillis();
        Live.hr = hr; Live.hrAt = now; Live.source = src;
        if (rr > 0) Live.rr = rr;
        if (Live.tracking) {
            Live.samples.add(now, hr);
            if (sampleOut != null) {
                try {
                    sampleOut.write(now + "," + hr + "\n");
                    if (++unflushed >= 10) { sampleOut.flush(); unflushed = 0; }
                } catch (IOException e) { Live.log("Write failed: " + e.getMessage()); }
            }
        }
        updateNotification(false);
    }

    // ------------------------------------------------------------------ tracking
    private void startTracking() {
        if (Live.tracking) return;
        Live.tracking = true;
        Live.trackStart = System.currentTimeMillis(); Live.trackEnd = 0;
        Live.samples.clear();
        openSampleFile(Live.trackStart);
        prefs.edit().putLong("activeSession", Live.trackStart).apply();
        acquireWakeLock();
        Live.log("Tracking started");
        updateNotification(true);
    }

    private void resumeTracking(long start) {
        if (Live.tracking) return;
        Samples s = Store.loadSamples(this, start);
        Live.samples.clear();
        for (int i = 0; i < s.n; i++) Live.samples.add(s.t[i], s.v[i]);
        Live.tracking = true; Live.trackStart = start; Live.trackEnd = 0;
        openSampleFile(start);
        acquireWakeLock();
        Live.log("Resumed tracking session after restart (" + s.n + " samples)");
    }

    private void stopTracking() {
        if (!Live.tracking) return;
        Live.tracking = false;
        Live.trackEnd = System.currentTimeMillis();
        try { if (sampleOut != null) sampleOut.close(); } catch (IOException ignored) {}
        sampleOut = null;
        prefs.edit().remove("activeSession").apply();
        if (Live.samples.n > 0) {
            Store.appendIndex(this, Live.trackStart, Live.trackEnd, Live.samples);
            Live.log(String.format(Locale.US, "Tracking stopped: %s, avg %.0f bpm (min %d, max %d)",
                    MainActivity.fmtDur(Live.trackEnd - Live.trackStart), Live.samples.avg(), Live.samples.min, Live.samples.max));
        } else {
            Store.samplesFile(this, Live.trackStart).delete();
            Live.log("Tracking stopped: no heart rate was received");
        }
        releaseWakeLock();
        updateNotification(true);
    }

    private void openSampleFile(long start) {
        try { sampleOut = new BufferedWriter(new FileWriter(Store.samplesFile(this, start), true)); unflushed = 0; }
        catch (IOException e) { sampleOut = null; Live.log("Cannot open session file: " + e.getMessage()); }
    }

    // A partial wake lock keeps the CPU awake during a session so reconnects and file writes
    // are not delayed by Doze. It is released as soon as tracking stops.
    private void acquireWakeLock() {
        if (wakeLock == null) wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhoopHR:tracking");
        if (!wakeLock.isHeld()) wakeLock.acquire(24 * 3600 * 1000L);
    }
    private void releaseWakeLock() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static void send(Context c, String action, String address) {
        Intent i = new Intent(c, HrService.class).setAction(action);
        if (address != null) i.putExtra(EXTRA_ADDRESS, address);
        c.startForegroundService(i);
    }
}
