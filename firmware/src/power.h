#pragma once
#include <stdint.h>

namespace power {
void begin();
uint16_t battery_mv();     // 0 if not measurable
uint8_t battery_pct();     // 255 if unknown
uint8_t charge_state();    // sex::charge::*
bool privacy_closed();     // hardware privacy switch in the "closed" position
}  // namespace power
