#include "indicator.h"

#include <Arduino.h>

#include "board.h"

namespace indicator {
static bool cam_lit = false;
static uint32_t cam_since = 0, cam_off_at = 0;
static Pattern pat = ADVERTISING, prev_pat = ADVERTISING;
static uint32_t err_until = 0;

static void status_write(bool on) {
#if PIN_STATUS_LED >= 0
  digitalWrite(PIN_STATUS_LED, STATUS_LED_ACTIVE_LOW ? !on : on);
#else
  (void)on;
#endif
}

void begin() {
  pinMode(PIN_CAMERA_LED, OUTPUT);
  digitalWrite(PIN_CAMERA_LED, LOW);
#if PIN_STATUS_LED >= 0
  pinMode(PIN_STATUS_LED, OUTPUT);
#endif
  status_write(false);
}

void set_pattern(Pattern p) {
  if (p == ERROR_BLINK) { prev_pat = pat == ERROR_BLINK ? prev_pat : pat; err_until = millis() + 900; }
  pat = p;
}

void camera_on() {
  digitalWrite(PIN_CAMERA_LED, HIGH);
  if (!cam_lit) { cam_lit = true; cam_since = millis(); }
  cam_off_at = 0;
}

void camera_release(uint32_t min_on_ms) {
  if (!cam_lit) return;
  uint32_t due = cam_since + min_on_ms, now = millis();
  cam_off_at = (int32_t)(due - now) > 0 ? due : now;
}

bool camera_lit() { return cam_lit; }

void tick(uint32_t now) {
  if (cam_lit && cam_off_at != 0 && (int32_t)(now - cam_off_at) >= 0) {
    digitalWrite(PIN_CAMERA_LED, LOW);
    cam_lit = false;
    cam_off_at = 0;
  }
  if (pat == ERROR_BLINK && (int32_t)(now - err_until) >= 0) pat = prev_pat;
  bool on = false;
  switch (pat) {
    case ADVERTISING: on = (now % 2000) < 100; break;
    case CONNECTED: on = (now % 4000) < 50; break;
    case PAIRING: on = (now % 300) < 150; break;
    case RECORDING: on = true; break;
    case ERROR_BLINK: on = (now % 300) < 150; break;
  }
  status_write(on);
}
}  // namespace indicator
