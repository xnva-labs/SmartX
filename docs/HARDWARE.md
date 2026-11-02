# Hardware kacamata SmartEyeX

> **Status**: desain acuan di bawah ditulis dari datasheet/pinout papan dan **belum diuji pada perangkat fisik oleh penulis kode**. Bagian "Bring-up" adalah prosedur untuk memverifikasinya satu subsistem demi satu sebelum memakai BLE. Bila perangkat keras Anda berbeda, tambahkan profil papan (lihat bawah) — protokol BLE dan aplikasi tidak berubah.

## Desain acuan A (profil `BOARD_XIAO_ESP32S3_SENSE`)

| Fungsi | Komponen | Alasan |
|---|---|---|
| MCU + BLE + PSRAM | Seeed **XIAO ESP32S3 Sense** | ESP32-S3 (BLE 5), PSRAM 8 MB untuk JPEG, charger LiPo bawaan, ukuran ±21×18 mm |
| Kamera | OV2640 pada expansion board Sense (konektor bawaan) | JPEG di sensor, hemat CPU |
| Mikrofon | Mikrofon **PDM** bawaan expansion board Sense | tanpa komponen tambahan |
| Amplifier | **MAX98357A** (I2S, 3 W) | I2S langsung, tanpa DAC terpisah |
| Speaker | Transduser **bone-conduction** 8 Ω ≥ 1 W | audio lewat tulang pelipis; telinga tetap terbuka |
| Baterai | LiPo 3,7 V 400–600 mAh **dengan sirkuit proteksi** | dicas oleh charger onboard XIAO |
| Tombol | 1 tombol taktil momentary | gestur dan pairing |
| LED kamera | LED terang (putih/merah) + resistor 330 Ω | **wajib terlihat dari luar** |
| Saklar privasi (opsional, disarankan) | SPDT geser | menutup kamera & mikrofon |
| Pembagi tegangan baterai | 2 × 100 kΩ 1% | pembacaan level baterai |

### Pengkabelan (GPIO sesuai `firmware/include/board.h`)

| Sinyal | Pin XIAO | GPIO | Sambungan |
|---|---|---|---|
| Kamera, mikrofon PDM | konektor Sense | 10,11,12,13,14,15,16,17,18,38,39,40,47,48 · mic 41/42 | bawaan, jangan dipakai untuk hal lain |
| Amp BCLK | D8 | 7 | MAX98357A **BCLK** |
| Amp LRC | D9 | 8 | MAX98357A **LRC** |
| Amp DIN | D10 | 9 | MAX98357A **DIN** |
| Tombol | D1 | 2 | ke GND (pull-up internal); juga pembangun dari deep sleep |
| LED kamera | D3 | 4 | anoda via 330 Ω, katoda ke GND, aktif HIGH |
| Saklar privasi | D4 | 5 | ke GND = TUTUP |
| Baterai (ADC) | D0 | 1 | BAT+ → 100 kΩ → simpul → 100 kΩ → GND; simpul ke D0 |
| Amp VIN/GND | 5V/3V3, GND | — | MAX98357A VIN ke 3V3 (volume lebih kecil) atau 5V (via USB saja) |

**Penting untuk keamanan pengguna dan privasi sekitar**

1. **LED kamera bukan sekadar fitur firmware.** Firmware menyalakannya sebelum sensor dihidupkan, tetapi desain produksi sebaiknya menyuplai daya kamera lewat MOSFET yang digerakkan sinyal yang sama dengan LED, sehingga bug firmware tidak dapat mengambil gambar tanpa LED.
2. **Saklar privasi** idealnya memutus daya/CLK kamera dan mikrofon secara fisik. Firmware juga menolak capture/mic saat saklar tertutup dan melaporkannya ke aplikasi.
3. **Baterai LiPo**: gunakan sel dengan proteksi (over-charge/discharge/short). Jangan menaruh sel tanpa isolasi di dekat kulit/pelipis; transduser dan sel menghasilkan panas — uji suhu permukaan sebelum dipakai lama.
4. **Antena**: jaga area di bawah antena PCB XIAO bebas logam dan tidak terhalang baterai.
5. Merekam orang lain diatur hukum setempat. Kepatuhan pemakai dan organisasi dibahas di [Ketentuan Layanan](TERMS.md).

### Profil papan lain

Salin blok `BOARD_XIAO_ESP32S3_SENSE` di `firmware/include/board.h`, beri nama baru, sesuaikan nomor pin, dan tambahkan environment di `platformio.ini` dengan `-DBOARD_NAMA_ANDA`. Pin opsional diset `-1` untuk dinonaktifkan (saklar privasi, ADC baterai, pin status charger); fitur terkait otomatis dilaporkan "tidak ada/tidak diketahui" ke aplikasi. Kamera berbasis ESP32 klasik (mis. modul AI-Thinker) memiliki pin bebas terbatas untuk mikrofon I2S + amp; dukungannya belum disediakan.

## Bring-up (urutan wajib sebelum mencoba BLE)

Flash: `cd firmware && pio run -e xiao_esp32s3_sense -t upload && pio device monitor`. Konsol serial 115200 baud menerima perintah (ketik `help`).

| # | Perintah | Hasil yang benar | Bila gagal |
|---|---|---|---|
| 1 | *(boot)* | `SmartEyeX 0.2.0 siap: SmartEyeX-XXXX …` | periksa USB, memory type `qio_opi` |
| 2 | `info` | `provisioned=0`, `psram` > 0 | PSRAM 0 → flag memory type/board salah |
| 3 | `led` | LED kamera menyala 2 detik, terlihat dari luar | polaritas/resistor/GPIO 4 |
| 4 | `batt` | mV masuk akal (3300–4200), pct 0–100 | pembagi tegangan; 255 = ADC tak terpasang |
| 5 | `cam` | `OK 320x240 … header JPEG=ya` | konektor Sense longgar; kode 3 = init kamera |
| 6 | `mic` | energi kiri/kanan > ~50 saat bertepuk tangan; slot terpilih dilaporkan | kedua ~0 → pin PDM; bila terlalu pelan naikkan `MIC_GAIN` |
| 7 | `spk` | nada 440 Hz terdengar (bone-conduction: tempelkan ke tulang pelipis) | pin I2S, VIN amp, polaritas transduser |
| 8 | `pair` lalu sambungkan dari aplikasi | aplikasi melaporkan "Terhubung dan terautentikasi" | lihat BLE_PROTOCOL; hapus bond lama di Bluetooth Android |

Setelah 1–7 lolos, uji dari aplikasi: tes nada, tes foto, lalu tahan tombol dan bicara.

### Hal yang dikalibrasi sesudah perakitan

- **Slot mikrofon PDM** dipilih otomatis (kiri/kanan) dari energi sinyal; `diag mic` melaporkan pilihannya.
- **Gain mikrofon** (`MIC_GAIN`, default 4) bersifat empiris.
- **Persentase baterai** memakai kurva diskarge LiPo 1 sel piecewise-linear (aproksimasi, ±10%). Pasang fuel gauge (mis. MAX17048) bila butuh akurasi.
- **Orientasi kamera**: `CAM_VFLIP` / `CAM_HMIRROR`.

## Pengerasan produksi

Sebelum menjual unit: aktifkan **flash encryption** dan **NVS encryption** (secret perangkat disimpan di NVS) dan **secure boot v2**; nonaktifkan JTAG (eFuse); kunci `CORE_DEBUG_LEVEL`. OTA update belum ada ([ROADMAP](ROADMAP.md)): update lewat kabel USB.

## Konsumsi daya

Idle terhubung BLE: puluhan mA (belum diukur). Tanpa koneksi 10 menit → deep sleep (bangun dengan tombol). Penggunaan nyata bergantung pada hardware; ukur dengan perangkat Anda.
