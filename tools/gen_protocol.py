"""Generate protocol constants for firmware (C++) and the Android app (Kotlin) from the single source
docs/protocol/smarteyex-ble-v1.json, plus shared test vectors computed with independent references
(Python hmac/zlib/audioop). tests: firmware/test/test_generated.py

Run: python3 tools/gen_protocol.py          (rewrites the generated files)
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import struct
import sys
import warnings
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = os.path.join(ROOT, "docs", "protocol", "smarteyex-ble-v1.json")
OUT_H = os.path.join(ROOT, "firmware", "include", "protocol_ids.h")
OUT_KT = os.path.join(ROOT, "app", "src", "main", "java", "com", "xnvalabs", "smarteyex", "data", "glasses", "ProtocolIds.kt")
OUT_VH = os.path.join(ROOT, "firmware", "test", "host", "vectors.h")
OUT_VKT = os.path.join(ROOT, "app", "src", "test", "java", "com", "xnvalabs", "smarteyex", "data", "glasses", "GlassesVectors.kt")
GROUPS = ["commands", "events", "status", "auth", "auth_fail", "provision_fail", "button", "mic_end", "capture_fail", "flags", "charge", "resolutions", "consts"]
NS = {"commands": "cmd", "events": "evt", "status": "st", "auth": "auth", "auth_fail": "authfail", "provision_fail": "provfail", "button": "btn",
      "mic_end": "micend", "capture_fail": "capfail", "flags": "flag", "charge": "charge", "resolutions": "res", "consts": "k"}


def load():
    with open(SPEC, encoding="utf-8") as f:
        return json.load(f)


def gen_h(s) -> str:
    L = ["// Dihasilkan oleh tools/gen_protocol.py dari docs/protocol/smarteyex-ble-v1.json. Jangan diedit manual.", "#pragma once", "#include <stdint.h>", "", "namespace sex {",
         f'constexpr const char* SERVICE_UUID = "{s["service"]}";']
    for name, c in s["characteristics"].items():
        L.append(f'constexpr const char* UUID_{name} = "{c["uuid"]}";')
    L.append(f'constexpr const char* MAC_LABEL_GLASSES = "{s["mac_labels"]["GLASSES"]}";')
    L.append(f'constexpr const char* MAC_LABEL_PHONE = "{s["mac_labels"]["PHONE"]}";')
    for g in GROUPS:
        L.append(f"namespace {NS[g]} {{")
        for k, v in s[g].items():
            L.append(f"constexpr uint32_t {k} = {v}u;")
        L.append("}")
    L.append("}")
    return "\n".join(L) + "\n"


def gen_kt(s) -> str:
    L = ["// Dihasilkan oleh tools/gen_protocol.py dari docs/protocol/smarteyex-ble-v1.json. Jangan diedit manual.",
         "package com.xnvalabs.smarteyex.data.glasses", "", "object ProtocolIds {", f'    const val SERVICE_UUID = "{s["service"]}"']
    for name, c in s["characteristics"].items():
        L.append(f'    const val UUID_{name} = "{c["uuid"]}"')
    L.append(f'    const val MAC_LABEL_GLASSES = "{s["mac_labels"]["GLASSES"]}"')
    L.append(f'    const val MAC_LABEL_PHONE = "{s["mac_labels"]["PHONE"]}"')
    for g in GROUPS:
        L.append(f"    object {NS[g].capitalize()} {{")
        for k, v in s[g].items():
            L.append(f"        const val {k} = {v}")
        L.append("    }")
    L.append("}")
    return "\n".join(L) + "\n"


def hx(b: bytes) -> str:
    return b.hex()


def adpcm_ref(pcm: list, pred: int, idx: int):
    """Reference: Python's audioop IMA ADPCM (high nibble first) re-packed low nibble first, as in our protocol."""
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        import audioop
        frag = struct.pack("<%dh" % len(pcm), *pcm)
        packed, (p2, i2) = audioop.lin2adpcm(frag, 2, (pred, idx))
    out = bytearray()
    for b in packed:
        out.append(((b & 0x0F) << 4) | (b >> 4))      # swap nibbles: low nibble = first sample
    return bytes(out), p2, i2


def vectors(s):
    k = s["consts"]
    v = {}
    v["crc32"] = [{"data": hx(d), "crc": zlib.crc32(d)} for d in (b"", b"123456789", bytes(range(256)), b"SmartEyeX" * 50)]
    key = bytes(range(32))
    v["hmac"] = [{"key": hx(kk), "msg": hx(m), "mac": hx(hmac.new(kk, m, hashlib.sha256).digest())} for kk, m in
                 ((bytes([0x0b] * 20), b"Hi There"), (b"Jefe", b"what do ya want for nothing?"), (key, b"x" * 100), (bytes([0xaa] * 131), b"Test Using Larger Than Block-Size Key - Hash Key First"))]
    dev = bytes.fromhex("a1b2c3d4e5f6")
    nc, ng = bytes(range(0x10, 0x20)), bytes(range(0x80, 0x90))
    mg = hmac.new(key, s["mac_labels"]["GLASSES"].encode() + dev + nc + ng, hashlib.sha256).digest()
    mc = hmac.new(key, s["mac_labels"]["PHONE"].encode() + dev + ng + nc, hashlib.sha256).digest()
    v["auth"] = {"secret": hx(key), "device_id": hx(dev), "nonce_c": hx(nc), "nonce_g": hx(ng), "mtu": 247, "mac_g": hx(mg), "mac_c": hx(mc)}
    import math
    pcm = [int(12000 * math.sin(i * 0.07) + 3000 * math.sin(i * 0.5)) for i in range(k["AUDIO_FRAME_SAMPLES"])]
    pcm[10], pcm[11] = 32767, -32768
    enc, p2, i2 = adpcm_ref(pcm, 0, 0)
    enc2, p3, i3 = adpcm_ref(pcm, -1234, 40)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        import audioop
        dec = audioop.adpcm2lin(bytes(((b & 0x0F) << 4) | (b >> 4) for b in enc2), 2, (-1234, 40))[0]
    v["adpcm"] = {"pcm": pcm, "enc_pred": 0, "enc_idx": 0, "enc": hx(enc), "end_pred": p2, "end_idx": i2,
                  "enc2_pred": -1234, "enc2_idx": 40, "enc2": hx(enc2), "end2_pred": p3, "end2_idx": i3,
                  "dec_pcm": list(struct.unpack("<%dh" % (len(dec) // 2), dec))}
    v["battery"] = [{"mv": mv, "pct": pct} for mv, pct in ((4300, 100), (4200, 100), (4100, 91), (3900, 63), (3700, 30), (3500, 5), (3300, 0), (2900, 0))]
    return v


# Battery curve used by firmware (mV, %), piecewise-linear; also used above to compute the expected points.
CURVE = [(3300, 0), (3500, 5), (3600, 14), (3700, 30), (3800, 48), (3900, 63), (4000, 78), (4100, 91), (4200, 100)]


def batt(mv):
    if mv <= CURVE[0][0]:
        return 0
    if mv >= CURVE[-1][0]:
        return 100
    for (x0, y0), (x1, y1) in zip(CURVE, CURVE[1:]):
        if mv <= x1:
            return int(y0 + (y1 - y0) * (mv - x0) / (x1 - x0))
    return 100


def arr(name, bs):
    return f"static const uint8_t {name}[] = {{{', '.join('0x%02x' % b for b in bs)}}};" if bs else f"static const uint8_t {name}[1] = {{0}};"


def gen_vh(v) -> str:
    L = ["// Dihasilkan oleh tools/gen_protocol.py. Jangan diedit manual.", "#pragma once", "#include <stdint.h>", "#include <stddef.h>", ""]
    for i, c in enumerate(v["crc32"]):
        d = bytes.fromhex(c["data"])
        L += [arr(f"CRC_DATA_{i}", d), f"static const size_t CRC_LEN_{i} = {len(d)};", f"static const uint32_t CRC_VAL_{i} = {c['crc']}u;"]
    L.append(f"static const int CRC_N = {len(v['crc32'])};")
    for i, h in enumerate(v["hmac"]):
        L += [arr(f"HMAC_KEY_{i}", bytes.fromhex(h["key"])), f"static const size_t HMAC_KEYLEN_{i} = {len(bytes.fromhex(h['key']))};",
              arr(f"HMAC_MSG_{i}", bytes.fromhex(h["msg"])), f"static const size_t HMAC_MSGLEN_{i} = {len(bytes.fromhex(h['msg']))};", arr(f"HMAC_MAC_{i}", bytes.fromhex(h["mac"]))]
    L.append(f"static const int HMAC_N = {len(v['hmac'])};")
    a = v["auth"]
    for n in ("secret", "device_id", "nonce_c", "nonce_g", "mac_g", "mac_c"):
        L.append(arr("AUTH_" + n.upper(), bytes.fromhex(a[n])))
    L.append(f"static const uint16_t AUTH_MTU = {a['mtu']};")
    d = v["adpcm"]
    L += ["static const int16_t ADPCM_PCM[] = {" + ", ".join(map(str, d["pcm"])) + "};", f"static const size_t ADPCM_PCM_N = {len(d['pcm'])};",
          arr("ADPCM_ENC", bytes.fromhex(d["enc"])), f"static const int16_t ADPCM_END_PRED = {d['end_pred']}; static const uint8_t ADPCM_END_IDX = {d['end_idx']};",
          arr("ADPCM_ENC2", bytes.fromhex(d["enc2"])), f"static const int16_t ADPCM_ENC2_PRED = {d['enc2_pred']}; static const uint8_t ADPCM_ENC2_IDX = {d['enc2_idx']};",
          f"static const int16_t ADPCM_END2_PRED = {d['end2_pred']}; static const uint8_t ADPCM_END2_IDX = {d['end2_idx']};",
          "static const int16_t ADPCM_DEC2[] = {" + ", ".join(map(str, d["dec_pcm"])) + "};"]
    L.append("struct BattVec { uint16_t mv; uint8_t pct; };")
    L.append("static const BattVec BATT_VECS[] = {" + ", ".join("{%d, %d}" % (b["mv"], b["pct"]) for b in v["battery"]) + "};")
    L.append(f"static const int BATT_N = {len(v['battery'])};")
    return "\n".join(L) + "\n"


def gen_vkt(v) -> str:
    L = ["// Dihasilkan oleh tools/gen_protocol.py. Jangan diedit manual.", "package com.xnvalabs.smarteyex.data.glasses", "", "object GlassesVectors {",
         "    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }"]
    L.append("    val crc = listOf(" + ", ".join(f'Pair("{c["data"]}", {c["crc"]}L)' for c in v["crc32"]) + ")")
    L.append("    val hmac = listOf(" + ", ".join(f'Triple("{h["key"]}", "{h["msg"]}", "{h["mac"]}")' for h in v["hmac"]) + ")")
    a = v["auth"]
    for n in ("secret", "device_id", "nonce_c", "nonce_g", "mac_g", "mac_c"):
        L.append(f'    const val AUTH_{n.upper()} = "{a[n]}"')
    L.append(f"    const val AUTH_MTU = {a['mtu']}")
    d = v["adpcm"]
    L.append("    val adpcmPcm = shortArrayOf(" + ", ".join(f"{x}" for x in d["pcm"]) + ")")
    L.append(f'    const val ADPCM_ENC = "{d["enc"]}"; const val ADPCM_END_PRED = {d["end_pred"]}; const val ADPCM_END_IDX = {d["end_idx"]}')
    L.append(f'    const val ADPCM_ENC2 = "{d["enc2"]}"; const val ADPCM_ENC2_PRED = {d["enc2_pred"]}; const val ADPCM_ENC2_IDX = {d["enc2_idx"]}')
    L.append(f"    const val ADPCM_END2_PRED = {d['end2_pred']}; const val ADPCM_END2_IDX = {d['end2_idx']}")
    L.append("    val adpcmDec2 = shortArrayOf(" + ", ".join(f"{x}" for x in d["dec_pcm"]) + ")")
    L.append("    val battery = listOf(" + ", ".join(f"Pair({b['mv']}, {b['pct']})" for b in v["battery"]) + ")")
    L.append("}")
    return "\n".join(L) + "\n"


def outputs():
    s = load()
    v = vectors(s)
    v["battery"] = [{"mv": b["mv"], "pct": batt(b["mv"])} for b in v["battery"]]
    return {OUT_H: gen_h(s), OUT_KT: gen_kt(s), OUT_VH: gen_vh(v), OUT_VKT: gen_vkt(v)}


if __name__ == "__main__":
    for path, text in outputs().items():
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print("ditulis:", os.path.relpath(path, ROOT))
