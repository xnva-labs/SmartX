// Host tests for the hardware-independent firmware core. Build/run: make -C firmware/test/host
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "adpcm.h"
#include "auth.h"
#include "crc32.h"
#include "frame.h"
#include "sha256.h"
#include "vectors.h"

static int g_fail = 0, g_checks = 0;
#define CHECK(cond) do { g_checks++; if (!(cond)) { g_fail++; printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } } while (0)

static void test_crc() {
  const uint8_t* d[] = {CRC_DATA_0, CRC_DATA_1, CRC_DATA_2, CRC_DATA_3};
  const size_t n[] = {CRC_LEN_0, CRC_LEN_1, CRC_LEN_2, CRC_LEN_3};
  const uint32_t v[] = {CRC_VAL_0, CRC_VAL_1, CRC_VAL_2, CRC_VAL_3};
  for (int i = 0; i < CRC_N; i++) CHECK(crc32_of(d[i], n[i]) == v[i]);
  // incremental == one-shot
  uint32_t c = crc32_update(0, CRC_DATA_2, 100);
  c = crc32_update(c, CRC_DATA_2 + 100, CRC_LEN_2 - 100);
  CHECK(c == CRC_VAL_2);
}

static void test_hmac() {
  const uint8_t* k[] = {HMAC_KEY_0, HMAC_KEY_1, HMAC_KEY_2, HMAC_KEY_3};
  const size_t kl[] = {HMAC_KEYLEN_0, HMAC_KEYLEN_1, HMAC_KEYLEN_2, HMAC_KEYLEN_3};
  const uint8_t* m[] = {HMAC_MSG_0, HMAC_MSG_1, HMAC_MSG_2, HMAC_MSG_3};
  const size_t ml[] = {HMAC_MSGLEN_0, HMAC_MSGLEN_1, HMAC_MSGLEN_2, HMAC_MSGLEN_3};
  const uint8_t* e[] = {HMAC_MAC_0, HMAC_MAC_1, HMAC_MAC_2, HMAC_MAC_3};
  for (int i = 0; i < HMAC_N; i++) {
    uint8_t out[32];
    hmac_sha256(k[i], kl[i], m[i], ml[i], out);
    CHECK(memcmp(out, e[i], 32) == 0);
  }
  // streaming in odd-sized pieces gives the same result
  HmacSha256 h; h.init(HMAC_KEY_2, HMAC_KEYLEN_2);
  for (size_t i = 0; i < HMAC_MSGLEN_2; i += 7) h.update(HMAC_MSG_2 + i, (HMAC_MSGLEN_2 - i) < 7 ? (HMAC_MSGLEN_2 - i) : 7);
  uint8_t out[32]; h.finish(out);
  CHECK(memcmp(out, HMAC_MAC_2, 32) == 0);
  CHECK(ct_equal(out, HMAC_MAC_2, 32));
  out[31] ^= 1;
  CHECK(!ct_equal(out, HMAC_MAC_2, 32));
}

static void test_adpcm() {
  uint8_t enc[512];
  AdpcmState st = {0, 0};
  size_t n = adpcm_encode(st, ADPCM_PCM, ADPCM_PCM_N, enc);
  CHECK(n == ADPCM_PCM_N / 2);
  CHECK(memcmp(enc, ADPCM_ENC, n) == 0);
  CHECK(st.predictor == ADPCM_END_PRED && st.index == ADPCM_END_IDX);

  AdpcmState st2 = {ADPCM_ENC2_PRED, ADPCM_ENC2_IDX};
  n = adpcm_encode(st2, ADPCM_PCM, ADPCM_PCM_N, enc);
  CHECK(memcmp(enc, ADPCM_ENC2, n) == 0);
  CHECK(st2.predictor == ADPCM_END2_PRED && st2.index == ADPCM_END2_IDX);

  int16_t dec[512];
  AdpcmState d = {ADPCM_ENC2_PRED, ADPCM_ENC2_IDX};
  size_t ns = adpcm_decode(d, ADPCM_ENC2, ADPCM_PCM_N / 2, dec);
  CHECK(ns == ADPCM_PCM_N);
  CHECK(memcmp(dec, ADPCM_DEC2, ns * 2) == 0);
  CHECK(d.predictor == ADPCM_END2_PRED && d.index == ADPCM_END2_IDX);

  // encode/decode symmetry: decoder state tracks encoder state exactly
  AdpcmState e = {100, 5}, dd = {100, 5};
  int16_t pcm[320], back[320]; uint8_t buf[160];
  for (int i = 0; i < 320; i++) pcm[i] = (int16_t)(8000.0 * sin(i * 0.1));
  adpcm_encode(e, pcm, 320, buf);
  adpcm_decode(dd, buf, 160, back);
  CHECK(e.predictor == dd.predictor && e.index == dd.index);
  double err = 0, sig = 0;
  for (int i = 40; i < 320; i++) { err += (double)(pcm[i] - back[i]) * (pcm[i] - back[i]); sig += (double)pcm[i] * pcm[i]; }
  CHECK(10 * log10(sig / err) > 20.0);  // > 20 dB SNR on a sine
  // silence stays silent
  AdpcmState z = {0, 0}; int16_t zero[320] = {0}, zb[320]; uint8_t zc[160];
  adpcm_encode(z, zero, 320, zc); AdpcmState zd = {0, 0}; adpcm_decode(zd, zc, 160, zb);
  for (int i = 0; i < 320; i++) CHECK(zb[i] == 0);
}

static void test_frames() {
  const uint8_t cmd[] = {sex::cmd::CAPTURE, 7, 2, 1, 20};
  CmdFrame f;
  CHECK(parse_cmd(cmd, sizeof(cmd), f) && f.cmd == 1 && f.seq == 7 && f.len == 2 && f.payload[1] == 20);
  CHECK(!parse_cmd(cmd, sizeof(cmd) - 1, f));
  CHECK(!parse_cmd(cmd, 2, f));
  const uint8_t bad[] = {1, 1, 5, 0};
  CHECK(!parse_cmd(bad, sizeof(bad), f));

  uint8_t out[64]; const uint8_t pl[] = {sex::cmd::PING, 9, sex::st::OK};
  size_t n = build_evt(sex::evt::CMD_ACK, 3, pl, 3, out, sizeof(out));
  CHECK(n == 6 && out[0] == sex::evt::CMD_ACK && out[1] == 3 && out[2] == 3 && out[3] == sex::cmd::PING && out[5] == sex::st::OK);
  CHECK(build_evt(1, 0, pl, 3, out, 5) == 0);

  StatusFields s = {87, sex::charge::CHARGING, (uint8_t)(sex::flag::AUTHENTICATED | sex::flag::PROVISIONED), 4012, 123456};
  uint8_t sb[10]; CHECK(build_status(s, sb) == 10);
  StatusFields p; CHECK(parse_status(sb, 10, p));
  CHECK(p.battery_pct == 87 && p.charge == 1 && p.flags == 136 && p.battery_mv == 4012 && p.uptime_s == 123456);
  CHECK(sb[0] == sex::k::PROTO_VERSION && sb[4] == (4012 & 0xff) && sb[5] == (4012 >> 8));
  sb[0] = 9; CHECK(!parse_status(sb, 10, p));

  // image chunker reassembles byte-exact with offsets
  uint8_t img[1000]; for (int i = 0; i < 1000; i++) img[i] = (uint8_t)(i * 7 + 3);
  ImageChunker ch; ch.init(img, sizeof(img), 5, 244);
  uint8_t rebuilt[1000]; memset(rebuilt, 0, sizeof(rebuilt)); size_t got = 0; int chunks = 0;
  uint8_t cb[256]; size_t cl;
  while (ch.next(cb, sizeof(cb), cl)) {
    CHECK(cl <= 244 && cb[0] == 5);
    uint32_t off = cb[1] | (cb[2] << 8) | (cb[3] << 16) | ((uint32_t)cb[4] << 24);
    memcpy(rebuilt + off, cb + 5, cl - 5); got += cl - 5; chunks++;
  }
  CHECK(got == 1000 && memcmp(img, rebuilt, 1000) == 0 && chunks == 5 && ch.done());
  CHECK(crc32_of(rebuilt, 1000) == crc32_of(img, 1000));

  // audio frame round-trip
  AdpcmState before = {-1234, 40}; uint8_t ad[160]; for (int i = 0; i < 160; i++) ad[i] = (uint8_t)i;
  uint8_t af[200]; size_t al = build_audio_frame(2, 65535, before, ad, 160, af, sizeof(af));
  AudioFrame a;
  CHECK(al == 166 && parse_audio_frame(af, al, a));
  CHECK(a.stream == 2 && a.seq == 65535 && a.state.predictor == -1234 && a.state.index == 40 && a.adpcm_len == 160 && a.adpcm[159] == 159);
  af[5] = 89; CHECK(!parse_audio_frame(af, al, a));
  CHECK(!parse_audio_frame(af, 6, a));
}

static void test_battery() {
  for (int i = 0; i < BATT_N; i++) CHECK(battery_percent(BATT_VECS[i].mv) == BATT_VECS[i].pct);
  for (int mv = 3000; mv < 4400; mv += 10) CHECK(battery_percent((uint16_t)(mv + 10)) >= battery_percent((uint16_t)mv));  // monotonic
}

struct FakeIO : AuthIO {
  uint8_t secret[32]; bool has = false, pairing = false; uint32_t now = 1000; uint32_t ctr = 0;
  bool load_secret(uint8_t out[32]) override { if (!has) return false; memcpy(out, secret, 32); return true; }
  bool store_secret(const uint8_t in[32]) override { memcpy(secret, in, 32); has = true; return true; }
  void erase_secret() override { has = false; memset(secret, 0, 32); }
  void random_bytes(uint8_t* out, size_t n) override { memcpy(out, AUTH_NONCE_G, n < 16 ? n : 16); (void)ctr; }
  uint32_t millis() override { return now; }
  bool pairing_mode() override { return pairing; }
};

static size_t hello(AuthSession& s, const uint8_t nc[16], uint16_t mtu, uint8_t* out) {
  uint8_t m[19]; m[0] = sex::auth::HELLO; memcpy(m + 1, nc, 16); m[17] = (uint8_t)mtu; m[18] = (uint8_t)(mtu >> 8);
  return s.handle(m, sizeof(m), out, 64);
}

static void test_auth() {
  FakeIO io; memcpy(io.secret, AUTH_SECRET, 32); io.has = true;
  AuthSession s(io, AUTH_DEVICE_ID);
  uint8_t out[64];

  // golden transcript shared with the Kotlin implementation
  size_t n = hello(s, AUTH_NONCE_C, AUTH_MTU, out);
  CHECK(n == 49 && out[0] == sex::auth::CHALLENGE);
  CHECK(memcmp(out + 1, AUTH_NONCE_G, 16) == 0);
  CHECK(memcmp(out + 17, AUTH_MAC_G, 32) == 0);
  CHECK(s.mtu() == 247 && !s.authenticated());
  uint8_t proof[33]; proof[0] = sex::auth::PROOF; memcpy(proof + 1, AUTH_MAC_C, 32);
  n = s.handle(proof, sizeof(proof), out, 64);
  CHECK(n == 1 && out[0] == sex::auth::AUTH_OK && s.authenticated());

  // reset on reconnect clears the session; a wrong proof is rejected
  s.reset(); CHECK(!s.authenticated());
  hello(s, AUTH_NONCE_C, 247, out);
  proof[5] ^= 0x40;
  n = s.handle(proof, sizeof(proof), out, 64);
  CHECK(n == 2 && out[0] == sex::auth::AUTH_FAIL && out[1] == sex::authfail::BAD_MAC && !s.authenticated());
  // PROOF without a fresh HELLO is refused
  proof[5] ^= 0x40;
  n = s.handle(proof, sizeof(proof), out, 64);
  CHECK(out[0] == sex::auth::AUTH_FAIL && out[1] == sex::authfail::BAD_STATE);

  // MTU below the minimum, malformed HELLO, unknown op
  n = hello(s, AUTH_NONCE_C, 100, out); CHECK(out[0] == sex::auth::AUTH_FAIL && out[1] == sex::authfail::MTU_TOO_SMALL);
  uint8_t shorthello[5] = {sex::auth::HELLO, 1, 2, 3, 4};
  n = s.handle(shorthello, sizeof(shorthello), out, 64); CHECK(out[1] == sex::authfail::BAD_STATE);
  uint8_t unk[1] = {0x66}; n = s.handle(unk, 1, out, 64); CHECK(out[0] == sex::auth::AUTH_FAIL);
  // MTU larger than we support is clamped
  hello(s, AUTH_NONCE_C, 517, out); CHECK(s.mtu() == 247);
}

static void test_lockout_and_provisioning() {
  FakeIO io; memcpy(io.secret, AUTH_SECRET, 32); io.has = true;
  AuthSession s(io, AUTH_DEVICE_ID);
  uint8_t out[64], proof[33]; proof[0] = sex::auth::PROOF; memset(proof + 1, 0xAB, 32);
  for (int i = 0; i < 5; i++) { io.now += 1000; hello(s, AUTH_NONCE_C, 247, out); s.handle(proof, sizeof(proof), out, 64); CHECK(out[1] == sex::authfail::BAD_MAC); }
  io.now += 1000;
  hello(s, AUTH_NONCE_C, 247, out);
  CHECK(out[0] == sex::auth::AUTH_FAIL && out[1] == sex::authfail::LOCKED_OUT);  // 5 failures within the window -> locked
  io.now += sex::k::AUTH_LOCKOUT_MS + 10;
  hello(s, AUTH_NONCE_C, 247, out); CHECK(out[0] == sex::auth::CHALLENGE);        // lockout expires
  // correct proof after the lockout works
  uint8_t good[33]; good[0] = sex::auth::PROOF; memcpy(good + 1, AUTH_MAC_C, 32);
  s.handle(good, sizeof(good), out, 64); CHECK(out[0] == sex::auth::AUTH_OK);

  // unprovisioned device refuses authentication and only accepts a secret while the pairing window is open
  FakeIO fresh; AuthSession u(fresh, AUTH_DEVICE_ID);
  hello(u, AUTH_NONCE_C, 247, out); CHECK(out[0] == sex::auth::AUTH_FAIL && out[1] == sex::authfail::NO_SECRET);
  uint8_t prov[33]; prov[0] = sex::auth::PROVISION; memcpy(prov + 1, AUTH_SECRET, 32);
  size_t n = u.handle(prov, sizeof(prov), out, 64);
  CHECK(n == 2 && out[0] == sex::auth::PROVISION_FAIL && out[1] == sex::provfail::NOT_PAIRING && !fresh.has);
  fresh.pairing = true;
  n = u.handle(prov, sizeof(prov), out, 64);
  CHECK(n == 1 && out[0] == sex::auth::PROVISION_OK && fresh.has && memcmp(fresh.secret, AUTH_SECRET, 32) == 0);
  hello(u, AUTH_NONCE_C, 247, out); CHECK(out[0] == sex::auth::CHALLENGE);
  // FORGET requires an authenticated session
  uint8_t fg[1] = {sex::auth::FORGET};
  u.handle(fg, 1, out, 64); CHECK(out[0] == sex::auth::AUTH_FAIL && fresh.has);
  hello(u, AUTH_NONCE_C, 247, out);  // a failed request resets the handshake; start again
  u.handle(good, sizeof(good), out, 64); CHECK(u.authenticated());
  n = u.handle(fg, 1, out, 64); CHECK(n == 1 && out[0] == sex::auth::FORGET_OK && !fresh.has && !u.authenticated());
}

int main() {
  test_crc(); test_hmac(); test_adpcm(); test_frames(); test_battery(); test_auth(); test_lockout_and_provisioning();
  printf("%d checks, %d failed\n", g_checks, g_fail);
  return g_fail ? 1 : 0;
}
