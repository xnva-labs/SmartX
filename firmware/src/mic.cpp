#include "mic.h"

#include <Arduino.h>
#include <driver/i2s.h>
#include <freertos/FreeRTOS.h>

#include "board.h"
#include "protocol_ids.h"

namespace mic {
static const i2s_port_t PORT = I2S_NUM_0;  // PDM receive is only available on I2S0 of the ESP32-S3
static const size_t N = sex::k::AUDIO_FRAME_SAMPLES;
static bool g_on = false;
static int g_channel = -1;  // 0 = left slot, 1 = right slot; chosen automatically on the first frames

bool active() { return g_on; }

bool start() {
  if (g_on) return true;
  i2s_config_t cfg;
  memset(&cfg, 0, sizeof(cfg));
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM);
  cfg.sample_rate = sex::k::AUDIO_RATE_HZ;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 6;
  cfg.dma_buf_len = N;
  if (i2s_driver_install(PORT, &cfg, 0, nullptr) != ESP_OK) return false;
  i2s_pin_config_t pins;
  memset(&pins, 0, sizeof(pins));
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = I2S_PIN_NO_CHANGE;
  pins.ws_io_num = MIC_PIN_PDM_CLK;       // PDM clock
  pins.data_out_num = I2S_PIN_NO_CHANGE;
  pins.data_in_num = MIC_PIN_PDM_DATA;
  if (i2s_set_pin(PORT, &pins) != ESP_OK) { i2s_driver_uninstall(PORT); return false; }
  i2s_zero_dma_buffer(PORT);
  g_on = true;
  return true;
}

void stop() {
  if (!g_on) return;
  i2s_stop(PORT);
  i2s_driver_uninstall(PORT);
  g_on = false;
}

static bool read_stereo(int16_t* st, uint32_t timeout_ms) {
  size_t got = 0, want = N * 2 * sizeof(int16_t);
  uint8_t* p = (uint8_t*)st;
  uint32_t t0 = millis();
  while (got < want) {
    size_t r = 0;
    if (i2s_read(PORT, p + got, want - got, &r, pdMS_TO_TICKS(40)) != ESP_OK) return false;
    got += r;
    if (millis() - t0 > timeout_ms) return false;
  }
  return true;
}

bool read_frame(int16_t* out, uint32_t timeout_ms) {
  static int16_t st[N * 2];
  static uint32_t acc[2] = {0, 0};
  static int seen = 0;
  if (!g_on || !read_stereo(st, timeout_ms)) return false;
  if (g_channel < 0) {  // choose the slot that actually carries signal
    int32_t m[2] = {0, 0};
    for (size_t i = 0; i < N; i++) { m[0] += st[2 * i]; m[1] += st[2 * i + 1]; }
    int32_t mean0 = m[0] / (int32_t)N, mean1 = m[1] / (int32_t)N;
    for (size_t i = 0; i < N; i++) {
      acc[0] += (uint32_t)abs(st[2 * i] - mean0);
      acc[1] += (uint32_t)abs(st[2 * i + 1] - mean1);
    }
    if (++seen >= 8) { g_channel = acc[1] > acc[0] ? 1 : 0; seen = 0; acc[0] = acc[1] = 0; }
  }
  int ch = g_channel < 0 ? 0 : g_channel;
  for (size_t i = 0; i < N; i++) {
    int32_t v = (int32_t)st[2 * i + ch] * MIC_GAIN;
    out[i] = (int16_t)(v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
  }
  return true;
}

bool measure(uint32_t ms, uint32_t* left, uint32_t* right, int* chosen) {
  bool was = g_on;
  if (!start()) return false;
  static int16_t st[N * 2];
  uint64_t a0 = 0, a1 = 0, n = 0;
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    if (!read_stereo(st, 200)) break;
    int32_t m0 = 0, m1 = 0;
    for (size_t i = 0; i < N; i++) { m0 += st[2 * i]; m1 += st[2 * i + 1]; }
    m0 /= (int32_t)N; m1 /= (int32_t)N;
    for (size_t i = 0; i < N; i++) { a0 += (uint32_t)abs(st[2 * i] - m0); a1 += (uint32_t)abs(st[2 * i + 1] - m1); n++; }
  }
  if (!was) stop();
  *left = n ? (uint32_t)(a0 / n) : 0;
  *right = n ? (uint32_t)(a1 / n) : 0;
  *chosen = *right > *left ? 1 : 0;
  return n > 0;
}
}  // namespace mic
