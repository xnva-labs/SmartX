#pragma once
#include <stddef.h>
#include <stdint.h>

#include "proto/auth.h"

namespace ble {
struct Callbacks {
  void (*on_command)(const uint8_t* data, size_t len);
  void (*on_audio_out)(const uint8_t* data, size_t len);
  void (*on_connect)();
  void (*on_disconnect)();
};
void begin(const char* device_name, const char* info_json, AuthSession* auth, const Callbacks& cb);
bool connected();
void update_status(const uint8_t* data, size_t len);  // sets the readable value and notifies when connected
void notify_event(const uint8_t* data, size_t len);
void notify_image(const uint8_t* data, size_t len);
void notify_audio(const uint8_t* data, size_t len);
void clear_bonds();
void start_advertising();
}  // namespace ble
