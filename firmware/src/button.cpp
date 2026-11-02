#include "button.h"

#include <Arduino.h>

#include "board.h"

namespace button {
static const uint32_t DEBOUNCE_MS = 30, HOLD_MS = 450, DOUBLE_MS = 450;
static bool stable_down = false, raw_prev = false, holding = false, pending_short = false;
static uint32_t raw_changed = 0, pressed_at = 0, first_release = 0;

void begin() { pinMode(PIN_BUTTON, INPUT_PULLUP); }
bool is_down() { return digitalRead(PIN_BUTTON) == LOW; }

Event update(uint32_t now) {
  bool raw = is_down();
  if (raw != raw_prev) { raw_prev = raw; raw_changed = now; }
  if (raw != stable_down && (now - raw_changed) >= DEBOUNCE_MS) {
    stable_down = raw;
    if (stable_down) {
      pressed_at = now;
      holding = false;
    } else if (holding) {
      holding = false;
      return HOLD_END;
    } else if (pending_short && (now - first_release) <= DOUBLE_MS) {
      pending_short = false;
      return DOUBLE_PRESS;
    } else {
      pending_short = true;
      first_release = now;
    }
  }
  if (stable_down && !holding && (now - pressed_at) >= HOLD_MS) {
    holding = true;
    pending_short = false;
    return HOLD_START;
  }
  if (pending_short && !stable_down && (now - first_release) > DOUBLE_MS) {
    pending_short = false;
    return SHORT_PRESS;
  }
  return NONE;
}
}  // namespace button
