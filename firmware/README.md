# Firmware kacamata SmartEyeX

Firmware PlatformIO/Arduino untuk ESP32-S3 (profil acuan: Seeed XIAO ESP32S3 Sense). Menyediakan layanan BLE yang dijelaskan di [docs/BLE_PROTOCOL.md](../docs/BLE_PROTOCOL.md).

```
src/proto/   inti murni (CRC32, SHA-256/HMAC, IMA ADPCM, frame, autentikasi)  ← diuji di host, tanpa Arduino
src/*.cpp    driver: camera, mic, speaker, ble_gatt, button, indicator, power, store, diag, main
include/     board.h (profil papan), protocol_ids.h (DIHASILKAN dari docs/protocol/*.json)
test/host/   tes inti dengan g++ + ASan/UBSan; vectors.h (DIHASILKAN)
```

```bash
make -C test/host test                  # tanpa perangkat
pio run -e xiao_esp32s3_sense           # kompilasi (butuh jaringan untuk toolchain)
pio run -e xiao_esp32s3_sense -t upload && pio device monitor
```

Pengkabelan, bring-up, dan pengerasan produksi: [docs/HARDWARE.md](../docs/HARDWARE.md). **Driver belum dikompilasi dengan toolchain ESP32 oleh penulis** — lihat [STATUS](../docs/STATUS.md); CI membangun firmware.

Versi Arduino-ESP32 dikunci ke 2.0.x (`espressif32@6.9.0`); API BLE/I2S yang dipakai berbeda di 3.x.
