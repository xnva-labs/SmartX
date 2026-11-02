#include "diag.h"

#include <Arduino.h>

#include "board.h"
#include "camera.h"
#include "ble_gatt.h"
#include "indicator.h"
#include "mic.h"
#include "power.h"
#include "speaker.h"

namespace diag {
static String line;

static void run(const String& cmd, NvsAuthIO& io, const char* hwid) {
  if (cmd == "help") {
    Serial.println("perintah: info batt led cam mic spk pair unpair");
  } else if (cmd == "info") {
    Serial.printf("fw=%s hw=%s id=%s provisioned=%d privacy_closed=%d heap=%u psram=%u\n", FW_VERSION, BOARD_NAME, hwid, io.provisioned(),
                  power::privacy_closed(), (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getFreePsram());
  } else if (cmd == "batt") {
    Serial.printf("battery=%u mV pct=%u (255 = tidak terpasang)\n", power::battery_mv(), power::battery_pct());
  } else if (cmd == "led") {
    Serial.println("LED kamera menyala 2 detik - harus terlihat dari luar");
    indicator::camera_on(); delay(2000); indicator::camera_release(0);
  } else if (cmd == "cam") {
    cam::Frame f;
    uint8_t err = cam::capture(sex::res::QVGA, 20, f);
    if (err) { Serial.printf("GAGAL kode=%u (3=init kamera, 4=tanpa frame)\n", err); return; }
    bool jpeg = f.len > 4 && f.data[0] == 0xFF && f.data[1] == 0xD8;
    Serial.printf("OK %ux%u %u byte, header JPEG=%s\n", f.width, f.height, (unsigned)f.len, jpeg ? "ya" : "TIDAK");
    cam::release(f);
  } else if (cmd == "mic") {
    uint32_t l = 0, r = 0; int ch = 0;
    Serial.println("Bicara/tepuk tangan selama 2 detik...");
    if (!mic::measure(2000, &l, &r, &ch)) { Serial.println("GAGAL membaca I2S"); return; }
    Serial.printf("energi kiri=%u kanan=%u -> slot terpilih=%s. Jika keduanya ~0, periksa pin MIC_PIN_PDM_*.\n", l, r, ch ? "kanan" : "kiri");
  } else if (cmd == "spk") {
    Serial.println(speaker::tone(440, 600, 60) ? "nada 440 Hz 600 ms" : "speaker sibuk");
  } else if (cmd == "pair") {
    io.open_pairing(sex::k::PAIRING_WINDOW_MS);
    Serial.println("jendela pairing dibuka 60 detik");
  } else if (cmd == "unpair") {
    io.erase_secret(); ble::clear_bonds();
    Serial.println("secret dan bond dihapus");
  } else if (cmd.length()) {
    Serial.println("perintah tidak dikenal; ketik help");
  }
}

void poll(NvsAuthIO& io, const char* hwid) {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') { line.trim(); run(line, io, hwid); line = ""; }
    else if (line.length() < 32) line += c;
  }
}
}  // namespace diag
