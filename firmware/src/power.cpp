#include "power.h"

#include <Arduino.h>

#include "board.h"
#include "proto/frame.h"

namespace power {
void begin() {
#if PIN_BATTERY_ADC >= 0
  analogSetPinAttenuation(PIN_BATTERY_ADC, ADC_11db);
#endif
#if PIN_PRIVACY_SWITCH >= 0
  pinMode(PIN_PRIVACY_SWITCH, INPUT_PULLUP);
#endif
#if PIN_CHARGE_STATUS >= 0
  pinMode(PIN_CHARGE_STATUS, INPUT_PULLUP);
#endif
}

uint16_t battery_mv() {
#if PIN_BATTERY_ADC >= 0
  uint32_t acc = 0;
  for (int i = 0; i < 16; i++) { acc += analogReadMilliVolts(PIN_BATTERY_ADC); delayMicroseconds(150); }
  return (uint16_t)((acc / 16) * BATTERY_DIVIDER_RATIO);
#else
  return 0;
#endif
}

uint8_t battery_pct() {
  uint16_t mv = battery_mv();
  return mv == 0 ? 255 : battery_percent(mv);
}

uint8_t charge_state() {
#if PIN_CHARGE_STATUS >= 0
  return digitalRead(PIN_CHARGE_STATUS) == LOW ? sex::charge::CHARGING : sex::charge::DISCHARGING;
#else
  return sex::charge::UNKNOWN;
#endif
}

bool privacy_closed() {
#if PIN_PRIVACY_SWITCH >= 0
  return digitalRead(PIN_PRIVACY_SWITCH) == LOW;
#else
  return false;
#endif
}
}  // namespace power
