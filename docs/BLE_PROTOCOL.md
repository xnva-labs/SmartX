# Protokol BLE kacamata SmartEyeX — v1

Sumber tunggal: [`docs/protocol/smarteyex-ble-v1.json`](protocol/smarteyex-ble-v1.json). Konstanta firmware (C++) dan aplikasi (Kotlin) **dihasilkan** darinya oleh `tools/gen_protocol.py`; CI gagal bila berkas hasil usang. Vektor uji (CRC32, HMAC, autentikasi, ADPCM) dihitung dari implementasi referensi independen (Python `zlib`, `hmac`, `audioop`) dan dipakai oleh tes firmware **dan** tes aplikasi sehingga kedua sisi dijamin sepakat byte demi byte.

## Layanan GATT

Service UUID: `caee0001-0087-43a0-82ed-500a709a9129` (diiklankan; nama perangkat `SmartEyeX-XXXX`, 4 digit terakhir MAC).

| Karakteristik | UUID | Properti | Isi |
|---|---|---|---|
| `DEVICE_INFO` | `caee0002-0087-43a0-82ed-500a709a9129` | read | JSON UTF-8: proto, fw, hw, id, caps |
| `STATUS` | `caee0003-0087-43a0-82ed-500a709a9129` | read, notify | 10-byte status block |
| `AUTH` | `caee0004-0087-43a0-82ed-500a709a9129` | write, notify | Mutual HMAC authentication and provisioning |
| `CONTROL` | `caee0005-0087-43a0-82ed-500a709a9129` | write | Commands phone -> glasses [cmd][seq][len][payload] |
| `EVENT` | `caee0006-0087-43a0-82ed-500a709a9129` | notify | Events glasses -> phone [evt][seq][len][payload] |
| `IMAGE` | `caee0007-0087-43a0-82ed-500a709a9129` | notify | JPEG chunks [xfer u8][offset u32][data] |
| `AUDIO_IN` | `caee0008-0087-43a0-82ed-500a709a9129` | notify | Microphone ADPCM frames glasses -> phone |
| `AUDIO_OUT` | `caee0009-0087-43a0-82ed-500a709a9129` | write_no_response | Speaker ADPCM frames phone -> glasses |

Semua karakteristik mensyaratkan **link terenkripsi** (LE Secure Connections, bonding). Android memulai pairing sistem pada koneksi pertama.

**MTU**: ponsel meminta 247. Ponsel melaporkan MTU hasil negosiasi di pesan `HELLO`; kacamata menolak sesi bila < 185 dan membatasi ukuran paket ke MTU−3. Semua integer little-endian.

## Autentikasi (karakteristik AUTH)

Pesan: `[op][payload]`. Secret 32 byte per perangkat dibuat server saat pendaftaran (atau acak lokal bila tanpa akun) dan ditulis ke kacamata saat pairing.

```
Ponsel                                   Kacamata
HELLO(0x01)  nonce_c[16] mtu[2]  ──────►
                                 ◄────── CHALLENGE(0x81) nonce_g[16] mac_g[32]
   mac_g = HMAC-SHA256(secret, "SEX1-G" ‖ device_id[6] ‖ nonce_c ‖ nonce_g)      ← kacamata membuktikan dirinya
PROOF(0x02)  mac_c[32]           ──────►
   mac_c = HMAC-SHA256(secret, "SEX1-C" ‖ device_id[6] ‖ nonce_g ‖ nonce_c)      ← ponsel membuktikan dirinya
                                 ◄────── AUTH_OK(0x82)
```

- `device_id` = MAC eFuse 6 byte (tampil di `DEVICE_INFO.id`). Perbandingan MAC memakai waktu-konstan.
- Gagal → `AUTH_FAIL(0x83) reason`: 1 tanpa secret, 2 MAC salah, 3 terkunci, 4 status salah, 5 MTU kecil. **5 kegagalan dalam 60 detik mengunci autentikasi selama 60 detik.**
- Sebelum `AUTH_OK`, perintah (`CONTROL`) dan audio keluar ditolak dengan status `NOT_AUTHENTICATED`.
- `PROVISION(0x10) secret[32]`: hanya diterima **saat jendela pairing fisik terbuka** (tombol ditahan 3 dtk saat menyala; berlaku 60 dtk) → `PROVISION_OK(0x90)` / `PROVISION_FAIL(0x93) 1`.
- `FORGET(0x11)` (sesi terautentikasi) menghapus secret → `FORGET_OK(0x91)`.

## Perintah (CONTROL) dan acknowledgment

Frame: `[cmd][seq][len][payload ≤16]`. Setiap perintah dijawab event `CMD_ACK [cmd][seq][status]`.

| Cmd | Kode | Payload | Efek |
|---|---|---|---|
| CAPTURE | 1 | `res u8` (0 QVGA, 1 VGA, 2 SVGA, 3 XGA), `quality u8` (10–63, kecil = bagus) | LED menyala → ambil JPEG → event `CAPTURE_READY` → chunk di IMAGE |
| MIC_START | 2 | `max_ms u16` (100–30000) | stream ADPCM di AUDIO_IN |
| MIC_STOP | 3 | — | hentikan; event `MIC_ENDED` |
| SPEAK_BEGIN | 4 | `stream u8`, `rate u16` (=16000) | buka speaker, kosongkan jitter buffer |
| SPEAK_END | 5 | — | putar sisa buffer lalu berhenti |
| SPEAK_ABORT | 6 | — | berhenti seketika |
| SET_VOLUME | 7 | `0–100` | |
| PING | 8 | — | |
| TONE | 9 | `freq u16` (100–6000), `ms u16` (20–2000), `vol u8` | nada uji/isyarat |

Status: 0 OK · 1 BAD_COMMAND · 2 BAD_PARAMS · 3 BUSY · 4 NOT_AUTHENTICATED · 5 UNSUPPORTED · 6 PRIVACY_BLOCKED · 7 HW_FAILURE · 8 LOW_BATTERY · 9 MTU_TOO_SMALL.

## Event (EVENT)

Frame: `[evt][seq][len][payload]`.

| Event | Kode | Payload |
|---|---|---|
| BUTTON | 1 | `kind`: 1 tekan, 2 panjang*, 3 dobel, 4 tahan-mulai, 5 tahan-selesai (*dicadangkan) |
| CAPTURE_READY | 2 | `xfer u8`, `size u32`, `crc32 u32`, `width u16`, `height u16` |
| CAPTURE_FAILED | 3 | `reason`: 1 privasi, 2 sibuk, 3 init kamera, 4 tanpa frame, 5 transfer |
| MIC_STARTED | 4 | `stream u8`, `rate u16` |
| MIC_ENDED | 5 | `stream u8`, `reason`: 1 stop, 2 timeout, 3 tombol, 4 error |
| CMD_ACK | 6 | `cmd`, `seq`, `status` |
| LOW_BATTERY | 7 | `pct` |
| SPEAK_UNDERRUN | 8 | `stream` |
| PRIVACY | 9 | `1` penutup tertutup, `0` terbuka |
| ERROR | 10 | `code` |

## Data

- **IMAGE**: `[xfer u8][offset u32][data…]`, berurutan; ponsel memverifikasi total byte dan **CRC32**. Gagal → ambil ulang.
- **Audio** (AUDIO_IN dan AUDIO_OUT): `[stream u8][seq u16][predictor s16][step_index u8][ADPCM…]`. IMA ADPCM 4-bit, sampel pertama di nibble rendah, 16 kHz mono, **320 sampel/frame (20 ms) = 160 byte** → paket 166 byte. Header predictor/index per frame membuat frame hilang tidak merusak frame berikutnya; sisi penerima mengganti frame hilang dengan senyap (maks 5).
- **STATUS** (10 byte): `proto, battery%, charge, flags, battery_mV u16, uptime_s u32`. Flags: 1 kamera aktif · 2 mic aktif · 4 speaker aktif · 8 terautentikasi · 16 mode pairing · 32 baterai rendah · 64 penutup privasi tertutup · 128 sudah dipasangkan. Dikirim saat flag berubah dan tiap 30 detik. Charge: 0 baterai, 1 mengisi, 2 penuh, 255 tidak diketahui. Baterai 255 = tidak terukur.
- **DEVICE_INFO**: JSON UTF-8 `{"proto":1,"fw":"0.2.0","hw":"xiao-esp32s3-sense","id":"A1B2C3D4E5F6","caps":[…]}`.

## Gestur tombol (diterjemahkan aplikasi)

Tahan → push-to-talk (`MIC_START` saat HOLD_START, `MIC_STOP` saat HOLD_END) · tekan sekali → foto dan jelaskan · tekan dua kali → foto resolusi tinggi dan bacakan teks.

## Properti keamanan dan batasnya

- Autentikasi timbal-balik berbasis secret mencegah kacamata palsu dan ponsel asing memakai kacamata.
- Kerahasiaan dan integritas data frame bergantung pada enkripsi link BLE (LE Secure Connections).
- **Batas**: pairing "Just Works" tidak melindungi dari penyerang aktif yang berada di jangkauan *tepat selama jendela pairing 60 detik* (ia bisa menjadi perantara dan ikut mengetahui secret). Mitigasi: jendela dibuka hanya oleh tombol fisik, singkat, ditandai LED, dan hanya sekali per pairing. Penguatan lebih lanjut (passkey/NFC out-of-band) ada di [ROADMAP](ROADMAP.md).
- Secret di NVS tidak terenkripsi kecuali **flash encryption + NVS encryption** diaktifkan pada unit produksi (lihat [HARDWARE.md](HARDWARE.md#pengerasan-produksi)).
- Kerahasiaan per-frame di atas enkripsi link (kunci sesi dari secret) belum ada; versi protokol berikutnya dapat menambahkannya tanpa mengubah GATT (naikkan `proto`).

## Versi

`DEVICE_INFO.proto` dan byte pertama STATUS memuat versi protokol. Aplikasi menolak perangkat dengan versi berbeda. Perubahan yang tidak kompatibel menaikkan versi dan service UUID dipertahankan; perubahan baru yang kompatibel hanya menambah kode.
