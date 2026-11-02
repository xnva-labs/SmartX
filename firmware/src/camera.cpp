#include "camera.h"

#include <Arduino.h>
#include <esp_camera.h>

#include "board.h"
#include "indicator.h"
#include "protocol_ids.h"

namespace cam {
static bool g_active = false;

bool active() { return g_active; }

static framesize_t size_for(uint8_t res) {
  switch (res) {
    case sex::res::QVGA: return FRAMESIZE_QVGA;
    case sex::res::SVGA: return FRAMESIZE_SVGA;
    case sex::res::XGA: return FRAMESIZE_XGA;
    default: return FRAMESIZE_VGA;
  }
}

uint8_t capture(uint8_t resolution, uint8_t quality, Frame& out) {
  if (g_active) return sex::capfail::BUSY;
  indicator::camera_on();  // visible to bystanders before any light reaches the sensor
  delay(CAMERA_LED_PRE_MS);

  camera_config_t c;
  memset(&c, 0, sizeof(c));
  c.ledc_channel = LEDC_CHANNEL_0;
  c.ledc_timer = LEDC_TIMER_0;
  c.pin_d0 = CAM_PIN_D0; c.pin_d1 = CAM_PIN_D1; c.pin_d2 = CAM_PIN_D2; c.pin_d3 = CAM_PIN_D3;
  c.pin_d4 = CAM_PIN_D4; c.pin_d5 = CAM_PIN_D5; c.pin_d6 = CAM_PIN_D6; c.pin_d7 = CAM_PIN_D7;
  c.pin_xclk = CAM_PIN_XCLK; c.pin_pclk = CAM_PIN_PCLK; c.pin_vsync = CAM_PIN_VSYNC; c.pin_href = CAM_PIN_HREF;
  c.pin_sscb_sda = CAM_PIN_SIOD; c.pin_sscb_scl = CAM_PIN_SIOC;
  c.pin_pwdn = CAM_PIN_PWDN; c.pin_reset = CAM_PIN_RESET;
  c.xclk_freq_hz = 20000000;
  c.pixel_format = PIXFORMAT_JPEG;
  c.frame_size = size_for(resolution);
  c.jpeg_quality = quality;
  c.fb_count = 1;
  c.fb_location = CAMERA_FB_IN_PSRAM;
  c.grab_mode = CAMERA_GRAB_WHEN_EMPTY;

  if (esp_camera_init(&c) != ESP_OK) {
    indicator::camera_release(CAMERA_LED_MIN_ON_MS);
    return sex::capfail::CAMERA_INIT;
  }
  g_active = true;
  sensor_t* s = esp_camera_sensor_get();
  if (s) {
    s->set_vflip(s, CAM_VFLIP);
    s->set_hmirror(s, CAM_HMIRROR);
  }
  for (int i = 0; i < 2; i++) {  // let auto exposure / white balance settle
    camera_fb_t* warm = esp_camera_fb_get();
    if (warm) esp_camera_fb_return(warm);
    delay(80);
  }
  camera_fb_t* fb = esp_camera_fb_get();
  if (!fb || fb->format != PIXFORMAT_JPEG || fb->len < 100) {
    if (fb) esp_camera_fb_return(fb);
    esp_camera_deinit();
    g_active = false;
    indicator::camera_release(CAMERA_LED_MIN_ON_MS);
    return sex::capfail::NO_FRAME;
  }
  out.data = fb->buf; out.len = fb->len; out.width = fb->width; out.height = fb->height; out.fb = fb;
  return 0;
}

void release(Frame& f) {
  if (f.fb) esp_camera_fb_return((camera_fb_t*)f.fb);
  f = Frame();
  if (g_active) { esp_camera_deinit(); g_active = false; }
  indicator::camera_release(CAMERA_LED_MIN_ON_MS);
}
}  // namespace cam
