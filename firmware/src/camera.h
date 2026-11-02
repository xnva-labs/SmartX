#pragma once
#include <stddef.h>
#include <stdint.h>

namespace cam {
struct Frame {
  const uint8_t* data = nullptr;
  size_t len = 0;
  uint16_t width = 0, height = 0;
  void* fb = nullptr;  // camera_fb_t*
};
// Returns 0 on success or a sex::capfail::* code. The camera LED is switched on BEFORE the sensor is powered
// and stays on until release() plus the minimum visible time. resolution: sex::res::*, quality: 10..63 (lower = better).
uint8_t capture(uint8_t resolution, uint8_t quality, Frame& out);
void release(Frame& f);
bool active();
}  // namespace cam
