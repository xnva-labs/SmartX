// SmartEyeX glasses firmware: BLE peripheral exposing camera, microphone and bone-conduction speaker to the phone app.
// Protocol: docs/BLE_PROTOCOL.md. Hardware: docs/HARDWARE.md.
#include <Arduino.h>
#include <driver/rtc_io.h>
#include <esp_sleep.h>
#include <esp_system.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#include "ble_gatt.h"
#include "board.h"
#include "button.h"
#include "camera.h"
#include "diag.h"
#include "indicator.h"
#include "mic.h"
#include "power.h"
#include "proto/adpcm.h"
#include "proto/auth.h"
#include "proto/crc32.h"
#include "proto/frame.h"
#include "speaker.h"
#include "store.h"

struct Cmd { uint8_t cmd, seq, len; uint8_t payload[16]; };

static QueueHandle_t g_cmdq;
static NvsAuthIO g_io;
static AuthSession* g_auth;
static uint8_t g_mac[6];
static char g_hwid[13], g_name[24], g_info[300];
static uint8_t g_evt_seq = 0, g_xfer = 0, g_stream = 0;
static volatile bool g_mic_run = false;
static volatile uint8_t g_mic_reason = sex::micend::STOPPED;
static volatile uint32_t g_mic_deadline = 0;
static TaskHandle_t g_mic_task;
static uint32_t g_last_status = 0, g_last_link = 0;
static uint8_t g_last_flags = 0, g_batt_pct = 255;
static bool g_low_sent = false;

static void send_evt(uint8_t evt, const uint8_t* payload, uint8_t len) {
  uint8_t buf[24];
  size_t n = build_evt(evt, g_evt_seq++, payload, len, buf, sizeof(buf));
  if (n) ble::notify_event(buf, n);
}

static void send_ack(uint8_t cmd, uint8_t seq, uint8_t status) {
  uint8_t p[3] = {cmd, seq, status};
  send_evt(sex::evt::CMD_ACK, p, 3);
}

static uint8_t current_flags() {
  uint8_t f = 0;
  if (cam::active() || indicator::camera_lit()) f |= sex::flag::CAMERA_ACTIVE;
  if (mic::active()) f |= sex::flag::MIC_ACTIVE;
  if (speaker::active()) f |= sex::flag::SPEAKER_ACTIVE;
  if (g_auth->authenticated()) f |= sex::flag::AUTHENTICATED;
  if (g_io.pairing_mode()) f |= sex::flag::PAIRING_MODE;
  if (g_batt_pct != 255 && g_batt_pct <= LOW_BATTERY_PCT) f |= sex::flag::LOW_BATTERY;
  if (power::privacy_closed()) f |= sex::flag::PRIVACY_CLOSED;
  if (g_io.provisioned()) f |= sex::flag::PROVISIONED;
  return f;
}

static void publish_status(bool force) {
  uint32_t now = millis();
  uint8_t flags = current_flags();
  if (!force && flags == g_last_flags && (now - g_last_status) < STATUS_PERIOD_MS) return;
  g_last_flags = flags;
  g_last_status = now;
  StatusFields s;
  s.battery_mv = power::battery_mv();
  s.battery_pct = s.battery_mv ? battery_percent(s.battery_mv) : 255;
  g_batt_pct = s.battery_pct;
  s.charge = power::charge_state();
  s.flags = flags;
  s.uptime_s = now / 1000;
  uint8_t buf[sex::k::STATUS_LEN];
  build_status(s, buf);
  ble::update_status(buf, sizeof(buf));
}

// ---- BLE callbacks (run on the BLE task: keep them short, hand work to the main loop)
static void on_command(const uint8_t* d, size_t n) {
  CmdFrame f;
  if (!parse_cmd(d, n, f) || f.len > sizeof(((Cmd*)0)->payload)) {
    uint8_t cmd = n > 0 ? d[0] : 0, seq = n > 1 ? d[1] : 0;
    send_ack(cmd, seq, sex::st::BAD_COMMAND);
    return;
  }
  if (!g_auth->authenticated()) { send_ack(f.cmd, f.seq, sex::st::NOT_AUTHENTICATED); return; }
  Cmd c;
  c.cmd = f.cmd; c.seq = f.seq; c.len = f.len;
  memcpy(c.payload, f.payload, f.len);
  if (xQueueSend(g_cmdq, &c, 0) != pdTRUE) send_ack(f.cmd, f.seq, sex::st::BUSY);
}

static void on_audio_out(const uint8_t* d, size_t n) {
  if (g_auth->authenticated()) speaker::push_frame(d, n);
}

static void on_connect() { g_last_link = millis(); indicator::set_pattern(indicator::CONNECTED); }

static void on_disconnect() {
  g_last_link = millis();
  g_mic_run = false;
  speaker::abort_stream();
  indicator::set_pattern(g_io.pairing_mode() ? indicator::PAIRING : indicator::ADVERTISING);
}

// ---- microphone streaming task
static void mic_task(void*) {
  static int16_t pcm[sex::k::AUDIO_FRAME_SAMPLES];
  static uint8_t adpcm[sex::k::AUDIO_FRAME_SAMPLES / 2], frame[sex::k::AUDIO_HEADER_LEN + sex::k::AUDIO_FRAME_SAMPLES / 2];
  for (;;) {
    ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    AdpcmState st = {0, 0};
    uint16_t seq = 0;
    uint8_t reason = sex::micend::STOPPED;
    while (g_mic_run) {
      if ((int32_t)(millis() - g_mic_deadline) >= 0) { reason = sex::micend::TIMEOUT; break; }
      if (!mic::read_frame(pcm, 200)) { reason = sex::micend::ERROR; break; }
      AdpcmState before = st;
      size_t n = adpcm_encode(st, pcm, sex::k::AUDIO_FRAME_SAMPLES, adpcm);
      size_t fl = build_audio_frame(g_stream, seq++, before, adpcm, n, frame, sizeof(frame));
      ble::notify_audio(frame, fl);
    }
    if (!g_mic_run && reason == sex::micend::STOPPED) reason = g_mic_reason;
    g_mic_run = false;
    mic::stop();
    uint8_t p[2] = {g_stream, reason};
    send_evt(sex::evt::MIC_ENDED, p, 2);
  }
}

// ---- command execution (main loop)
static void do_capture(const Cmd& c) {
  if (c.len != 2 || c.payload[0] > sex::res::XGA || c.payload[1] < 10 || c.payload[1] > 63) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); return; }
  if (power::privacy_closed()) {
    send_ack(c.cmd, c.seq, sex::st::PRIVACY_BLOCKED);
    uint8_t p = sex::capfail::PRIVACY; send_evt(sex::evt::CAPTURE_FAILED, &p, 1);
    return;
  }
  if (g_batt_pct != 255 && g_batt_pct <= CRITICAL_BATTERY_PCT) { send_ack(c.cmd, c.seq, sex::st::LOW_BATTERY); return; }
  if (cam::active()) { send_ack(c.cmd, c.seq, sex::st::BUSY); return; }
  send_ack(c.cmd, c.seq, sex::st::OK);
  publish_status(true);
  cam::Frame f;
  uint8_t err = cam::capture(c.payload[0], c.payload[1], f);
  if (err) { send_evt(sex::evt::CAPTURE_FAILED, &err, 1); publish_status(true); return; }
  uint8_t xfer = ++g_xfer;
  uint32_t crc = crc32_of(f.data, f.len);
  uint8_t p[13] = {xfer, (uint8_t)f.len, (uint8_t)(f.len >> 8), (uint8_t)(f.len >> 16), (uint8_t)(f.len >> 24),
                   (uint8_t)crc, (uint8_t)(crc >> 8), (uint8_t)(crc >> 16), (uint8_t)(crc >> 24),
                   (uint8_t)f.width, (uint8_t)(f.width >> 8), (uint8_t)f.height, (uint8_t)(f.height >> 8)};
  send_evt(sex::evt::CAPTURE_READY, p, sizeof(p));
  ImageChunker ch;
  ch.init(f.data, f.len, xfer, g_auth->mtu() - 3);
  uint8_t buf[sex::k::MAX_MTU];
  size_t n = 0;
  bool ok = true;
  while (ch.next(buf, sizeof(buf), n)) {
    if (!ble::connected()) { ok = false; break; }
    ble::notify_image(buf, n);
    delay(IMAGE_CHUNK_DELAY_MS);
  }
  cam::release(f);
  if (!ok) return;
  publish_status(true);
}

static void do_command(const Cmd& c) {
  switch (c.cmd) {
    case sex::cmd::PING: send_ack(c.cmd, c.seq, sex::st::OK); break;
    case sex::cmd::CAPTURE: do_capture(c); break;
    case sex::cmd::MIC_START: {
      if (c.len != 2) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      uint16_t ms = (uint16_t)(c.payload[0] | (c.payload[1] << 8));
      if (ms < 100 || ms > 30000) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      if (power::privacy_closed()) { send_ack(c.cmd, c.seq, sex::st::PRIVACY_BLOCKED); break; }
      if (g_mic_run || mic::active()) { send_ack(c.cmd, c.seq, sex::st::BUSY); break; }
      if (!mic::start()) { send_ack(c.cmd, c.seq, sex::st::HW_FAILURE); break; }
      g_stream++;
      g_mic_deadline = millis() + ms;
      g_mic_reason = sex::micend::STOPPED;
      g_mic_run = true;
      send_ack(c.cmd, c.seq, sex::st::OK);
      uint8_t p[3] = {g_stream, (uint8_t)sex::k::AUDIO_RATE_HZ, (uint8_t)(sex::k::AUDIO_RATE_HZ >> 8)};
      send_evt(sex::evt::MIC_STARTED, p, 3);
      xTaskNotifyGive(g_mic_task);
      publish_status(true);
      break;
    }
    case sex::cmd::MIC_STOP:
      g_mic_reason = sex::micend::STOPPED;
      g_mic_run = false;
      send_ack(c.cmd, c.seq, sex::st::OK);
      break;
    case sex::cmd::SPEAK_BEGIN: {
      if (c.len != 3 || (uint16_t)(c.payload[1] | (c.payload[2] << 8)) != sex::k::AUDIO_RATE_HZ) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      send_ack(c.cmd, c.seq, speaker::begin_stream(c.payload[0]) ? sex::st::OK : sex::st::BUSY);
      break;
    }
    case sex::cmd::SPEAK_END: speaker::end_stream(); send_ack(c.cmd, c.seq, sex::st::OK); break;
    case sex::cmd::SPEAK_ABORT: speaker::abort_stream(); send_ack(c.cmd, c.seq, sex::st::OK); break;
    case sex::cmd::SET_VOLUME:
      if (c.len != 1 || c.payload[0] > 100) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      speaker::set_volume(c.payload[0]);
      send_ack(c.cmd, c.seq, sex::st::OK);
      break;
    case sex::cmd::TONE: {
      if (c.len != 5) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      uint16_t hz = (uint16_t)(c.payload[0] | (c.payload[1] << 8)), ms = (uint16_t)(c.payload[2] | (c.payload[3] << 8));
      if (hz < 100 || hz > 6000 || ms < 20 || ms > 2000 || c.payload[4] > 100) { send_ack(c.cmd, c.seq, sex::st::BAD_PARAMS); break; }
      send_ack(c.cmd, c.seq, speaker::tone(hz, ms, c.payload[4]) ? sex::st::OK : sex::st::BUSY);
      break;
    }
    default: send_ack(c.cmd, c.seq, sex::st::BAD_COMMAND);
  }
}

static void go_to_sleep() {
  digitalWrite(PIN_CAMERA_LED, LOW);
  mic::stop();
  rtc_gpio_pullup_en((gpio_num_t)PIN_BUTTON);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_BUTTON);
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_BUTTON, 0);
  Serial.println("deep sleep (tekan tombol untuk bangun)");
  Serial.flush();
  esp_deep_sleep_start();
}

void setup() {
  Serial.begin(115200);
  indicator::begin();
  button::begin();
  power::begin();
  esp_efuse_mac_get_default(g_mac);
  snprintf(g_hwid, sizeof(g_hwid), "%02X%02X%02X%02X%02X%02X", g_mac[0], g_mac[1], g_mac[2], g_mac[3], g_mac[4], g_mac[5]);
  snprintf(g_name, sizeof(g_name), "SmartEyeX-%s", g_hwid + 8);
  snprintf(g_info, sizeof(g_info), "{\"proto\":%u,\"fw\":\"%s\",\"hw\":\"%s\",\"id\":\"%s\",\"caps\":[\"camera\",\"mic\",\"speaker\",\"button\",\"camera_led\"%s%s]}",
           (unsigned)sex::k::PROTO_VERSION, FW_VERSION, BOARD_NAME, g_hwid, PIN_BATTERY_ADC >= 0 ? ",\"battery\"" : "", PIN_PRIVACY_SWITCH >= 0 ? ",\"privacy_switch\"" : "");

  if (button::is_down()) {  // button held while powering up: open the physical pairing window
    uint32_t t0 = millis();
    while (button::is_down() && millis() - t0 < BOOT_PAIR_HOLD_MS) { indicator::tick(millis()); delay(10); }
    if (button::is_down()) g_io.open_pairing(sex::k::PAIRING_WINDOW_MS);
  }

  g_cmdq = xQueueCreate(8, sizeof(Cmd));
  g_auth = new AuthSession(g_io, g_mac);
  speaker::begin();
  xTaskCreatePinnedToCore(mic_task, "mic", 6144, nullptr, 5, &g_mic_task, 1);
  ble::Callbacks cb = {on_command, on_audio_out, on_connect, on_disconnect};
  ble::begin(g_name, g_info, g_auth, cb);
  indicator::set_pattern(g_io.pairing_mode() ? indicator::PAIRING : indicator::ADVERTISING);
  g_last_link = millis();
  publish_status(true);
  Serial.printf("SmartEyeX %s siap: %s (%s) provisioned=%d pairing=%d. Ketik help.\n", FW_VERSION, g_name, BOARD_NAME, g_io.provisioned(), g_io.pairing_mode());
}

void loop() {
  uint32_t now = millis();
  indicator::tick(now);
  diag::poll(g_io, g_hwid);

  button::Event e = button::update(now);
  if (e != button::NONE) {
    uint8_t kind = e == button::SHORT_PRESS ? sex::btn::SHORT : e == button::DOUBLE_PRESS ? sex::btn::DOUBLE : e == button::HOLD_START ? sex::btn::HOLD_START : sex::btn::HOLD_END;
    if (ble::connected() && g_auth->authenticated()) send_evt(sex::evt::BUTTON, &kind, 1);
  }

  Cmd c;
  while (xQueueReceive(g_cmdq, &c, 0) == pdTRUE) do_command(c);

  uint8_t uid;
  if (speaker::take_underrun(&uid)) send_evt(sex::evt::SPEAK_UNDERRUN, &uid, 1);

  static bool privacy_prev = false;
  bool pc = power::privacy_closed();
  if (pc != privacy_prev) {
    privacy_prev = pc;
    if (pc) { g_mic_reason = sex::micend::ERROR; g_mic_run = false; }  // closing the shutter ends any recording at once
    uint8_t s = pc ? 1 : 0;
    if (ble::connected() && g_auth->authenticated()) send_evt(sex::evt::PRIVACY, &s, 1);
    publish_status(true);
  }

  if (g_io.pairing_mode() == false && g_last_flags & sex::flag::PAIRING_MODE) publish_status(true);
  if (g_io.pairing_mode()) indicator::set_pattern(indicator::PAIRING);
  else if (g_mic_run) indicator::set_pattern(indicator::RECORDING);
  else if (!ble::connected()) indicator::set_pattern(indicator::ADVERTISING);
  else indicator::set_pattern(indicator::CONNECTED);

  publish_status(false);
  if (g_batt_pct != 255) {
    if (g_batt_pct <= LOW_BATTERY_PCT && !g_low_sent && ble::connected() && g_auth->authenticated()) { send_evt(sex::evt::LOW_BATTERY, &g_batt_pct, 1); g_low_sent = true; }
    if (g_batt_pct > LOW_BATTERY_PCT + 5) g_low_sent = false;
  }

  if (ble::connected()) g_last_link = now;
  else if (!g_io.pairing_mode() && !cam::active() && (now - g_last_link) > IDLE_SLEEP_MS) go_to_sleep();
  delay(5);
}
