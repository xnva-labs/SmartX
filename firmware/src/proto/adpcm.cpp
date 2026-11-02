#include "adpcm.h"

static const int8_t kIndexTable[8] = {-1, -1, -1, -1, 2, 4, 6, 8};
static const int16_t kStepTable[89] = {
    7,    8,    9,    10,   11,   12,   13,   14,   16,   17,   19,   21,   23,    25,    28,    31,    34,    37,    41,    45,   50,
    55,   60,   66,   73,   80,   88,   97,   107,  118,  130,  143,  157,  173,   190,   209,   230,   253,   279,   307,   337,  371,
    408,  449,  494,  544,  598,  658,  724,  796,  876,  963,  1060, 1166, 1282,  1411,  1552,  1707,  1878,  2066,  2272,  2499, 2749,
    3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493,  10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350,
    22385, 24623, 27086, 29794, 32767};

static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

static uint8_t encode_one(AdpcmState& st, int16_t sample) {
  int step = kStepTable[st.index];
  int diff = (int)sample - (int)st.predictor;
  int sign = 0;
  if (diff < 0) { sign = 8; diff = -diff; }
  int delta = 0, vpdiff = step >> 3;
  if (diff >= step) { delta = 4; diff -= step; vpdiff += step; }
  step >>= 1;
  if (diff >= step) { delta |= 2; diff -= step; vpdiff += step; }
  step >>= 1;
  if (diff >= step) { delta |= 1; vpdiff += step; }
  int vp = (int)st.predictor + (sign ? -vpdiff : vpdiff);
  st.predictor = (int16_t)clampi(vp, -32768, 32767);
  delta |= sign;
  st.index = (uint8_t)clampi((int)st.index + kIndexTable[delta & 7], 0, 88);
  return (uint8_t)delta;
}

static int16_t decode_one(AdpcmState& st, uint8_t code) {
  int step = kStepTable[st.index];
  st.index = (uint8_t)clampi((int)st.index + kIndexTable[code & 7], 0, 88);
  int vpdiff = step >> 3;
  if (code & 4) vpdiff += step;
  if (code & 2) vpdiff += step >> 1;
  if (code & 1) vpdiff += step >> 2;
  int vp = (int)st.predictor + ((code & 8) ? -vpdiff : vpdiff);
  st.predictor = (int16_t)clampi(vp, -32768, 32767);
  return st.predictor;
}

size_t adpcm_encode(AdpcmState& st, const int16_t* pcm, size_t n, uint8_t* out) {
  size_t o = 0;
  for (size_t i = 0; i + 1 < n; i += 2) {
    uint8_t lo = encode_one(st, pcm[i]);
    uint8_t hi = encode_one(st, pcm[i + 1]);
    out[o++] = (uint8_t)(lo | (hi << 4));
  }
  return o;
}

size_t adpcm_decode(AdpcmState& st, const uint8_t* in, size_t n, int16_t* pcm) {
  size_t o = 0;
  for (size_t i = 0; i < n; i++) {
    pcm[o++] = decode_one(st, in[i] & 0x0F);
    pcm[o++] = decode_one(st, in[i] >> 4);
  }
  return o;
}
