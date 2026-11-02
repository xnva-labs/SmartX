#include "auth.h"

#include <string.h>

#include "sha256.h"

AuthSession::AuthSession(AuthIO& io, const uint8_t device_id[6]) : io_(io), state_(IDLE), mtu_(0), fails_(0), window_start_(0), locked_until_(0), locked_flag_(false) {
  memcpy(id_, device_id, 6);
  memset(nonce_c_, 0, sizeof(nonce_c_));
  memset(nonce_g_, 0, sizeof(nonce_g_));
}

void AuthSession::reset() { state_ = IDLE; mtu_ = 0; }

bool AuthSession::locked() const {
  return locked_flag_ && (uint32_t)(io_.millis() - locked_until_) > 0x7fffffffu;  // now < locked_until (wrap-safe)
}

void AuthSession::note_failure() {
  uint32_t now = io_.millis();
  if ((uint32_t)(now - window_start_) > sex::k::AUTH_LOCKOUT_MS) { window_start_ = now; fails_ = 0; }
  if (++fails_ >= sex::k::AUTH_MAX_FAILS) { locked_flag_ = true; locked_until_ = now + sex::k::AUTH_LOCKOUT_MS; fails_ = 0; }
}

size_t AuthSession::fail(uint8_t reason, uint8_t* out) {
  state_ = IDLE;
  out[0] = (uint8_t)sex::auth::AUTH_FAIL; out[1] = reason;
  return 2;
}

static void mac(const uint8_t secret[32], const char* label, const uint8_t id[6], const uint8_t* a, const uint8_t* b, uint8_t out[32]) {
  HmacSha256 h;
  h.init(secret, sex::k::SECRET_LEN);
  h.update((const uint8_t*)label, strlen(label));
  h.update(id, 6);
  h.update(a, sex::k::NONCE_LEN);
  h.update(b, sex::k::NONCE_LEN);
  h.finish(out);
}

size_t AuthSession::handle(const uint8_t* m, size_t len, uint8_t* out, size_t cap) {
  if (len < 1 || cap < 2 + sex::k::NONCE_LEN + sex::k::MAC_LEN) return 0;
  uint8_t secret[sex::k::SECRET_LEN];
  switch (m[0]) {
    case sex::auth::HELLO: {
      if (len != 1 + sex::k::NONCE_LEN + 2) return fail(sex::authfail::BAD_STATE, out);
      if (locked()) return fail(sex::authfail::LOCKED_OUT, out);
      if (!io_.load_secret(secret)) return fail(sex::authfail::NO_SECRET, out);
      uint16_t mtu = (uint16_t)(m[1 + sex::k::NONCE_LEN] | (m[2 + sex::k::NONCE_LEN] << 8));
      if (mtu < sex::k::MIN_MTU) return fail(sex::authfail::MTU_TOO_SMALL, out);
      mtu_ = mtu > sex::k::MAX_MTU ? (uint16_t)sex::k::MAX_MTU : mtu;
      memcpy(nonce_c_, m + 1, sex::k::NONCE_LEN);
      io_.random_bytes(nonce_g_, sizeof(nonce_g_));
      out[0] = (uint8_t)sex::auth::CHALLENGE;
      memcpy(out + 1, nonce_g_, sex::k::NONCE_LEN);
      mac(secret, sex::MAC_LABEL_GLASSES, id_, nonce_c_, nonce_g_, out + 1 + sex::k::NONCE_LEN);
      memset(secret, 0, sizeof(secret));
      state_ = CHALLENGED;
      return 1 + sex::k::NONCE_LEN + sex::k::MAC_LEN;
    }
    case sex::auth::PROOF: {
      if (state_ != CHALLENGED || len != 1 + sex::k::MAC_LEN) return fail(sex::authfail::BAD_STATE, out);
      if (locked()) return fail(sex::authfail::LOCKED_OUT, out);
      if (!io_.load_secret(secret)) return fail(sex::authfail::NO_SECRET, out);
      uint8_t expect[sex::k::MAC_LEN];
      mac(secret, sex::MAC_LABEL_PHONE, id_, nonce_g_, nonce_c_, expect);
      memset(secret, 0, sizeof(secret));
      if (!ct_equal(expect, m + 1, sex::k::MAC_LEN)) { note_failure(); return fail(sex::authfail::BAD_MAC, out); }
      state_ = AUTHED;
      fails_ = 0;
      out[0] = (uint8_t)sex::auth::AUTH_OK;
      return 1;
    }
    case sex::auth::PROVISION: {
      if (!io_.pairing_mode() || len != 1 + sex::k::SECRET_LEN) { out[0] = (uint8_t)sex::auth::PROVISION_FAIL; out[1] = (uint8_t)sex::provfail::NOT_PAIRING; return 2; }
      if (!io_.store_secret(m + 1)) { out[0] = (uint8_t)sex::auth::PROVISION_FAIL; out[1] = (uint8_t)sex::provfail::STORAGE; return 2; }
      state_ = IDLE;
      out[0] = (uint8_t)sex::auth::PROVISION_OK;
      return 1;
    }
    case sex::auth::FORGET: {
      if (state_ != AUTHED) return fail(sex::authfail::BAD_STATE, out);
      io_.erase_secret();
      state_ = IDLE;
      out[0] = (uint8_t)sex::auth::FORGET_OK;
      return 1;
    }
    default:
      return fail(sex::authfail::BAD_STATE, out);
  }
}
