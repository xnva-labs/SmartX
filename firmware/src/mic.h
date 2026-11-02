#pragma once
#include <stddef.h>
#include <stdint.h>

namespace mic {
// Starts capture; returns false if the I2S driver could not be installed.
bool start();
void stop();
bool active();
// Reads exactly one 20 ms frame (sex::k::AUDIO_FRAME_SAMPLES samples, 16 kHz mono) with digital gain applied.
bool read_frame(int16_t* out, uint32_t timeout_ms);
// Bring-up helper: mean absolute deviation of each hardware channel over `ms` milliseconds.
bool measure(uint32_t ms, uint32_t* left, uint32_t* right, int* chosen);
}  // namespace mic
