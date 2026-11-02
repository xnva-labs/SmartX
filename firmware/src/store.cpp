#include "store.h"

#include <Arduino.h>
#include <Preferences.h>
#include <esp_system.h>

static const char* NS = "sex";
static const char* KEY = "secret";

bool NvsAuthIO::load_secret(uint8_t out[sex::k::SECRET_LEN]) {
  Preferences p;
  if (!p.begin(NS, true)) return false;
  size_t n = p.getBytes(KEY, out, sex::k::SECRET_LEN);
  p.end();
  return n == sex::k::SECRET_LEN;
}

bool NvsAuthIO::store_secret(const uint8_t in[sex::k::SECRET_LEN]) {
  Preferences p;
  if (!p.begin(NS, false)) return false;
  size_t n = p.putBytes(KEY, in, sex::k::SECRET_LEN);
  p.end();
  return n == sex::k::SECRET_LEN;
}

void NvsAuthIO::erase_secret() {
  Preferences p;
  if (p.begin(NS, false)) { p.remove(KEY); p.end(); }
}

void NvsAuthIO::random_bytes(uint8_t* out, size_t n) { esp_fill_random(out, n); }
uint32_t NvsAuthIO::millis() { return ::millis(); }
bool NvsAuthIO::pairing_mode() { return until_ != 0 && (int32_t)(until_ - ::millis()) > 0; }
void NvsAuthIO::open_pairing(uint32_t window_ms) { until_ = ::millis() + window_ms; }
bool NvsAuthIO::provisioned() { uint8_t s[sex::k::SECRET_LEN]; bool ok = load_secret(s); memset(s, 0, sizeof(s)); return ok; }
