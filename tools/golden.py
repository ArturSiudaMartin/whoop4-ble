#!/usr/bin/env python3
"""
Generate golden test vectors for Bloop's Kotlin protocol decoder from the Python reference.

Every expected value below is produced by the reference code itself:
  - src/whoop4/packet.py      WhoopPacket.from_data, parse_historical_record, parse_event_packet
  - src/whoop4/reader.py      parse_hr_measurement (extracted with `ast`, so bleak is not needed)
  - METADATA / GET_CLOCK / CONSOLE_LOGS decoding mirrors src/whoop4/monitor/server.py
    (decode_whoop_packet) and src/whoop4/drain.py line for line. Those modules can't be imported
    without side effects (server.py opens log files; both need bleak), so the few lines are restated here.

Inputs are real captures from docs/SAMPLE_SESSION.md plus synthetic frames that cover edge cases and
a seeded random sweep. Output: android/core/protocol/src/test/resources/golden/vectors.json

Usage:  python -I tools/golden.py
"""
import ast
import json
import math
import random
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src" / "whoop4"))
from packet import (  # noqa: E402
    WhoopPacket, PacketType, CommandNumber, MetadataType, EventType,
    parse_historical_record, parse_event_packet,
)

OUT = ROOT / "android" / "core" / "protocol" / "src" / "test" / "resources" / "golden" / "vectors.json"


def load_reader_hr_parser():
    src = (ROOT / "src" / "whoop4" / "reader.py").read_text(encoding="utf-8")
    tree = ast.parse(src)
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "parse_hr_measurement")
    ns = {"struct": struct}
    exec(compile(ast.Module(body=[fn], type_ignores=[]), "reader.py", "exec"), ns)
    return ns["parse_hr_measurement"]


parse_hr_measurement = load_reader_hr_parser()


def clean(v):
    """JSON-safe values (NaN/inf would not survive)."""
    if isinstance(v, float) and not math.isfinite(v):
        return str(v)
    if isinstance(v, dict):
        return {k: clean(x) for k, x in v.items()}
    if isinstance(v, list):
        return [clean(x) for x in v]
    return v


def decode(raw: bytes) -> dict:
    """Expected decoding of one notification, per the reference implementation."""
    pkt = WhoopPacket.from_data(raw)
    if pkt is None:
        return {"packet": None}
    out = {"packet": {"type": pkt.type, "seq": pkt.seq, "cmd": pkt.cmd, "data": pkt.data.hex()}}
    if pkt.type == PacketType.HISTORICAL_DATA:
        out["historical"] = parse_historical_record(pkt.data)
    elif pkt.type == PacketType.EVENT:
        out["event"] = parse_event_packet(pkt)
    elif pkt.type == PacketType.METADATA:
        # server.py decode_whoop_packet, METADATA branch
        m = {}
        data = pkt.data
        if pkt.cmd == MetadataType.HISTORY_START and len(data) >= 12:
            m["start_offset"] = struct.unpack_from("<I", data, 4)[0]
            m["end_offset"] = struct.unpack_from("<I", data, 8)[0]
        elif pkt.cmd == MetadataType.HISTORY_END and len(data) >= 14:
            m["trim_offset"] = struct.unpack_from("<I", data, 10)[0]
        out["metadata"] = m
    elif pkt.type == PacketType.COMMAND_RESPONSE and pkt.cmd == CommandNumber.GET_CLOCK:
        # drain.py needs >= 6 bytes for hw_rtc; server.py needs >= 8 to also read sub_second
        c = {}
        if len(pkt.data) >= 6:
            c["hw_rtc"] = struct.unpack_from("<I", pkt.data, 2)[0]
        if len(pkt.data) >= 8:
            c["subsec_ticks"] = struct.unpack_from("<H", pkt.data, 6)[0]
        out["clock"] = c
    elif pkt.type == PacketType.CONSOLE_LOGS:
        # server.py CONSOLE_LOGS branch
        d = pkt.data[11:] if len(pkt.data) >= 11 else pkt.data
        out["console"] = d.decode("ascii", errors="replace").rstrip("\x00")
    return out


def frame(ptype, cmd, data=b"", seq=0):
    return WhoopPacket(ptype, seq, cmd, data).framed_packet()


def hist_record(seq, ts, subsec, hr, rr=(), rr_count=None, accel=(0.0, 0.0, 1.0), size=93, rnd=None):
    b = bytearray(size if size >= 0 else 0)
    def put(fmt, off, *v):
        if off + struct.calcsize(fmt) <= len(b):
            struct.pack_into(fmt, b, off, *v)
    put("<I", 0, seq)
    put("<I", 4, ts)
    put("<H", 8, subsec)
    put("<I", 10, 0x00010203)
    if len(b) > 14:
        b[14] = hr
    if len(b) > 15:
        b[15] = len(rr) if rr_count is None else rr_count
    for i, v in enumerate(rr[:4]):
        put("<H", 16 + 2 * i, v)
    put("<fff", 33, *accel)
    put("<fff", 49, *accel)
    if rnd is not None:
        for i in [j for j in list(range(24, 33)) + list(range(61, len(b))) if j < len(b)]:
            b[i] = rnd.randrange(256)
    return bytes(b)


def event(evt_type, payload=b"", clock=123456, sub=789, declared_len=None, header=True):
    if not header:
        return payload
    plen = len(payload) if declared_len is None else declared_len
    return bytes([0]) + struct.pack("<IHH", clock, sub, plen) + payload


def build_frames():
    F = []
    add = lambda name, raw: F.append({"name": name, "hex": raw.hex(), "expect": clean(decode(raw))})
    TS = 1778513464  # 2026-05-11T15:31:04Z

    # --- real captures from docs/SAMPLE_SESSION.md
    add("sample_session_metadata_1",
        bytes.fromhex("aa2c0052311501465a026a687e060000001000000002000000290000001100000006000000000000000806001e4e5107"))
    add("sample_session_historical",
        bytes.fromhex("aa6400a12f18056da9fc0138f6016a785a805440013c01e10300000000000000207d05ff0019403c8527083f85eba43ef6584e3f0002e4468527083f85eba43ef6584e3f03025902a402590252015004010c020c000000000007000116da000000000000b009ccfa"))
    add("sample_session_metadata_2",
        bytes.fromhex("aa1c00ab311602495a026ab0214b000000227300001a000000000000c1e8af02"))

    # --- invalid / truncated frames
    add("too_short", bytes.fromhex("aa0500"))
    add("wrong_sof", bytes.fromhex("bb0800a8230016001b6a5b8f"))
    add("payload_under_3", bytes.fromhex("aa0600aa2300ffffffff"))
    add("truncated_body", frame(PacketType.HISTORICAL_DATA, 0, hist_record(1, TS, 0, 60))[:40])

    # --- historical records: edge cases
    H = PacketType.HISTORICAL_DATA
    add("hist_basic", frame(H, 0, hist_record(1, TS, 3, 60, rr=(993,), accel=(0.5319, 0.3221, 0.806))))
    add("hist_four_rr", frame(H, 0, hist_record(2, TS, 4, 75, rr=(800, 810, 820, 830))))
    add("hist_rr_out_of_range", frame(H, 0, hist_record(3, TS, 5, 75, rr=(150, 3000, 900))))
    add("hist_rr_count_5", frame(H, 0, hist_record(4, TS, 6, 75, rr=(800, 800, 800, 800), rr_count=5)))
    add("hist_rr_count_exceeds_len", frame(H, 0, hist_record(5, TS, 7, 75, rr=(), rr_count=4, size=20)))
    add("hist_hr_zero", frame(H, 0, hist_record(6, TS, 0, 0)))
    add("hist_hr_221", frame(H, 0, hist_record(7, TS, 0, 221)))
    add("hist_hr_220", frame(H, 0, hist_record(8, TS, 0, 220)))
    add("hist_ts_too_old", frame(H, 0, hist_record(9, 32154354, 0, 60)))
    add("hist_ts_too_new", frame(H, 0, hist_record(10, 1893456001, 0, 60)))
    add("hist_ts_lower_bound", frame(H, 0, hist_record(11, 1577836800, 0, 60)))
    add("hist_accel_out_of_range", frame(H, 0, hist_record(12, TS, 0, 60, accel=(150.0, 0.0, 1.0))))
    add("hist_accel_nan", frame(H, 0, hist_record(13, TS, 0, 60, accel=(float("nan"), 0.0, 1.0))))
    add("hist_accel_rounding", frame(H, 0, hist_record(14, TS, 0, 60, accel=(0.123456, -0.98765, 1.00005))))
    for size in (14, 15, 16, 44, 45):
        add(f"hist_size_{size}", frame(H, 0, hist_record(15, TS, 0, 60, rr=(1000,), size=size)))

    # --- events
    E = PacketType.EVENT
    add("ev_battery", frame(E, EventType.BATTERY_LEVEL, event(3, b"\x02" + struct.pack("<IH", 313, 3805) + b"\x00\x00")))
    add("ev_battery_temp_out_of_range", frame(E, EventType.BATTERY_LEVEL, event(3, b"\x02" + struct.pack("<IH", 313, 1000))))
    add("ev_battery_pct_invalid", frame(E, EventType.BATTERY_LEVEL, event(3, b"\x02" + struct.pack("<IH", 2000, 3805))))
    add("ev_battery_short", frame(E, EventType.BATTERY_LEVEL, event(3, b"\x02\x01\x02")))
    add("ev_temperature", frame(E, EventType.TEMPERATURE_LEVEL, event(17, struct.pack("<h", 365))))
    add("ev_temperature_invalid", frame(E, EventType.TEMPERATURE_LEVEL, event(17, struct.pack("<h", 100))))
    add("ev_temperature_negative", frame(E, EventType.TEMPERATURE_LEVEL, event(17, struct.pack("<h", -50))))
    add("ev_wrist_on", frame(E, EventType.WRIST_ON, event(9)))
    add("ev_wrist_off", frame(E, EventType.WRIST_OFF, event(10)))
    add("ev_double_tap", frame(E, EventType.DOUBLE_TAP, event(14)))
    add("ev_sensor_snapshot", frame(E, EventType.SENSOR_SNAPSHOT, event(29, bytes(6) + struct.pack("<H", 304) + bytes(4))))
    add("ev_sensor_snapshot_invalid_pct", frame(E, EventType.SENSOR_SNAPSHOT, event(29, bytes(6) + struct.pack("<H", 1500) + bytes(4))))
    add("ev_sensor_snapshot_short", frame(E, EventType.SENSOR_SNAPSHOT, event(29, bytes(8))))
    add("ev_state_a", frame(E, 101, event(101, bytes.fromhex("01000000"))))
    add("ev_state_b", frame(E, 102, event(102, bytes.fromhex("01010000"))))
    add("ev_state_c", frame(E, 103, event(103, bytes.fromhex("01010100"))))
    add("ev_state_empty", frame(E, 102, event(102)))
    add("ev_unknown_32", frame(E, 32, event(32, bytes(28) + struct.pack("<f", 0.06))))
    add("ev_unknown_63", frame(E, 63, event(63, bytes(range(10)))))
    add("ev_no_header", frame(E, 9, b"\x00\x01\x02"))
    add("ev_declared_len_too_long", frame(E, EventType.TEMPERATURE_LEVEL, event(17, struct.pack("<h", 365), declared_len=40)))

    # --- metadata
    M = PacketType.METADATA
    add("meta_start", frame(M, MetadataType.HISTORY_START, struct.pack("<IIII", 7, 0x1000, 0x2000, 9)))
    add("meta_start_short", frame(M, MetadataType.HISTORY_START, struct.pack("<II", 7, 0x1000)))
    add("meta_end", frame(M, MetadataType.HISTORY_END, bytes(10) + struct.pack("<I", 0xDEADBEEF) + bytes(4)))
    add("meta_end_short", frame(M, MetadataType.HISTORY_END, bytes(12)))
    add("meta_complete", frame(M, MetadataType.HISTORY_COMPLETE, b""))

    # --- GET_CLOCK responses
    R = PacketType.COMMAND_RESPONSE
    add("clock_sample_session", frame(R, CommandNumber.GET_CLOCK, bytes.fromhex("0001a8e0016a0100050000000000")))
    add("clock_invalid_rtc", frame(R, CommandNumber.GET_CLOCK, bytes([0, 1]) + struct.pack("<IH", 32193226, 1234) + bytes(6)))
    add("clock_short_6", frame(R, CommandNumber.GET_CLOCK, bytes([0, 1]) + struct.pack("<I", TS)))
    add("clock_short_5", frame(R, CommandNumber.GET_CLOCK, bytes(5)))
    add("other_response", frame(R, CommandNumber.SEND_HISTORICAL_DATA, bytes.fromhex("00020b0000")))

    # --- console logs
    C = PacketType.CONSOLE_LOGS
    msg = b"E, 1234: Flash: RTC timestamp 32154354 is invalid; not saving data to flash.\n"
    add("console_rtc_invalid", frame(C, 0, bytes(11) + msg + b"\x00\x00\x00"))
    add("console_short", frame(C, 0, b"hello"))

    # --- realtime (pass-through only; no reference decoder exists)
    add("realtime_passthrough", frame(PacketType.REALTIME_DATA, 0, bytes(range(12))))

    # --- seeded random sweep of historical records
    rnd = random.Random(20261120)
    for i in range(300):
        size = rnd.choice([93, 93, 93, 93, 60, 45, 30, 16])
        rr = tuple(rnd.choice([rnd.randint(150, 2600), rnd.randint(400, 1500)]) for _ in range(rnd.randint(0, 4)))
        accel = tuple(rnd.choice([rnd.uniform(-2, 2), rnd.uniform(-2, 2), rnd.uniform(-200, 200)]) for _ in range(3))
        ts = rnd.choice([TS + rnd.randint(0, 10 ** 6), rnd.randint(0, 2 ** 32 - 1)])
        hr = rnd.choice([rnd.randint(35, 200), rnd.randint(0, 255)])
        rec = hist_record(rnd.randrange(2 ** 32), ts, rnd.randrange(2 ** 16), hr, rr=rr,
                          rr_count=rnd.choice([None, None, None, rnd.randint(0, 255)]), accel=accel, size=size, rnd=rnd)
        add(f"hist_random_{i:03d}", frame(H, rnd.randrange(256), rec, seq=rnd.randrange(256)))
    return F


def build_hr_measurements():
    cases = [
        "103e1904",          # docs/SAMPLE_SESSION.md: flags 0x10, 62 bpm, RR 1024/1024 s
        "0048",              # uint8 HR, no RR
        "014801",            # uint16 HR = 328? (0x0148)
        "1046000400040004",  # 3 RR values
        "1846aabb0004",      # energy expended present + 1 RR
        "1946aa00bbcc0004",  # uint16 HR + energy + RR
        "10460004ff",        # trailing odd byte
        "00",                # too short
    ]
    out = []
    for h in cases:
        raw = bytes.fromhex(h)
        try:
            exp = parse_hr_measurement(bytearray(raw))
        except Exception as e:  # reference raises on malformed input
            exp = {"error": type(e).__name__}
        out.append({"hex": h, "expect": exp})
    return out


def build_commands():
    def f(cmd, data=b""):
        return WhoopPacket(PacketType.COMMAND, 0, cmd, data).framed_packet().hex()
    return [
        {"name": "set_clock", "ts": 1778539127, "hex": f(CommandNumber.SET_CLOCK, struct.pack("<I", 1778539127) + b"\x01")},
        {"name": "get_clock", "hex": f(CommandNumber.GET_CLOCK, b"\x00")},
        {"name": "send_historical_data", "hex": f(CommandNumber.SEND_HISTORICAL_DATA, b"\x00")},
        {"name": "abort_historical_transmits", "hex": f(CommandNumber.ABORT_HISTORICAL_TRANSMITS, b"")},
        {"name": "historical_data_result", "trim": 0xDEADBEEF,
         "hex": f(CommandNumber.HISTORICAL_DATA_RESULT, struct.pack("<BII", 1, 0xDEADBEEF, 0))},
    ]


def main():
    vectors = {
        "generated_by": "tools/golden.py",
        "frames": build_frames(),
        "hr_measurements": build_hr_measurements(),
        "commands": build_commands(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(vectors, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}: {len(vectors['frames'])} frames, "
          f"{len(vectors['hr_measurements'])} HR measurements, {len(vectors['commands'])} commands")


if __name__ == "__main__":
    main()
