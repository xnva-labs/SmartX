#pragma once
#include <stddef.h>
#include <stdint.h>

#include "adpcm.h"
#include "protocol_ids.h"

// ---- command frames (phone -> glasses): [cmd][seq][len][payload]
struct CmdFrame {
  uint8_t cmd, seq, len;
  const uint8_t* payload;
};
bool parse_cmd(const uint8_t* buf, size_t len, CmdFrame& out);

// ---- event frames (glasses -> phone): [evt][seq][len][payload]; returns bytes written or 0 if payload too long
size_t build_evt(uint8_t evt, uint8_t seq, const uint8_t* payload, uint8_t len, uint8_t* out, size_t cap);

// ---- status block (10 bytes)
struct StatusFields {
  uint8_t battery_pct, charge, flags;
  uint16_t battery_mv;
  uint32_t uptime_s;
};
size_t build_status(const StatusFields& s, uint8_t out[sex::k::STATUS_LEN]);
bool parse_status(const uint8_t* buf, size_t len, StatusFields& out);

// ---- image chunks: [xfer u8][offset u32 LE][data]
size_t build_image_chunk(uint8_t xfer, uint32_t offset, const uint8_t* data, size_t n, uint8_t* out, size_t cap);
struct ImageChunker {
  const uint8_t* data;
  size_t size, pos, chunk_max;
  uint8_t xfer;
  void init(const uint8_t* d, size_t sz, uint8_t xfer_id, size_t max_payload);  // max_payload = ATT payload (mtu - 3)
  bool next(uint8_t* out, size_t cap, size_t& out_len);
  bool done() const { return pos >= size; }
};

// ---- audio frames: [stream u8][seq u16 LE][pred s16 LE][index u8][adpcm...]
size_t build_audio_frame(uint8_t stream, uint16_t seq, const AdpcmState& before, const uint8_t* adpcm, size_t n, uint8_t* out, size_t cap);
struct AudioFrame {
  uint8_t stream;
  uint16_t seq;
  AdpcmState state;
  const uint8_t* adpcm;
  size_t adpcm_len;
};
bool parse_audio_frame(const uint8_t* buf, size_t len, AudioFrame& out);

// ---- battery voltage (mV) -> percent via a piecewise-linear single-cell LiPo discharge curve (approximation)
uint8_t battery_percent(uint16_t mv);
