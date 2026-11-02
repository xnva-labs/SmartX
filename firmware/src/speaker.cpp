#include "speaker.h"

#include <Arduino.h>
#include <driver/i2s.h>
#include <freertos/FreeRTOS.h>
#include <freertos/stream_buffer.h>
#include <freertos/task.h>
#include <math.h>

#include "board.h"
#include "proto/adpcm.h"
#include "proto/frame.h"

namespace speaker {
static const i2s_port_t PORT = I2S_NUM_1;
static const size_t N = sex::k::AUDIO_FRAME_SAMPLES;
static const size_t BUF_SAMPLES = 8000;       // 0.5 s jitter buffer
static const size_t PREROLL_SAMPLES = 2400;   // 150 ms before playback starts
static StreamBufferHandle_t sb = nullptr;
static volatile bool g_open = false, g_end = false, g_abort = false, g_underrun = false;
static volatile uint8_t g_stream = 0, g_underrun_stream = 0, g_volume = 70;
static volatile uint16_t g_tone_hz = 0, g_tone_ms = 0;
static volatile uint8_t g_tone_vol = 0;
static volatile uint16_t g_expect_seq = 0;
static volatile bool g_have_seq = false;
static bool g_started = false;  // pre-roll satisfied for the current stream

bool active() { return g_open || g_tone_ms != 0; }
void set_volume(uint8_t pct) { g_volume = pct > 100 ? 100 : pct; }

static bool open_i2s() {
  i2s_config_t cfg;
  memset(&cfg, 0, sizeof(cfg));
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = sex::k::AUDIO_RATE_HZ;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  cfg.dma_buf_count = 6;
  cfg.dma_buf_len = 160;
  cfg.tx_desc_auto_clear = true;
  if (i2s_driver_install(PORT, &cfg, 0, nullptr) != ESP_OK) return false;
  i2s_pin_config_t pins;
  memset(&pins, 0, sizeof(pins));
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = SPK_PIN_BCLK;
  pins.ws_io_num = SPK_PIN_LRC;
  pins.data_out_num = SPK_PIN_DIN;
  pins.data_in_num = I2S_PIN_NO_CHANGE;
  if (i2s_set_pin(PORT, &pins) != ESP_OK) { i2s_driver_uninstall(PORT); return false; }
  return true;
}

static void close_i2s() {
  i2s_zero_dma_buffer(PORT);
  i2s_stop(PORT);
  i2s_driver_uninstall(PORT);
}

static void write_mono(const int16_t* pcm, size_t n) {
  static int16_t stereo[N * 2];
  uint32_t vol = g_volume;
  while (n > 0) {
    size_t k = n < N ? n : N;
    for (size_t i = 0; i < k; i++) {
      int16_t v = (int16_t)(((int32_t)pcm[i] * (int32_t)vol) / 100);
      stereo[2 * i] = v; stereo[2 * i + 1] = v;
    }
    size_t written = 0;
    i2s_write(PORT, stereo, k * 2 * sizeof(int16_t), &written, portMAX_DELAY);
    pcm += k; n -= k;
  }
}

static void play_tone_now() {
  uint32_t total = (uint32_t)sex::k::AUDIO_RATE_HZ * g_tone_ms / 1000;
  static int16_t chunk[N];
  float step = 2.0f * (float)M_PI * g_tone_hz / sex::k::AUDIO_RATE_HZ, ph = 0;
  uint8_t keep = g_volume; g_volume = g_tone_vol ? g_tone_vol : keep;
  if (open_i2s()) {
    for (uint32_t done = 0; done < total && !g_abort;) {
      size_t k = total - done < N ? total - done : N;
      for (size_t i = 0; i < k; i++) {
        float env = 1.0f;
        uint32_t pos = done + i;
        if (pos < 160) env = pos / 160.0f; else if (total - pos < 160) env = (total - pos) / 160.0f;  // 10 ms fades avoid clicks
        chunk[i] = (int16_t)(sinf(ph) * 12000.0f * env); ph += step;
      }
      write_mono(chunk, k);
      done += k;
    }
    close_i2s();
  }
  g_volume = keep;
  g_tone_ms = 0;
}

static void task(void*) {
  static int16_t pcm[N];
  for (;;) {
    if (g_tone_ms != 0 && !g_open) { play_tone_now(); continue; }
    if (!g_open) { vTaskDelay(pdMS_TO_TICKS(10)); continue; }
    if (g_abort) { xStreamBufferReset(sb); close_i2s(); g_open = false; g_abort = false; g_end = false; g_started = false; continue; }
    size_t buffered = xStreamBufferBytesAvailable(sb) / sizeof(int16_t);
    if (!g_started) {
      if (buffered >= PREROLL_SAMPLES || (g_end && buffered > 0)) g_started = true;
      else if (g_end) { close_i2s(); g_open = false; g_end = false; continue; }
      else { vTaskDelay(pdMS_TO_TICKS(10)); continue; }
    }
    size_t got = xStreamBufferReceive(sb, pcm, N * sizeof(int16_t), pdMS_TO_TICKS(30)) / sizeof(int16_t);
    if (got > 0) { write_mono(pcm, got); continue; }
    if (g_end) { close_i2s(); g_open = false; g_end = false; g_started = false; continue; }
    g_underrun_stream = g_stream; g_underrun = true;  // ran dry mid-stream: re-buffer before resuming
    g_started = false;
  }
}

void begin() {
  sb = xStreamBufferCreate(BUF_SAMPLES * sizeof(int16_t), 1);
  xTaskCreatePinnedToCore(task, "speaker", 4096, nullptr, 5, nullptr, 1);
}

bool begin_stream(uint8_t stream_id) {
  if (g_open || g_tone_ms != 0) return false;
  xStreamBufferReset(sb);
  if (!open_i2s()) return false;
  g_stream = stream_id; g_end = false; g_abort = false; g_have_seq = false; g_started = false;
  g_open = true;
  return true;
}

void push_frame(const uint8_t* frame, size_t len) {
  AudioFrame f;
  if (!g_open || g_end || !parse_audio_frame(frame, len, f) || f.stream != g_stream) return;
  static int16_t pcm[N];
  if (f.adpcm_len * 2 > N) return;
  AdpcmState st = f.state;
  size_t n = adpcm_decode(st, f.adpcm, f.adpcm_len, pcm);
  // A lost frame is concealed with silence so playback timing is preserved.
  if (g_have_seq && f.seq != g_expect_seq) {
    uint16_t gap = (uint16_t)(f.seq - g_expect_seq);
    static const int16_t zeros[N] = {0};
    for (uint16_t i = 0; i < gap && i < 5; i++) xStreamBufferSend(sb, zeros, sizeof(zeros), 0);
  }
  g_expect_seq = (uint16_t)(f.seq + 1); g_have_seq = true;
  xStreamBufferSend(sb, pcm, n * sizeof(int16_t), 0);
}

void end_stream() { g_end = true; }
void abort_stream() { if (g_open) g_abort = true; g_end = true; }

bool tone(uint16_t freq_hz, uint16_t ms, uint8_t volume_pct) {
  if (g_open || g_tone_ms != 0) return false;
  g_tone_hz = freq_hz; g_tone_vol = volume_pct; g_tone_ms = ms;
  return true;
}

bool take_underrun(uint8_t* stream_id) {
  if (!g_underrun) return false;
  *stream_id = g_underrun_stream; g_underrun = false;
  return true;
}
}  // namespace speaker
