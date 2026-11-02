# Instalasi

## 1. Backend

**Pengembangan lokal** — lihat blok "Mulai cepat" di [README](../README.md). Tes: `python -m unittest discover -s tests -t .` (butuh Python 3.12; dependensi di `backend/requirements-dev.txt`).

**Produksi** — [OPERATIONS.md](OPERATIONS.md). Buat organisasi pertama dan isi `XNAI_*` ([CONFIGURATION.md](CONFIGURATION.md)).

Verifikasi: `GET /v1/status` → `xnai_chat: "configured"` bila penyedia model terhubung.

## 2. Aplikasi Android

Prasyarat: JDK 17, Android SDK 36, Gradle 8.14+ (atau Android Studio terbaru).

```bash
gradle :app:testDebugUnitTest         # tes protokol (vektor yang sama dengan firmware)
gradle :app:assembleDebug             # APK debug → app/build/outputs/apk/debug/
# default alamat server saat build (opsional): -PXNVA_API_BASE_URL=https://api.domain-anda.com
```

Emulator + backend lokal: jalankan backend di port 8080, bangun debug dengan `-PXNVA_API_BASE_URL=http://10.0.2.2:8080` (HTTP hanya diizinkan untuk build debug ke alamat lokal).

Rilis bertanda tangan: [PLAY_STORE.md](PLAY_STORE.md). BLE butuh perangkat fisik (emulator tidak mendukung).

Alur pertama kali: Welcome → pilih Personal/Enterprise → atur server → masuk. Kacamata: menu **Device Management** → *Cari kacamata*.

## 3. Firmware kacamata

Prasyarat: PlatformIO (`pip install platformio`), kabel USB-C, perakitan sesuai [HARDWARE.md](HARDWARE.md).

```bash
cd firmware
make -C test/host test                                   # tes inti protokol (g++, tanpa perangkat)
pio run -e xiao_esp32s3_sense -t upload && pio device monitor
```

Lalu jalankan prosedur **bring-up** di HARDWARE.md. Pairing pertama: tahan tombol sambil menyalakan ±3 detik (LED berkedip cepat), lalu *Cari kacamata* di aplikasi.

## Mengubah protokol

Edit `docs/protocol/smarteyex-ble-v1.json`, jalankan `python3 tools/gen_protocol.py` (menulis ulang konstanta C++/Kotlin dan vektor uji), jalankan semua tes, commit hasil generator.

## Mengubah API

Edit tabel `E` di `backend/tools/gen_openapi.py`, jalankan `python -m tools.gen_openapi` di folder `backend`. Tes menolak dokumentasi yang tidak sinkron dengan rute Flask.
