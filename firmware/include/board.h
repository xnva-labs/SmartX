#pragma once
#include <stdint.h>

#define FW_VERSION "0.2.0"

// ---------------------------------------------------------------------------------------------------------------------
// Profil papan. Profil acuan: Seeed XIAO ESP32S3 Sense (kamera OV2640 + mikrofon PDM bawaan) + amplifier I2S MAX98357A
// untuk transduser bone-conduction. Pengkabelan lengkap: docs/HARDWARE.md. Profil lain: salin blok ini, ganti nomor pin,
// dan pilih dengan build flag -DBOARD_NAMA_ANDA (lihat firmware/README.md).
// ---------------------------------------------------------------------------------------------------------------------
#if defined(BOARD_XIAO_ESP32S3_SENSE)
#define BOARD_NAME "xiao-esp32s3-sense"

// Kamera (konektor expansion board Sense)
#define CAM_PIN_PWDN -1
#define CAM_PIN_RESET -1
#define CAM_PIN_XCLK 10
#define CAM_PIN_SIOD 40
#define CAM_PIN_SIOC 39
#define CAM_PIN_D0 15  // Y2
#define CAM_PIN_D1 17  // Y3
#define CAM_PIN_D2 18  // Y4
#define CAM_PIN_D3 16  // Y5
#define CAM_PIN_D4 14  // Y6
#define CAM_PIN_D5 12  // Y7
#define CAM_PIN_D6 11  // Y8
#define CAM_PIN_D7 48  // Y9
#define CAM_PIN_VSYNC 38
#define CAM_PIN_HREF 47
#define CAM_PIN_PCLK 13
#define CAM_VFLIP 0
#define CAM_HMIRROR 0

// Mikrofon PDM bawaan Sense
#define MIC_PIN_PDM_CLK 42
#define MIC_PIN_PDM_DATA 41
#define MIC_GAIN 4  // pengali digital (saturasi); mikrofon PDM keluarannya pelan. Sesuaikan lewat `diag mic`.

// Amplifier I2S (MAX98357A) ke transduser bone-conduction
#define SPK_PIN_BCLK 7  // D8
#define SPK_PIN_LRC 8   // D9
#define SPK_PIN_DIN 9   // D10

// Input/Output
#define PIN_BUTTON 2         // D1, ke GND, pull-up internal; juga pembangun dari deep sleep
#define PIN_CAMERA_LED 4     // D3, LED merah/putih ke GND lewat resistor 330 ohm, aktif HIGH. WAJIB terlihat dari luar.
#define PIN_STATUS_LED 21    // LED bawaan XIAO
#define STATUS_LED_ACTIVE_LOW 1
#define PIN_PRIVACY_SWITCH 5  // D4, saklar geser ke GND = TUTUP (kamera+mikrofon diblokir). -1 bila tidak dipasang.
#define PIN_BATTERY_ADC 1     // D0, pembagi tegangan 2 x 100k dari BAT+. -1 bila tidak dipasang.
#define BATTERY_DIVIDER_RATIO 2.0f
#define PIN_CHARGE_STATUS -1  // opsional: pin STAT charger (aktif LOW = mengisi). -1 = status isi tidak diketahui.
#else
#error "Pilih profil papan dengan build flag, mis. -DBOARD_XIAO_ESP32S3_SENSE. Lihat firmware/README.md."
#endif

// Perilaku umum
#define CAMERA_LED_PRE_MS 150      // LED menyala sebelum sensor dihidupkan
#define CAMERA_LED_MIN_ON_MS 700   // LED tetap menyala minimal selama ini setelah pengambilan gambar
#define IMAGE_CHUNK_DELAY_MS 10    // jeda antar notifikasi gambar (pengendali laju)
#define LOW_BATTERY_PCT 15
#define CRITICAL_BATTERY_PCT 5
#define IDLE_SLEEP_MS (10UL * 60UL * 1000UL)  // tanpa koneksi BLE selama ini -> deep sleep, bangun dengan tombol
#define STATUS_PERIOD_MS 30000UL
#define BOOT_PAIR_HOLD_MS 3000UL  // tahan tombol saat menyala selama ini = buka jendela pairing
