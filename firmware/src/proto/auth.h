#pragma once
#include <stddef.h>
#include <stdint.h>

#include "protocol_ids.h"

// Platform services the authentication logic needs; implemented by NVS/ESP code on the device and by fakes in host tests.
struct AuthIO {
  virtual ~AuthIO() {}
  virtual bool load_secret(uint8_t out[sex::k::SECRET_LEN]) = 0;   // false if not provisioned
  virtual bool store_secret(const uint8_t in[sex::k::SECRET_LEN]) = 0;
  virtual void erase_secret() = 0;
  virtual void random_bytes(uint8_t* out, size_t n) = 0;
  virtual uint32_t millis() = 0;
  virtual bool pairing_mode() = 0;  // true only while the physical pairing window is open
};

class AuthSession {
 public:
  AuthSession(AuthIO& io, const uint8_t device_id[6]);
  void reset();  // call on every connect/disconnect
  // Processes one message written to the AUTH characteristic. Returns the response length (0 = nothing to send).
  size_t handle(const uint8_t* msg, size_t len, uint8_t* out, size_t cap);
  bool authenticated() const { return state_ == AUTHED; }
  uint16_t mtu() const { return mtu_; }

 private:
  enum State { IDLE, CHALLENGED, AUTHED };
  size_t fail(uint8_t reason, uint8_t* out);
  void note_failure();
  bool locked() const;
  AuthIO& io_;
  uint8_t id_[6];
  State state_;
  uint8_t nonce_c_[sex::k::NONCE_LEN], nonce_g_[sex::k::NONCE_LEN];
  uint16_t mtu_;
  uint8_t fails_;
  uint32_t window_start_, locked_until_;
  bool locked_flag_;
};
