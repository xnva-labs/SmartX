#pragma once
#include <stdint.h>

namespace button {
enum Event { NONE = 0, SHORT_PRESS, DOUBLE_PRESS, HOLD_START, HOLD_END };
void begin();
bool is_down();
Event update(uint32_t now);  // call often; returns at most one event per call
}  // namespace button
