#pragma once
#include <stddef.h>
#include <stdint.h>

// IMA/DVI ADPCM, 4 bits per sample, FIRST sample in the LOW nibble. Each protocol audio frame carries its own
// (predictor, step index) header so a lost frame never corrupts later frames.
struct AdpcmState {
  int16_t predictor;
  uint8_t index;  // 0..88
};

// n_samples must be even. Returns bytes written (n_samples / 2).
size_t adpcm_encode(AdpcmState& st, const int16_t* pcm, size_t n_samples, uint8_t* out);
// Returns samples written (n_bytes * 2).
size_t adpcm_decode(AdpcmState& st, const uint8_t* in, size_t n_bytes, int16_t* pcm);
