#pragma once
#include <stddef.h>
#include <stdint.h>

struct Sha256 {
  uint32_t h[8];
  uint8_t buf[64];
  uint64_t total;
  size_t blen;
  void init();
  void update(const uint8_t* data, size_t len);
  void finish(uint8_t out[32]);
};

struct HmacSha256 {
  Sha256 inner, outer;
  void init(const uint8_t* key, size_t keylen);
  void update(const uint8_t* data, size_t len) { inner.update(data, len); }
  void finish(uint8_t out[32]);
};

void hmac_sha256(const uint8_t* key, size_t keylen, const uint8_t* msg, size_t msglen, uint8_t out[32]);
bool ct_equal(const uint8_t* a, const uint8_t* b, size_t n);  // constant-time comparison
