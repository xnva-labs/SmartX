#include "ble_gatt.h"

#include <Arduino.h>
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <esp_gap_ble_api.h>
#include <freertos/FreeRTOS.h>
#include <freertos/semphr.h>

#include "protocol_ids.h"

namespace ble {
static BLEServer* g_server = nullptr;
static BLECharacteristic *chStatus, *chAuth, *chControl, *chEvent, *chImage, *chAudioIn, *chAudioOut;
static volatile bool g_connected = false;
static AuthSession* g_auth = nullptr;
static Callbacks g_cb;
static SemaphoreHandle_t g_mx;

bool connected() { return g_connected; }

static void notify_char(BLECharacteristic* c, const uint8_t* d, size_t n) {
  if (!g_connected || !c) return;
  xSemaphoreTake(g_mx, portMAX_DELAY);
  c->setValue((uint8_t*)d, n);
  c->notify();
  xSemaphoreGive(g_mx);
}

void update_status(const uint8_t* d, size_t n) {
  xSemaphoreTake(g_mx, portMAX_DELAY);
  chStatus->setValue((uint8_t*)d, n);
  if (g_connected) chStatus->notify();
  xSemaphoreGive(g_mx);
}
void notify_event(const uint8_t* d, size_t n) { notify_char(chEvent, d, n); }
void notify_image(const uint8_t* d, size_t n) { notify_char(chImage, d, n); }
void notify_audio(const uint8_t* d, size_t n) { notify_char(chAudioIn, d, n); }

void start_advertising() { BLEDevice::startAdvertising(); }

void clear_bonds() {
  int n = esp_ble_get_bond_device_num();
  if (n <= 0) return;
  esp_ble_bond_dev_t* list = (esp_ble_bond_dev_t*)malloc(sizeof(esp_ble_bond_dev_t) * n);
  if (!list) return;
  if (esp_ble_get_bond_device_list(&n, list) == ESP_OK)
    for (int i = 0; i < n; i++) esp_ble_remove_bond_device(list[i].bd_addr);
  free(list);
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override {
    g_connected = true;
    if (g_auth) g_auth->reset();
    if (g_cb.on_connect) g_cb.on_connect();
  }
  void onDisconnect(BLEServer*) override {
    g_connected = false;
    if (g_auth) g_auth->reset();
    if (g_cb.on_disconnect) g_cb.on_disconnect();
    BLEDevice::startAdvertising();
  }
};

class WriteCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    const uint8_t* d = c->getData();
    size_t n = c->getLength();
    if (!d || n == 0) return;
    if (c == chAuth && g_auth) {
      uint8_t resp[64];
      size_t r = g_auth->handle(d, n, resp, sizeof(resp));
      if (r) notify_char(chAuth, resp, r);
    } else if (c == chControl) {
      if (g_cb.on_command) g_cb.on_command(d, n);
    } else if (c == chAudioOut) {
      if (g_cb.on_audio_out) g_cb.on_audio_out(d, n);
    }
  }
};

static BLECharacteristic* make(BLEService* svc, const char* uuid, uint32_t props, bool with_cccd) {
  BLECharacteristic* c = svc->createCharacteristic(BLEUUID(uuid), props);
  c->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED | ESP_GATT_PERM_WRITE_ENCRYPTED);  // all traffic requires an encrypted (bonded) link
  if (with_cccd) {
    BLE2902* d = new BLE2902();
    d->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED | ESP_GATT_PERM_WRITE_ENCRYPTED);
    c->addDescriptor(d);
  }
  return c;
}

void begin(const char* name, const char* info_json, AuthSession* auth, const Callbacks& cb) {
  g_auth = auth;
  g_cb = cb;
  g_mx = xSemaphoreCreateMutex();
  BLEDevice::init(name);
  BLEDevice::setMTU(sex::k::MAX_MTU);
  BLEDevice::setPower(ESP_PWR_LVL_P6);

  BLESecurity* sec = new BLESecurity();
  sec->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  sec->setCapability(ESP_IO_CAP_NONE);
  sec->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  g_server = BLEDevice::createServer();
  g_server->setCallbacks(new ServerCb());
  BLEService* svc = g_server->createService(BLEUUID(sex::SERVICE_UUID), 48);
  WriteCb* wcb = new WriteCb();

  BLECharacteristic* info = make(svc, sex::UUID_DEVICE_INFO, BLECharacteristic::PROPERTY_READ, false);
  info->setValue((uint8_t*)info_json, strlen(info_json));
  chStatus = make(svc, sex::UUID_STATUS, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY, true);
  chAuth = make(svc, sex::UUID_AUTH, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_NOTIFY, true);
  chAuth->setCallbacks(wcb);
  chControl = make(svc, sex::UUID_CONTROL, BLECharacteristic::PROPERTY_WRITE, false);
  chControl->setCallbacks(wcb);
  chEvent = make(svc, sex::UUID_EVENT, BLECharacteristic::PROPERTY_NOTIFY, true);
  chImage = make(svc, sex::UUID_IMAGE, BLECharacteristic::PROPERTY_NOTIFY, true);
  chAudioIn = make(svc, sex::UUID_AUDIO_IN, BLECharacteristic::PROPERTY_NOTIFY, true);
  chAudioOut = make(svc, sex::UUID_AUDIO_OUT, BLECharacteristic::PROPERTY_WRITE_NR, false);
  chAudioOut->setCallbacks(wcb);
  svc->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(sex::SERVICE_UUID);
  adv->setScanResponse(true);
  adv->setMinPreferred(0x06);
  BLEDevice::startAdvertising();
}
}  // namespace ble
