package dev.whoop4.hr;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

/** Whoop 4.0 BLE protocol bits — a port of src/whoop4/packet.py (see docs/PROTOCOL.md). */
final class Whoop {
    private Whoop() {}

    static final UUID SVC       = UUID.fromString("61080001-8d6d-82b8-614a-1c8cb0f8dcc6");
    static final UUID CMD_TO    = UUID.fromString("61080002-8d6d-82b8-614a-1c8cb0f8dcc6");
    static final UUID CMD_FROM  = UUID.fromString("61080003-8d6d-82b8-614a-1c8cb0f8dcc6");
    static final UUID DATA_FROM = UUID.fromString("61080005-8d6d-82b8-614a-1c8cb0f8dcc6");
    static final UUID HR_SVC    = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb");
    static final UUID HR_MEAS   = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");
    static final UUID CCCD      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    static final int PT_COMMAND = 0x23, PT_REALTIME_DATA = 0x28;
    // Community RE (whoomp) numbering; payload [0x01] = on.
    static final int CMD_TOGGLE_REALTIME_HR = 0x03, CMD_TOGGLE_GENERIC_HR_PROFILE = 0x0E, CMD_SET_CLOCK = 0x0A;

    private static final int[] CRC8 = new int[256], CRC32 = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 0x80) != 0 ? ((c << 1) ^ 0x07) & 0xFF : (c << 1) & 0xFF;
            CRC8[i] = c;
            c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            CRC32[i] = c;
        }
    }
    static int crc8(byte[] b) { int c = 0; for (byte x : b) c = CRC8[c ^ (x & 0xFF)]; return c; }
    static int crc32(byte[] b) { int c = 0xFFFFFFFF; for (byte x : b) c = CRC32[(c ^ x) & 0xFF] ^ (c >>> 8); return ~c; }

    static byte[] u32le(long v) { return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)}; }

    /** Builds a framed COMMAND packet: [AA][len LE][crc8(len)][23 00 cmd data...][crc32 LE]. */
    static byte[] frame(int cmd, byte... data) {
        byte[] payload = new byte[3 + data.length];
        payload[0] = (byte) PT_COMMAND; payload[1] = 0; payload[2] = (byte) cmd;
        System.arraycopy(data, 0, payload, 3, data.length);
        int len = payload.length + 4;
        byte[] lenB = {(byte) len, (byte) (len >> 8)};
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0xAA); o.write(lenB, 0, 2); o.write(crc8(lenB));
        o.write(payload, 0, payload.length);
        byte[] c = u32le(crc32(payload) & 0xFFFFFFFFL);
        o.write(c, 0, 4);
        return o.toByteArray();
    }

    static byte[] setClock() {
        byte[] ts = u32le(System.currentTimeMillis() / 1000);
        return frame(CMD_SET_CLOCK, ts[0], ts[1], ts[2], ts[3], (byte) 0x01);   // 5 bytes — 4 is silently ignored
    }

    /** Standard GATT Heart Rate Measurement (0x2A37). Returns [hr, rr_ms...]. */
    static int[] parseHrMeasurement(byte[] v) {
        if (v == null || v.length < 2) return new int[]{0};
        int flags = v[0] & 0xFF, i = 1, hr;
        if ((flags & 1) != 0) { hr = (v[1] & 0xFF) | ((v[2] & 0xFF) << 8); i += 2; } else { hr = v[1] & 0xFF; i += 1; }
        if ((flags & 0x08) != 0) i += 2;
        int nRr = (flags & 0x10) != 0 ? Math.max(0, (v.length - i) / 2) : 0;
        int[] out = new int[1 + nRr];
        out[0] = hr;
        for (int k = 0; k < nRr; k++, i += 2) out[1 + k] = Math.round(((v[i] & 0xFF) | ((v[i + 1] & 0xFF) << 8)) * 1000f / 1024f);
        return out;
    }

    /**
     * HR from a REALTIME_DATA (0x28) frame, or -1. The byte offset is not confirmed for every firmware,
     * so the service only uses this when the standard HR service is silent.
     */
    static int realtimeHr(byte[] f) {
        if (f.length < 8 || (f[0] & 0xFF) != 0xAA) return -1;
        int len = (f[1] & 0xFF) | ((f[2] & 0xFF) << 8);
        int payloadEnd = Math.min(f.length, 4 + len - 4);
        if (payloadEnd - 4 < 3 || (f[4] & 0xFF) != PT_REALTIME_DATA) return -1;
        int idx = 4 + 3 + 5;                       // data[5]
        return idx < payloadEnd ? f[idx] & 0xFF : -1;
    }

    /** Reassembles frames split across notifications (small MTU). */
    static final class Assembler {
        interface Sink { void onFrame(byte[] frame); }
        private final Sink sink;
        private ByteArrayOutputStream buf;
        private int total;
        Assembler(Sink sink) { this.sink = sink; }

        void feed(byte[] chunk) {
            if (chunk == null || chunk.length == 0) return;
            if ((chunk[0] & 0xFF) == 0xAA && chunk.length >= 4) {
                int t = 4 + ((chunk[1] & 0xFF) | ((chunk[2] & 0xFF) << 8));
                if (t > 4096) { buf = null; return; }
                if (chunk.length >= t) { buf = null; sink.onFrame(java.util.Arrays.copyOf(chunk, t)); return; }
                buf = new ByteArrayOutputStream(); buf.write(chunk, 0, chunk.length); total = t; return;
            }
            if (buf == null) return;
            buf.write(chunk, 0, chunk.length);
            if (buf.size() >= total) { byte[] all = buf.toByteArray(); buf = null; sink.onFrame(java.util.Arrays.copyOf(all, total)); }
        }
    }
}
