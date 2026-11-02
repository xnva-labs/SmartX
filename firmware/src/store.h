#pragma once
#include "proto/auth.h"

// AuthIO backed by NVS (secret), the hardware RNG, and a physical pairing window.
class NvsAuthIO : public AuthIO {
 public:
  bool load_secret(uint8_t out[sex::k::SECRET_LEN]) override;
  bool store_secret(const uint8_t in[sex::k::SECRET_LEN]) override;
  void erase_secret() override;
  void random_bytes(uint8_t* out, size_t n) override;
  uint32_t millis() override;
  bool pairing_mode() override;
  void open_pairing(uint32_t window_ms);
  void close_pairing() { until_ = 0; }
  bool provisioned();
 private:
  uint32_t until_ = 0;
};
