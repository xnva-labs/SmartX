#pragma once
#include <stdint.h>

namespace indicator {
enum Pattern { ADVERTISING, CONNECTED, PAIRING, RECORDING, ERROR_BLINK };
void begin();
void set_pattern(Pattern p);
// Camera indicator LED: on before the sensor is powered, off only after a minimum visible time.
void camera_on();
void camera_release(uint32_t min_on_ms);
bool camera_lit();
void tick(uint32_t now);
}  // namespace indicator
