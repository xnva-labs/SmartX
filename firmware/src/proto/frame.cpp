#include "frame.h"

#include <string.h>

static inline void put16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void put32(uint8_t* p, uint32_t v) { for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (8 * i)); }
static inline uint16_t get16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }

bool parse_cmd(const uint8_t* buf, size_t len, CmdFrame& out) {
  if (len < 3 || (size_t)buf[2] != len - 3) return false;
  out.cmd = buf[0]; out.seq = buf[1]; out.len = buf[2]; out.payload = buf + 3;
  return true;
}

size_t build_evt(uint8_t evt, uint8_t seq, const uint8_t* payload, uint8_t len, uint8_t* out, size_t cap) {
  if ((size_t)len + 3 > cap) return 0;
  out[0] = evt; out[1] = seq; out[2] = len;
  if (len) memcpy(out + 3, payload, len);
  return (size_t)len + 3;
}

size_t build_status(const StatusFields& s, uint8_t out[sex::k::STATUS_LEN]) {
  out[0] = (uint8_t)sex::k::PROTO_VERSION; out[1] = s.battery_pct; out[2] = s.charge; out[3] = s.flags;
  put16(out + 4, s.battery_mv); put32(out + 6, s.uptime_s);
  return sex::k::STATUS_LEN;
}

bool parse_status(const uint8_t* b, size_t len, StatusFields& o) {
  if (len != sex::k::STATUS_LEN || b[0] != sex::k::PROTO_VERSION) return false;
  o.battery_pct = b[1]; o.charge = b[2]; o.flags = b[3]; o.battery_mv = get16(b + 4);
  o.uptime_s = (uint32_t)b[6] | ((uint32_t)b[7] << 8) | ((uint32_t)b[8] << 16) | ((uint32_t)b[9] << 24);
  return true;
}

size_t build_image_chunk(uint8_t xfer, uint32_t offset, const uint8_t* data, size_t n, uint8_t* out, size_t cap) {
  if (n + sex::k::IMAGE_HEADER_LEN > cap) return 0;
  out[0] = xfer; put32(out + 1, offset);
  memcpy(out + sex::k::IMAGE_HEADER_LEN, data, n);
  return n + sex::k::IMAGE_HEADER_LEN;
}

void ImageChunker::init(const uint8_t* d, size_t sz, uint8_t xfer_id, size_t max_payload) {
  data = d; size = sz; pos = 0; xfer = xfer_id;
  chunk_max = max_payload > sex::k::IMAGE_HEADER_LEN ? max_payload - sex::k::IMAGE_HEADER_LEN : 0;
}

bool ImageChunker::next(uint8_t* out, size_t cap, size_t& out_len) {
  if (done() || chunk_max == 0) return false;
  size_t n = size - pos < chunk_max ? size - pos : chunk_max;
  out_len = build_image_chunk(xfer, (uint32_t)pos, data + pos, n, out, cap);
  if (out_len == 0) return false;
  pos += n;
  return true;
}

size_t build_audio_frame(uint8_t stream, uint16_t seq, const AdpcmState& before, const uint8_t* adpcm, size_t n, uint8_t* out, size_t cap) {
  if (n + sex::k::AUDIO_HEADER_LEN > cap) return 0;
  out[0] = stream; put16(out + 1, seq); put16(out + 3, (uint16_t)before.predictor); out[5] = before.index;
  memcpy(out + sex::k::AUDIO_HEADER_LEN, adpcm, n);
  return n + sex::k::AUDIO_HEADER_LEN;
}

bool parse_audio_frame(const uint8_t* b, size_t len, AudioFrame& o) {
  if (len <= sex::k::AUDIO_HEADER_LEN || b[5] > 88) return false;
  o.stream = b[0]; o.seq = get16(b + 1); o.state.predictor = (int16_t)get16(b + 3); o.state.index = b[5];
  o.adpcm = b + sex::k::AUDIO_HEADER_LEN; o.adpcm_len = len - sex::k::AUDIO_HEADER_LEN;
  return true;
}

uint8_t battery_percent(uint16_t mv) {
  static const uint16_t X[] = {3300, 3500, 3600, 3700, 3800, 3900, 4000, 4100, 4200};
  static const uint8_t Y[] = {0, 5, 14, 30, 48, 63, 78, 91, 100};
  const int n = sizeof(X) / sizeof(X[0]);
  if (mv <= X[0]) return 0;
  if (mv >= X[n - 1]) return 100;
  for (int i = 1; i < n; i++)
    if (mv <= X[i]) return (uint8_t)(Y[i - 1] + (int)(Y[i] - Y[i - 1]) * (int)(mv - X[i - 1]) / (int)(X[i] - X[i - 1]));
  return 100;
}
