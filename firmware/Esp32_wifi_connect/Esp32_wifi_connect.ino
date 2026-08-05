/*
  ESP32 FSR MCP Bridge Firmware

  Hardware:
  - Board: ESP32-S3, tested with ESP32-S3-N16R8 style boards.
  - Sensors: FSR402 pressure sensors on GPIO1-GPIO10.
  - Reset button: GPIO11 to GND, hold for 5 seconds to clear WiFi and sensor config.
  - GPIO12: reserved idle pin; firmware drives it LOW as an auxiliary ground helper.
  - Other safe non-ADC pins are also driven LOW as optional auxiliary ground pins.

  Network:
  - BLE provisioning service UUID: 0000FFFF-0000-1000-8000-00805F9B34FB.
  - HTTP API runs on port 80 after WiFi connects.
  - mDNS name: esp32.local.

  Serial debug:
  - Baud rate: 115200.
  - Logs show BLE, WiFi retry/failure, HTTP startup, mDNS and ADC configuration events.

  Version: V2.3.25
  License: BSD 2-Clause License
  Copyright (c) 2026 LINYUN XESJ
*/

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>

static const int RESET_BUTTON_PIN = 11;
static const int MAX_PIN_CONFIGS = 16;
static const int MAX_COMBO_CONFIGS = 8;
static const unsigned long RESET_HOLD_MILLIS = 5000;
static const unsigned long WIFI_CONNECT_TIMEOUT_MILLIS = 18000;
static const unsigned long WIFI_AUTH_FAIL_GRACE_MILLIS = 5000;
static const unsigned long WIFI_NO_AP_FAIL_GRACE_MILLIS = 3500;
static const unsigned long WIFI_RETRY_DELAY_MILLIS = 700;
static const unsigned long MULTIPLEX_INTERVAL_US = 350;
static const int FSR_CHANGE_THRESHOLD = 8;
static const int MAX_WIFI_CONNECT_ATTEMPTS = 2;

static const char* DEVICE_NAME = "ESP32-Provision";
static const char* MDNS_NAME = "esp32";
static const char* SERVICE_UUID = "0000FFFF-0000-1000-8000-00805F9B34FB";
static const char* APP_TO_ESP_UUID = "0000FF01-0000-1000-8000-00805F9B34FB";
static const char* ESP_TO_APP_UUID = "0000FF02-0000-1000-8000-00805F9B34FB";

// 按用户实际排针表开放。GPIO0/45/46 不作为传感器输入，避免启动相关风险。
static const int MANAGED_PINS[] = {
  1, 2, 3, 4, 5, 6, 7, 8, 9, 10
};
static const int MANAGED_PIN_COUNT = sizeof(MANAGED_PINS) / sizeof(MANAGED_PINS[0]);
static const int AUX_GROUND_PINS[] = {
  0, 12, 13, 14, 15, 16, 17, 18, 20, 21, 35, 36, 37, 38, 39, 40, 41, 42, 45, 46, 47, 48
};
static const int AUX_GROUND_PIN_COUNT = sizeof(AUX_GROUND_PINS) / sizeof(AUX_GROUND_PINS[0]);

Preferences preferences;
WebServer server(80);

BLEServer* bleServer = nullptr;
BLEService* bleService = nullptr;
BLEAdvertising* bleAdvertising = nullptr;
BLECharacteristic* notifyCharacteristic = nullptr;
BLECharacteristic* writeCharacteristic = nullptr;

enum ProvisionState {
  PROV_IDLE,
  PROV_WAITING_CREDENTIALS,
  PROV_CONNECTING_WIFI,
  PROV_SUCCESS,
  PROV_FAILED
};

struct ManagedPinConfig {
  bool used;
  int pin;
  String direction;
  String mode;
  int value;
  int order;
  String label;
};

struct ComboConfig {
  bool used;
  String id;
  String preset;
  String label;
  int order;
  int pins[8];
  int pinCount;
  int latchPin;
  int clockPin;
  int dataPin;
  String mapping[8];
  uint64_t pattern;
  int digits[4];
  int scanIndex;
  unsigned long lastScanUs;
};

ManagedPinConfig pinConfigs[MAX_PIN_CONFIGS];
ComboConfig comboConfigs[MAX_COMBO_CONFIGS];

ProvisionState provisionState = PROV_IDLE;
bool deviceProvisioned = false;
bool bleClientConnected = false;
bool resetButtonPressed = false;
bool resetAlreadyHandled = false;
bool webServerStarted = false;
bool mdnsStarted = false;
bool startupWifiPending = false;
bool wifiConnectFromProvisioning = false;

String savedSsid = "";
String savedPassword = "";
String deviceIp = "";
String pendingBlePayload = "";

unsigned long resetButtonPressedAt = 0;
unsigned long wifiConnectStartedAt = 0;
uint8_t lastWifiDisconnectReason = 0;
int wifiConnectAttempt = 0;
int fsrLastReportedValues[MANAGED_PIN_COUNT];
bool wifiAuthFailureSeen = false;
bool wifiNoApSeen = false;

void startBleProvisioning();
void startAdvertising();
void startWebServer();
void startMdns();
void queueBlePayload(const String& payload);
void flushBlePayload();
void connectToWiFi(const String& ssid, const String& password, bool fromProvisioning);
void beginWiFiAttempt();
void retryOrFailWiFi(const String& reason);
void failProvisioning(const String& reason);
void clearProvisioningDataAndRestart();
void handleResetButton();
void handleProvisioningTimeout();
void switchToBleProvisioningMode();
void loadConfigs();
void saveConfigs();
void applySavedConfigs();
void releaseUnusedManagedPins();
void updateMultiplexedCombos();
void handleSnapshotRequest();
void handleFsrChangesRequest();
void setupAuxiliaryGroundPins();

void printBanner() {
  Serial.println();
  Serial.println("========================================");
  Serial.println("ESP32 Smart Controller Boot");
  Serial.println("========================================");
}

bool isManagedPin(int pin) {
  for (int i = 0; i < MANAGED_PIN_COUNT; i++) {
    if (MANAGED_PINS[i] == pin) return true;
  }
  return false;
}

int fsrIndexForPin(int pin) {
  for (int i = 0; i < MANAGED_PIN_COUNT; i++) {
    if (MANAGED_PINS[i] == pin) return i;
  }
  return -1;
}

void resetFsrReportBaselines() {
  for (int i = 0; i < MANAGED_PIN_COUNT; i++) {
    fsrLastReportedValues[i] = -1;
  }
}

void setupAuxiliaryGroundPins() {
  for (int i = 0; i < AUX_GROUND_PIN_COUNT; i++) {
    pinMode(AUX_GROUND_PINS[i], OUTPUT);
    digitalWrite(AUX_GROUND_PINS[i], LOW);
  }
}

bool isReservedInputOnlyPin(int pin) {
  return true;
}

bool supportsDigitalInput(int pin) {
  return false;
}

bool supportsDigitalOutput(int pin) {
  return false;
}

bool supportsAnalogInput(int pin) {
  return isManagedPin(pin) && pin >= 1 && pin <= 10;
}

bool supportsAnalogOutput(int pin) {
  return false;
  // ESP32-S3 无传统 DAC，模拟输出统一使用 LEDC/PWM。
  return supportsDigitalOutput(pin);
}

String analogInputKind(int pin) {
  if (pin >= 1 && pin <= 10) return "ADC1";
  return "";
}

String analogOutputKind(int pin) {
  return "";
}

String noteForPin(int pin) {
  if (pin >= 1 && pin <= 10) return "FSR402 analog input, 12-bit range 0-4095.";
  if (pin == RESET_BUTTON_PIN) return "GPIO11 reset button, hold for 5 seconds to clear provisioning.";
  if (pin == 12) return "Reserved idle pin, driven LOW as auxiliary ground helper.";
  return "Not exposed as a sensor pin in this firmware.";
}

int clampAnalogValue(int value) {
  return constrain(value, 0, 4095);
}

int normalizeDigitalValue(int value) {
  return value > 0 ? 1 : 0;
}

bool isAuthFailureReason(uint8_t reason) {
  return reason == 2 || reason == 15 || reason == 202;
}

bool isNoApFoundReason(uint8_t reason) {
  return reason == 201;
}

int segmentIndex(const String& key) {
  if (key == "A") return 0;
  if (key == "B") return 1;
  if (key == "C") return 2;
  if (key == "D") return 3;
  if (key == "E") return 4;
  if (key == "F") return 5;
  if (key == "G") return 6;
  return 7;
}

String defaultSegmentKey(int index) {
  static const char* keys[] = {"A", "B", "C", "D", "E", "F", "G", "DP"};
  return keys[index % 8];
}

int findConfigIndex(int pin) {
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (pinConfigs[i].used && pinConfigs[i].pin == pin) return i;
  }
  return -1;
}

int findFreeConfigIndex() {
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (!pinConfigs[i].used) return i;
  }
  return -1;
}

int findComboIndex(const String& id) {
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (comboConfigs[i].used && comboConfigs[i].id == id) return i;
  }
  return -1;
}

int findFreeComboIndex() {
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) return i;
  }
  return -1;
}

int nextOrderValue() {
  int maxOrder = -1;
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (pinConfigs[i].used) maxOrder = max(maxOrder, pinConfigs[i].order);
  }
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (comboConfigs[i].used) maxOrder = max(maxOrder, comboConfigs[i].order);
  }
  return maxOrder + 1;
}

String makeComboId() {
  return "combo-" + String((uint32_t)millis(), HEX) + "-" + String((uint32_t)random(0xFFFF), HEX);
}

String sourceForConfig(const ManagedPinConfig& config) {
  if (config.mode == "analog" && config.direction == "input") return analogInputKind(config.pin);
  if (config.mode == "analog" && config.direction == "output") return analogOutputKind(config.pin);
  return "DIGITAL";
}

bool validatePinConfig(int pin, const String& direction, const String& mode, String& error) {
  if (!isManagedPin(pin)) {
    error = "pin_not_allowed";
    return false;
  }
  if (direction != "input" || mode != "analog") {
    error = "fsr_requires_analog_input";
    return false;
  }
  if (direction != "input" && direction != "output") {
    error = "bad_direction";
    return false;
  }
  if (mode != "digital" && mode != "analog") {
    error = "bad_mode";
    return false;
  }
  if (direction == "input" && mode == "digital" && !supportsDigitalInput(pin)) {
    error = "digital_input_not_supported";
    return false;
  }
  if (direction == "input" && mode == "analog" && !supportsAnalogInput(pin)) {
    error = "analog_input_not_supported";
    return false;
  }
  if (direction == "output" && mode == "digital" && !supportsDigitalOutput(pin)) {
    error = "digital_output_not_supported";
    return false;
  }
  if (direction == "output" && mode == "analog" && !supportsAnalogOutput(pin)) {
    error = "analog_output_not_supported";
    return false;
  }
  return true;
}

bool validateOutputPin(int pin, String& error) {
  if (!supportsDigitalOutput(pin)) {
    error = "output_pin_not_supported";
    return false;
  }
  return true;
}

bool configUsesPin(int pin) {
  if (pin == RESET_BUTTON_PIN) return true;
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (pinConfigs[i].used && pinConfigs[i].pin == pin) return true;
  }
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) continue;
    if (comboConfigs[i].preset == "segment_direct") {
      for (int j = 0; j < comboConfigs[i].pinCount; j++) {
        if (comboConfigs[i].pins[j] == pin) return true;
      }
    } else if (
      comboConfigs[i].latchPin == pin ||
      comboConfigs[i].clockPin == pin ||
      comboConfigs[i].dataPin == pin
    ) {
      return true;
    }
  }
  return false;
}

void releaseManagedPin(int pin) {
  if (pin == RESET_BUTTON_PIN) {
    pinMode(pin, INPUT_PULLUP);
    return;
  }
  if (supportsDigitalOutput(pin)) {
    ledcDetach(pin);
    digitalWrite(pin, LOW);
  }
  pinMode(pin, INPUT);
}

void releaseUnusedManagedPins() {
  for (int i = 0; i < MANAGED_PIN_COUNT; i++) {
    int pin = MANAGED_PINS[i];
    if (!configUsesPin(pin)) {
      releaseManagedPin(pin);
    }
  }
}

void applyPinConfig(int index) {
  if (index < 0 || index >= MAX_PIN_CONFIGS || !pinConfigs[index].used) return;
  ManagedPinConfig& config = pinConfigs[index];
  if (config.direction == "output") {
    pinMode(config.pin, OUTPUT);
    if (config.mode == "analog") {
      config.value = clampAnalogValue(config.value);
      analogWrite(config.pin, config.value);
    } else {
      config.value = normalizeDigitalValue(config.value);
      digitalWrite(config.pin, config.value == 1 ? HIGH : LOW);
    }
  } else {
    pinMode(config.pin, INPUT);
  }
}

int readPinValue(const ManagedPinConfig& config) {
  if (config.direction == "output") {
    return config.mode == "analog" ? clampAnalogValue(config.value) : normalizeDigitalValue(config.value);
  }
  if (config.mode == "analog") {
    int raw = analogRead(config.pin);
    return constrain(raw, 0, 4095);
  }
  return digitalRead(config.pin) == HIGH ? 1 : 0;
}

void write595Byte(int latchPin, int clockPin, int dataPin, uint8_t value) {
  digitalWrite(latchPin, LOW);
  shiftOut(dataPin, clockPin, MSBFIRST, value);
  digitalWrite(latchPin, HIGH);
}

uint8_t reverseBits(uint8_t value) {
  value = (uint8_t)(((value & 0xF0) >> 4) | ((value & 0x0F) << 4));
  value = (uint8_t)(((value & 0xCC) >> 2) | ((value & 0x33) << 2));
  value = (uint8_t)(((value & 0xAA) >> 1) | ((value & 0x55) << 1));
  return value;
}

void write595Pair(int latchPin, int clockPin, int dataPin, uint8_t first, uint8_t second) {
  digitalWrite(latchPin, LOW);
  shiftOut(dataPin, clockPin, MSBFIRST, reverseBits(second));
  shiftOut(dataPin, clockPin, MSBFIRST, reverseBits(first));
  digitalWrite(latchPin, HIGH);
}

uint8_t activeSingleBit(int index, bool activeLow) {
  uint8_t bit = (uint8_t)(1 << index);
  return activeLow ? (uint8_t)(~bit & 0xFF) : bit;
}

uint8_t activeByte(uint8_t value, bool activeLow) {
  return activeLow ? (uint8_t)(~value & 0xFF) : value;
}

void clear595PairOutputs(ComboConfig& combo) {
  if (combo.latchPin < 0 || combo.clockPin < 0 || combo.dataPin < 0) return;
  if (combo.preset == "segment_4digit") {
    write595Pair(combo.latchPin, combo.clockPin, combo.dataPin, 0xFF, 0x00);
  } else if (combo.preset == "matrix_8x8") {
    write595Pair(combo.latchPin, combo.clockPin, combo.dataPin, 0xFF, 0x00);
  } else {
    write595Pair(combo.latchPin, combo.clockPin, combo.dataPin, 0x00, 0x00);
  }
}

void applyDirectSegmentCombo(ComboConfig& combo) {
  for (int i = 0; i < combo.pinCount; i++) {
    int bit = segmentIndex(combo.mapping[i]);
    bool active = ((combo.pattern >> bit) & 1ULL) == 1ULL;
    // 按实测修正：直连单位数码管为低电平点亮。
    digitalWrite(combo.pins[i], active ? LOW : HIGH);
  }
}

void applyComboPins(ComboConfig& combo) {
  if (combo.preset == "segment_direct") {
    for (int i = 0; i < combo.pinCount; i++) {
      pinMode(combo.pins[i], OUTPUT);
      digitalWrite(combo.pins[i], HIGH);
    }
    applyDirectSegmentCombo(combo);
    return;
  }

  pinMode(combo.latchPin, OUTPUT);
  pinMode(combo.clockPin, OUTPUT);
  pinMode(combo.dataPin, OUTPUT);
  digitalWrite(combo.latchPin, HIGH);
  digitalWrite(combo.clockPin, LOW);
  digitalWrite(combo.dataPin, LOW);

  if (combo.preset == "segment_595") {
    // 常见 74HC595 数码管模块段码为低电平有效。
    write595Byte(combo.latchPin, combo.clockPin, combo.dataPin, (uint8_t)(~combo.pattern & 0xFF));
  }
}

void applySavedConfigs() {
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) applyPinConfig(i);
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (comboConfigs[i].used) applyComboPins(comboConfigs[i]);
  }
  releaseUnusedManagedPins();
}

void updateMultiplexedCombos() {
  return;
  unsigned long now = micros();
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) continue;
    ComboConfig& combo = comboConfigs[i];
    if (combo.preset != "segment_4digit" && combo.preset != "matrix_8x8") continue;
    if (now - combo.lastScanUs < MULTIPLEX_INTERVAL_US) continue;
    combo.lastScanUs = now;

    if (combo.preset == "segment_4digit") {
      int physicalDigit = combo.scanIndex % 4;
      int sourceDigit = 3 - physicalDigit;
      uint8_t digitMask = activeSingleBit(physicalDigit, true);
      uint8_t segmentMask = activeByte((uint8_t)(combo.digits[sourceDigit] & 0xFF), false);
      clear595PairOutputs(combo);
      write595Pair(combo.latchPin, combo.clockPin, combo.dataPin, digitMask, segmentMask);
      combo.scanIndex = (combo.scanIndex + 1) % 4;
    } else {
      int row = combo.scanIndex % 8;
      uint8_t rowMask = activeSingleBit(row, true);
      uint8_t colMask = 0;
      for (int col = 0; col < 8; col++) {
        // App 图像到实物：左右翻转后逆时针旋转 90 度。
        int sourceRow = col;
        int sourceCol = row;
        int bit = sourceRow * 8 + sourceCol;
        if (((combo.pattern >> bit) & 1ULL) == 1ULL) {
          colMask |= (uint8_t)(1 << col);
        }
      }
      clear595PairOutputs(combo);
      write595Pair(combo.latchPin, combo.clockPin, combo.dataPin, rowMask, activeByte(colMask, false));
      combo.scanIndex = (combo.scanIndex + 1) % 8;
    }
  }
}

void writePinConfigJsonWithValue(JsonObject object, const ManagedPinConfig& config, int value) {
  object["id"] = "pin-" + String(config.pin);
  object["kind"] = "gpio";
  object["pin"] = config.pin;
  object["direction"] = config.direction;
  object["mode"] = config.mode;
  object["value"] = value;
  object["source"] = sourceForConfig(config);
  object["order"] = config.order;
  object["label"] = config.label;
}

void writePinConfigJson(JsonObject object, const ManagedPinConfig& config) {
  writePinConfigJsonWithValue(object, config, readPinValue(config));
}

void writeComboConfigJson(JsonObject object, const ComboConfig& combo) {
  object["id"] = combo.id;
  object["kind"] = "combo";
  object["pin"] = -1;
  object["direction"] = "output";
  object["mode"] = "digital";
  object["value"] = 0;
  object["source"] = combo.preset;
  object["order"] = combo.order;
  object["label"] = combo.label;
  object["preset"] = combo.preset;
  object["latchPin"] = combo.latchPin;
  object["clockPin"] = combo.clockPin;
  object["dataPin"] = combo.dataPin;
  object["pattern"] = (int64_t)combo.pattern;

  JsonArray pins = object.createNestedArray("pins");
  for (int i = 0; i < combo.pinCount; i++) pins.add(combo.pins[i]);

  JsonArray mapping = object.createNestedArray("mapping");
  for (int i = 0; i < 8; i++) mapping.add(combo.mapping[i]);

  JsonArray digits = object.createNestedArray("digits");
  for (int i = 0; i < 4; i++) digits.add(combo.digits[i]);
}

void sendJsonError(int code, const String& error) {
  DynamicJsonDocument doc(256);
  doc["ok"] = false;
  doc["error"] = error;
  String body;
  serializeJson(doc, body);
  server.send(code, "application/json", body);
}

void sendPinConfigOk(int index) {
  DynamicJsonDocument doc(512);
  doc["ok"] = true;
  JsonObject config = doc.createNestedObject("config");
  writePinConfigJson(config, pinConfigs[index]);
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void sendComboConfigOk(int index) {
  DynamicJsonDocument doc(1024);
  doc["ok"] = true;
  JsonObject config = doc.createNestedObject("config");
  writeComboConfigJson(config, comboConfigs[index]);
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

int parseIntCsv(const String& csv, int* output, int maxItems) {
  int count = 0;
  int start = 0;
  while (start <= csv.length() && count < maxItems) {
    int comma = csv.indexOf(',', start);
    if (comma < 0) comma = csv.length();
    String token = csv.substring(start, comma);
    token.trim();
    if (!token.isEmpty()) output[count++] = token.toInt();
    start = comma + 1;
  }
  return count;
}

int parseStringCsv(const String& csv, String* output, int maxItems) {
  int count = 0;
  int start = 0;
  while (start <= csv.length() && count < maxItems) {
    int comma = csv.indexOf(',', start);
    if (comma < 0) comma = csv.length();
    String token = csv.substring(start, comma);
    token.trim();
    if (!token.isEmpty()) output[count++] = token;
    start = comma + 1;
  }
  return count;
}

void handlePinsRequest() {
  DynamicJsonDocument doc(12288);
  doc["device"] = "ESP32-S3";
  doc["ip"] = WiFi.localIP().toString();
  doc["ssid"] = WiFi.SSID();

  JsonArray pins = doc.createNestedArray("pins");
  for (int i = 0; i < MANAGED_PIN_COUNT; i++) {
    int pin = MANAGED_PINS[i];
    JsonObject item = pins.createNestedObject();
    item["pin"] = pin;
    item["digitalInput"] = supportsDigitalInput(pin);
    item["digitalOutput"] = supportsDigitalOutput(pin);
    item["analogInput"] = supportsAnalogInput(pin);
    item["analogOutput"] = supportsAnalogOutput(pin);
    item["analogInputKind"] = analogInputKind(pin);
    item["analogOutputKind"] = analogOutputKind(pin);
    item["note"] = noteForPin(pin);
  }

  JsonArray configs = doc.createNestedArray("configs");
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (!pinConfigs[i].used) continue;
    JsonObject item = configs.createNestedObject();
    writePinConfigJson(item, pinConfigs[i]);
  }
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) continue;
    JsonObject item = configs.createNestedObject();
    writeComboConfigJson(item, comboConfigs[i]);
  }

  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void handleSnapshotRequest() {
  updateMultiplexedCombos();

  DynamicJsonDocument doc(8192);
  doc["status"] = 0;

  JsonArray configs = doc.createNestedArray("configs");
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (!pinConfigs[i].used) continue;
    JsonObject item = configs.createNestedObject();
    int value = readPinValue(pinConfigs[i]);
    writePinConfigJsonWithValue(item, pinConfigs[i], value);
    int fsrIndex = fsrIndexForPin(pinConfigs[i].pin);
    if (fsrIndex >= 0) fsrLastReportedValues[fsrIndex] = value;
    updateMultiplexedCombos();
  }
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) continue;
    JsonObject item = configs.createNestedObject();
    writeComboConfigJson(item, comboConfigs[i]);
    updateMultiplexedCombos();
  }

  String body;
  serializeJson(doc, body);
  updateMultiplexedCombos();
  server.send(200, "application/json", body);
}

void handleFsrChangesRequest() {
  DynamicJsonDocument doc(4096);
  doc["status"] = 0;

  JsonArray configs = doc.createNestedArray("configs");
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (!pinConfigs[i].used) continue;
    if (pinConfigs[i].direction != "input" || pinConfigs[i].mode != "analog") continue;

    int fsrIndex = fsrIndexForPin(pinConfigs[i].pin);
    if (fsrIndex < 0) continue;

    int value = readPinValue(pinConfigs[i]);
    int previousValue = fsrLastReportedValues[fsrIndex];
    bool firstReport = previousValue < 0;
    int delta = firstReport ? 0 : value - previousValue;
    bool changed = firstReport || abs(delta) >= FSR_CHANGE_THRESHOLD;

    if (changed) {
      JsonObject item = configs.createNestedObject();
      writePinConfigJsonWithValue(item, pinConfigs[i], value);
      item["previousValue"] = firstReport ? value : previousValue;
      item["delta"] = delta;
      fsrLastReportedValues[fsrIndex] = value;
    }
  }

  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void handlePinConfigRequest() {
  if (!server.hasArg("pin") || !server.hasArg("direction") || !server.hasArg("mode")) {
    sendJsonError(400, "missing_args");
    return;
  }

  int pin = server.arg("pin").toInt();
  String direction = server.arg("direction");
  String mode = server.arg("mode");
  direction.toLowerCase();
  mode.toLowerCase();

  String error;
  if (!validatePinConfig(pin, direction, mode, error)) {
    sendJsonError(400, error);
    return;
  }

  int index = findConfigIndex(pin);
  bool isNew = index < 0;
  if (index < 0) index = findFreeConfigIndex();
  if (index < 0) {
    sendJsonError(400, "config_full");
    return;
  }

  int value = server.hasArg("value") ? server.arg("value").toInt() : 0;
  pinConfigs[index].used = true;
  pinConfigs[index].pin = pin;
  pinConfigs[index].direction = direction;
  pinConfigs[index].mode = mode;
  pinConfigs[index].value = mode == "analog" ? clampAnalogValue(value) : normalizeDigitalValue(value);
  String defaultLabel = String("GPIO") + String(pin);
  pinConfigs[index].label = server.hasArg("label") ? server.arg("label") : defaultLabel;
  int fsrIndex = fsrIndexForPin(pin);
  if (fsrIndex >= 0) fsrLastReportedValues[fsrIndex] = -1;
  if (isNew) pinConfigs[index].order = nextOrderValue();

  applyPinConfig(index);
  saveConfigs();
  sendPinConfigOk(index);
}

void handlePinValueRequest() {
  if (!server.hasArg("pin") || !server.hasArg("value")) {
    sendJsonError(400, "missing_args");
    return;
  }

  int pin = server.arg("pin").toInt();
  int index = findConfigIndex(pin);
  if (index < 0) {
    sendJsonError(404, "config_not_found");
    return;
  }

  ManagedPinConfig& config = pinConfigs[index];
  if (config.direction != "output") {
    sendJsonError(400, "pin_is_input");
    return;
  }

  int value = server.arg("value").toInt();
  config.value = config.mode == "analog" ? clampAnalogValue(value) : normalizeDigitalValue(value);
  applyPinConfig(index);
  saveConfigs();
  sendPinConfigOk(index);
}

void handlePinDeleteRequest() {
  if (!server.hasArg("pin")) {
    sendJsonError(400, "missing_pin");
    return;
  }

  int pin = server.arg("pin").toInt();
  int index = findConfigIndex(pin);
  if (index < 0) {
    sendJsonError(404, "config_not_found");
    return;
  }

  if (pinConfigs[index].direction == "output") digitalWrite(pinConfigs[index].pin, LOW);
  int fsrIndex = fsrIndexForPin(pinConfigs[index].pin);
  if (fsrIndex >= 0) fsrLastReportedValues[fsrIndex] = -1;
  pinMode(pinConfigs[index].pin, INPUT);
  pinConfigs[index].used = false;
  saveConfigs();
  releaseUnusedManagedPins();

  DynamicJsonDocument doc(128);
  doc["ok"] = true;
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void clearComboPins(ComboConfig& combo) {
  if (combo.preset == "segment_direct") {
    for (int i = 0; i < combo.pinCount; i++) {
      digitalWrite(combo.pins[i], HIGH);
      pinMode(combo.pins[i], INPUT);
    }
  } else if (combo.preset == "segment_595") {
    write595Byte(combo.latchPin, combo.clockPin, combo.dataPin, 0xFF);
  } else {
    clear595PairOutputs(combo);
  }
}

void handleConfigDeleteRequest() {
  if (!server.hasArg("id")) {
    sendJsonError(400, "missing_id");
    return;
  }
  String id = server.arg("id");

  if (id.startsWith("pin-")) {
    int pin = id.substring(4).toInt();
    int pinIndex = findConfigIndex(pin);
    if (pinIndex >= 0) {
      if (pinConfigs[pinIndex].direction == "output") digitalWrite(pinConfigs[pinIndex].pin, LOW);
      pinMode(pinConfigs[pinIndex].pin, INPUT);
      pinConfigs[pinIndex].used = false;
      saveConfigs();
      releaseUnusedManagedPins();
      DynamicJsonDocument doc(128);
      doc["ok"] = true;
      String body;
      serializeJson(doc, body);
      server.send(200, "application/json", body);
      return;
    }
  }

  int comboIndex = findComboIndex(id);
  if (comboIndex < 0) {
    sendJsonError(404, "config_not_found");
    return;
  }
  clearComboPins(comboConfigs[comboIndex]);
  comboConfigs[comboIndex].used = false;
  saveConfigs();
  releaseUnusedManagedPins();

  DynamicJsonDocument doc(128);
  doc["ok"] = true;
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void handleConfigOrderRequest() {
  if (!server.hasArg("ids")) {
    sendJsonError(400, "missing_ids");
    return;
  }

  String ids = server.arg("ids");
  int order = 0;
  int start = 0;
  while (start <= ids.length()) {
    int comma = ids.indexOf(',', start);
    if (comma < 0) comma = ids.length();
    String id = ids.substring(start, comma);
    id.trim();
    if (id.startsWith("pin-")) {
      int index = findConfigIndex(id.substring(4).toInt());
      if (index >= 0) pinConfigs[index].order = order++;
    } else {
      int index = findComboIndex(id);
      if (index >= 0) comboConfigs[index].order = order++;
    }
    start = comma + 1;
  }

  saveConfigs();
  DynamicJsonDocument doc(128);
  doc["ok"] = true;
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void handleComboConfigRequest() {
  if (!server.hasArg("preset")) {
    sendJsonError(400, "missing_preset");
    return;
  }

  String preset = server.arg("preset");
  String id = server.hasArg("id") ? server.arg("id") : "";
  String label = server.hasArg("label") ? server.arg("label") : preset;

  int index = id.isEmpty() ? -1 : findComboIndex(id);
  bool isNew = index < 0;
  if (index < 0) index = findFreeComboIndex();
  if (index < 0) {
    sendJsonError(400, "combo_full");
    return;
  }

  if (!isNew) {
    clearComboPins(comboConfigs[index]);
  }

  ComboConfig& combo = comboConfigs[index];
  combo.used = true;
  combo.id = id.isEmpty() ? makeComboId() : id;
  combo.preset = preset;
  combo.label = label;
  if (isNew) combo.order = nextOrderValue();
  combo.pinCount = 0;
  combo.pattern = server.hasArg("pattern") ? (uint64_t)strtoll(server.arg("pattern").c_str(), nullptr, 10) : 0ULL;
  combo.scanIndex = 0;
  combo.lastScanUs = 0;
  for (int i = 0; i < 4; i++) combo.digits[i] = 0;
  for (int i = 0; i < 8; i++) combo.mapping[i] = defaultSegmentKey(i);

  String error;
  if (preset == "segment_direct") {
    if (!server.hasArg("pins")) {
      sendJsonError(400, "missing_pins");
      return;
    }
    combo.pinCount = parseIntCsv(server.arg("pins"), combo.pins, 8);
    if (combo.pinCount != 8) {
      sendJsonError(400, "need_8_pins");
      return;
    }
    for (int i = 0; i < combo.pinCount; i++) {
      if (!validateOutputPin(combo.pins[i], error)) {
        sendJsonError(400, error);
        return;
      }
    }
    if (server.hasArg("mapping")) {
      parseStringCsv(server.arg("mapping"), combo.mapping, 8);
    }
  } else {
    if (!server.hasArg("latch") || !server.hasArg("clock") || !server.hasArg("data")) {
      sendJsonError(400, "missing_shift_pins");
      return;
    }
    combo.latchPin = server.arg("latch").toInt();
    combo.clockPin = server.arg("clock").toInt();
    combo.dataPin = server.arg("data").toInt();
    if (!validateOutputPin(combo.latchPin, error) ||
        !validateOutputPin(combo.clockPin, error) ||
        !validateOutputPin(combo.dataPin, error)) {
      sendJsonError(400, error);
      return;
    }
    if (server.hasArg("digits")) {
      parseIntCsv(server.arg("digits"), combo.digits, 4);
    }
  }

  applyComboPins(combo);
  saveConfigs();
  sendComboConfigOk(index);
}

void handleComboValueRequest() {
  if (!server.hasArg("id")) {
    sendJsonError(400, "missing_id");
    return;
  }
  int index = findComboIndex(server.arg("id"));
  if (index < 0) {
    sendJsonError(404, "config_not_found");
    return;
  }
  ComboConfig& combo = comboConfigs[index];
  if (server.hasArg("pattern")) {
    combo.pattern = (uint64_t)strtoll(server.arg("pattern").c_str(), nullptr, 10);
  }
  if (server.hasArg("digits")) {
    parseIntCsv(server.arg("digits"), combo.digits, 4);
  }
  applyComboPins(combo);
  saveConfigs();
  sendComboConfigOk(index);
}

void handleDirectSegmentTestRequest() {
  if (!server.hasArg("pins") || !server.hasArg("mask")) {
    sendJsonError(400, "missing_args");
    return;
  }

  int pins[8];
  int count = parseIntCsv(server.arg("pins"), pins, 8);
  int mask = server.arg("mask").toInt();
  String error;
  if (count != 8) {
    sendJsonError(400, "need_8_pins");
    return;
  }
  for (int i = 0; i < 8; i++) {
    if (!validateOutputPin(pins[i], error)) {
      sendJsonError(400, error);
      return;
    }
    pinMode(pins[i], OUTPUT);
    digitalWrite(pins[i], (mask & (1 << i)) ? LOW : HIGH);
  }

  DynamicJsonDocument doc(128);
  doc["ok"] = true;
  String body;
  serializeJson(doc, body);
  server.send(200, "application/json", body);
}

void loadConfigs() {
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    pinConfigs[i].used = false;
    pinConfigs[i].pin = -1;
    pinConfigs[i].direction = "output";
    pinConfigs[i].mode = "digital";
    pinConfigs[i].value = 0;
    pinConfigs[i].order = i;
    pinConfigs[i].label = "";
  }
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    comboConfigs[i].used = false;
    comboConfigs[i].id = "";
    comboConfigs[i].preset = "";
    comboConfigs[i].label = "";
    comboConfigs[i].order = MAX_PIN_CONFIGS + i;
    comboConfigs[i].pinCount = 0;
    comboConfigs[i].latchPin = -1;
    comboConfigs[i].clockPin = -1;
    comboConfigs[i].dataPin = -1;
    comboConfigs[i].pattern = 0;
    comboConfigs[i].scanIndex = 0;
    comboConfigs[i].lastScanUs = 0;
    for (int j = 0; j < 4; j++) comboConfigs[i].digits[j] = 0;
    for (int j = 0; j < 8; j++) comboConfigs[i].mapping[j] = defaultSegmentKey(j);
  }

  preferences.begin("pins", true);
  String raw = preferences.getString("configs", "");
  preferences.end();
  if (raw.isEmpty()) return;

  DynamicJsonDocument doc(4096);
  DeserializationError error = deserializeJson(doc, raw);
  if (error) {
    Serial.println("Saved config parse failed");
    return;
  }

  JsonArray pinsArray = doc["pins"].is<JsonArray>() ? doc["pins"].as<JsonArray>() : doc["configs"].as<JsonArray>();
  int pinIndex = 0;
  for (JsonObject item : pinsArray) {
    if (pinIndex >= MAX_PIN_CONFIGS) break;
    int pin = item["pin"] | -1;
    String direction = item["direction"] | "output";
    String mode = item["mode"] | "digital";
    int value = item["value"] | 0;
    String validationError;
    if (!validatePinConfig(pin, direction, mode, validationError)) continue;
    pinConfigs[pinIndex].used = true;
    pinConfigs[pinIndex].pin = pin;
    pinConfigs[pinIndex].direction = direction;
    pinConfigs[pinIndex].mode = mode;
    pinConfigs[pinIndex].value = mode == "analog" ? clampAnalogValue(value) : normalizeDigitalValue(value);
    pinConfigs[pinIndex].order = item["order"] | pinIndex;
    String defaultLabel = String("GPIO") + String(pin);
    pinConfigs[pinIndex].label = item["label"] | defaultLabel;
    pinIndex++;
  }

  // FSR402 版本不再加载旧组合输出配置，避免历史 74HC595/数码管配置继续刷新造成频闪。
  return;

  JsonArray combosArray = doc["combos"].as<JsonArray>();
  int comboIndex = 0;
  for (JsonObject item : combosArray) {
    if (comboIndex >= MAX_COMBO_CONFIGS) break;
    ComboConfig& combo = comboConfigs[comboIndex];
    combo.used = true;
    combo.id = item["id"] | makeComboId();
    combo.preset = item["preset"] | "";
    combo.label = item["label"] | combo.preset;
    combo.order = item["order"] | (MAX_PIN_CONFIGS + comboIndex);
    combo.pattern = (uint64_t)((int64_t)(item["pattern"] | 0));
    combo.pinCount = 0;
    combo.latchPin = item["latchPin"] | -1;
    combo.clockPin = item["clockPin"] | -1;
    combo.dataPin = item["dataPin"] | -1;
    JsonArray comboPins = item["pins"].as<JsonArray>();
    for (int i = 0; i < 8 && i < comboPins.size(); i++) combo.pins[combo.pinCount++] = comboPins[i] | -1;
    JsonArray mapping = item["mapping"].as<JsonArray>();
    for (int i = 0; i < 8; i++) combo.mapping[i] = i < mapping.size() ? (mapping[i] | defaultSegmentKey(i)) : defaultSegmentKey(i);
    JsonArray digits = item["digits"].as<JsonArray>();
    for (int i = 0; i < 4; i++) combo.digits[i] = i < digits.size() ? (digits[i] | 0) : 0;
    combo.scanIndex = 0;
    combo.lastScanUs = 0;
    comboIndex++;
  }
}

void saveConfigs() {
  DynamicJsonDocument doc(4096);
  JsonArray pins = doc.createNestedArray("pins");
  for (int i = 0; i < MAX_PIN_CONFIGS; i++) {
    if (!pinConfigs[i].used) continue;
    JsonObject item = pins.createNestedObject();
    item["pin"] = pinConfigs[i].pin;
    item["direction"] = pinConfigs[i].direction;
    item["mode"] = pinConfigs[i].mode;
    item["value"] = pinConfigs[i].value;
    item["order"] = pinConfigs[i].order;
    item["label"] = pinConfigs[i].label;
  }

  JsonArray combos = doc.createNestedArray("combos");
  for (int i = 0; i < MAX_COMBO_CONFIGS; i++) {
    if (!comboConfigs[i].used) continue;
    JsonObject item = combos.createNestedObject();
    item["id"] = comboConfigs[i].id;
    item["preset"] = comboConfigs[i].preset;
    item["label"] = comboConfigs[i].label;
    item["order"] = comboConfigs[i].order;
    item["latchPin"] = comboConfigs[i].latchPin;
    item["clockPin"] = comboConfigs[i].clockPin;
    item["dataPin"] = comboConfigs[i].dataPin;
    item["pattern"] = (int64_t)comboConfigs[i].pattern;
    JsonArray comboPins = item.createNestedArray("pins");
    for (int p = 0; p < comboConfigs[i].pinCount; p++) comboPins.add(comboConfigs[i].pins[p]);
    JsonArray mapping = item.createNestedArray("mapping");
    for (int m = 0; m < 8; m++) mapping.add(comboConfigs[i].mapping[m]);
    JsonArray digits = item.createNestedArray("digits");
    for (int d = 0; d < 4; d++) digits.add(comboConfigs[i].digits[d]);
  }

  String body;
  serializeJson(doc, body);
  preferences.begin("pins", false);
  preferences.putString("configs", body);
  preferences.end();
}

void queueBlePayload(const String& payload) {
  pendingBlePayload = payload;
  flushBlePayload();
}

void flushBlePayload() {
  if (pendingBlePayload.isEmpty() || notifyCharacteristic == nullptr) return;
  notifyCharacteristic->setValue(pendingBlePayload.c_str());
  if (bleClientConnected) notifyCharacteristic->notify();
  Serial.print("BLE response: ");
  Serial.println(pendingBlePayload);
  pendingBlePayload = "";
}

void saveCredentials(const String& ssid, const String& password) {
  preferences.begin("wifi", false);
  preferences.putString("ssid", ssid);
  preferences.putString("password", password);
  preferences.end();
}

void clearSavedCredentials() {
  preferences.begin("wifi", false);
  preferences.clear();
  preferences.end();
}

bool loadSavedWiFi() {
  preferences.begin("wifi", true);
  savedSsid = preferences.getString("ssid", "");
  savedPassword = preferences.getString("password", "");
  preferences.end();
  if (savedSsid.isEmpty()) return false;
  Serial.print("Saved WiFi found: ");
  Serial.println(savedSsid);
  return true;
}

void startWebServer() {
  if (webServerStarted) return;

  server.on("/", []() {
    server.send(200, "text/plain", "ESP32 Smart Controller");
  });

  server.on("/on", []() {
    server.send(200, "application/json", "{\"status\":0}");
  });

  server.on("/off", []() {
    server.send(200, "application/json", "{\"status\":0}");
  });

  server.on("/status", []() {
    server.send(200, "application/json", "{\"status\":0}");
  });

  server.on("/pins", handlePinsRequest);
  server.on("/snapshot", handleSnapshotRequest);
  server.on("/fsr/changes", handleFsrChangesRequest);
  server.on("/pin/config", HTTP_POST, handlePinConfigRequest);
  // Keep GET compatibility for APKs released before the write API moved to POST.
  server.on("/pin/config", HTTP_GET, handlePinConfigRequest);
  server.on("/pin/value", handlePinValueRequest);
  server.on("/pin/delete", HTTP_POST, handlePinDeleteRequest);
  server.on("/pin/delete", HTTP_GET, handlePinDeleteRequest);
  server.on("/config/delete", handleConfigDeleteRequest);
  server.on("/config/order", handleConfigOrderRequest);
  server.on("/combo/config", handleComboConfigRequest);
  server.on("/combo/value", handleComboValueRequest);
  server.on("/combo/direct/test", handleDirectSegmentTestRequest);

  server.onNotFound([]() {
    sendJsonError(404, "not_found");
  });

  server.begin();
  webServerStarted = true;
  Serial.println("HTTP server started on port 80");
}

void startMdns() {
  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
  }
  if (!MDNS.begin(MDNS_NAME)) {
    Serial.println("mDNS start failed");
    return;
  }
  MDNS.addService("http", "tcp", 80);
  MDNS.addService("esp32", "tcp", 80);
  mdnsStarted = true;
  Serial.println("mDNS started: esp32.local");
}

void connectToWiFi(const String& ssid, const String& password, bool fromProvisioning) {
  savedSsid = ssid;
  savedPassword = password;
  wifiConnectAttempt = 0;
  wifiConnectFromProvisioning = fromProvisioning;
  provisionState = fromProvisioning ? PROV_CONNECTING_WIFI : PROV_IDLE;
  startupWifiPending = !fromProvisioning;

  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
  }

  WiFi.disconnect(true, true);
  delay(300);
  WiFi.mode(WIFI_STA);

  beginWiFiAttempt();
}

void beginWiFiAttempt() {
  wifiConnectAttempt++;
  wifiConnectStartedAt = millis();
  lastWifiDisconnectReason = 0;
  wifiAuthFailureSeen = false;
  wifiNoApSeen = false;

  Serial.print("Connecting WiFi: ");
  Serial.println(savedSsid);
  Serial.print("WiFi attempt ");
  Serial.print(wifiConnectAttempt);
  Serial.print("/");
  Serial.println(MAX_WIFI_CONNECT_ATTEMPTS);

  WiFi.begin(savedSsid.c_str(), savedPassword.c_str());

  if (wifiConnectFromProvisioning) {
    queueBlePayload(
      "{\"status\":\"connecting\",\"attempt\":" +
      String(wifiConnectAttempt) +
      ",\"maxAttempts\":" +
      String(MAX_WIFI_CONNECT_ATTEMPTS) +
      "}"
    );
  }
}

void retryOrFailWiFi(const String& reason) {
  Serial.print("WiFi attempt failed: ");
  Serial.println(reason);

  if (wifiConnectAttempt < MAX_WIFI_CONNECT_ATTEMPTS) {
    Serial.println("Retrying WiFi connection");
    WiFi.disconnect(false, false);
    delay(WIFI_RETRY_DELAY_MILLIS);
    WiFi.mode(WIFI_STA);
    beginWiFiAttempt();
    return;
  }

  Serial.println("WiFi failed after all attempts");
  if (wifiConnectFromProvisioning) {
    failProvisioning(reason);
  } else {
    queueBlePayload(
      "{\"status\":\"fail\",\"reason\":\"" +
      reason +
      "\",\"attempt\":" +
      String(wifiConnectAttempt) +
      ",\"maxAttempts\":" +
      String(MAX_WIFI_CONNECT_ATTEMPTS) +
      "}"
    );
    switchToBleProvisioningMode();
  }
}

void handleWiFiDisconnected(uint8_t reason) {
  lastWifiDisconnectReason = reason;
  if (isAuthFailureReason(reason)) wifiAuthFailureSeen = true;
  if (isNoApFoundReason(reason)) wifiNoApSeen = true;

  // 连接过程会连续抛出很多底层断开事件，这里只记录原因，避免串口刷屏拖慢判断。
  if (provisionState == PROV_CONNECTING_WIFI || (startupWifiPending && !deviceProvisioned)) {
    return;
  }

  Serial.print("WiFi disconnected, reason: ");
  Serial.println(reason);
  if (deviceProvisioned) {
    WiFi.reconnect();
  }
}

void onWiFiGotIp() {
  deviceIp = WiFi.localIP().toString();
  deviceProvisioned = true;
  startupWifiPending = false;
  wifiConnectAttempt = 0;

  Serial.println("WiFi connected");
  Serial.print("IP address: ");
  Serial.println(deviceIp);

  if (provisionState == PROV_CONNECTING_WIFI) {
    provisionState = PROV_SUCCESS;
    saveCredentials(savedSsid, savedPassword);
    DynamicJsonDocument doc(256);
    doc["status"] = "ok";
    doc["ip"] = deviceIp;
    doc["ssid"] = savedSsid;
    String payload;
    serializeJson(doc, payload);
    queueBlePayload(payload);
  }
  wifiConnectFromProvisioning = false;

  startWebServer();
  startMdns();
}

void WiFiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  switch (event) {
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
      onWiFiGotIp();
      break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
      handleWiFiDisconnected(info.wifi_sta_disconnected.reason);
      break;
    default:
      break;
  }
}

void failProvisioning(const String& reason) {
  provisionState = PROV_FAILED;
  WiFi.disconnect(false, false);
  queueBlePayload(
    "{\"status\":\"fail\",\"reason\":\"" +
    reason +
    "\",\"attempt\":" +
    String(wifiConnectAttempt) +
    ",\"maxAttempts\":" +
    String(MAX_WIFI_CONNECT_ATTEMPTS) +
    "}"
  );
  provisionState = PROV_WAITING_CREDENTIALS;
  wifiConnectFromProvisioning = false;
  startAdvertising();
}

void handleProvisioningTimeout() {
  if (provisionState == PROV_CONNECTING_WIFI) {
    unsigned long elapsed = millis() - wifiConnectStartedAt;
    if (wifiNoApSeen && elapsed >= WIFI_NO_AP_FAIL_GRACE_MILLIS) {
      Serial.println("WiFi SSID not found");
      retryOrFailWiFi("ssid_not_found");
      return;
    }
    if (wifiAuthFailureSeen && elapsed >= WIFI_AUTH_FAIL_GRACE_MILLIS) {
      Serial.println("WiFi auth failed");
      retryOrFailWiFi("auth_failed");
      return;
    }
    if (elapsed >= WIFI_CONNECT_TIMEOUT_MILLIS) {
      Serial.println("WiFi provisioning timeout");
      retryOrFailWiFi("wifi_timeout");
      return;
    }
  }

  if (!startupWifiPending || deviceProvisioned) return;
  unsigned long elapsed = millis() - wifiConnectStartedAt;
  if (wifiNoApSeen && elapsed >= WIFI_NO_AP_FAIL_GRACE_MILLIS) {
    Serial.println("Saved WiFi SSID not found");
    retryOrFailWiFi("ssid_not_found");
    return;
  }
  if (wifiAuthFailureSeen && elapsed >= WIFI_AUTH_FAIL_GRACE_MILLIS) {
    Serial.println("Saved WiFi auth failed");
    retryOrFailWiFi("auth_failed");
    return;
  }
  if (elapsed >= WIFI_CONNECT_TIMEOUT_MILLIS) {
    Serial.println("Saved WiFi connect timeout");
    retryOrFailWiFi("wifi_timeout");
  }
}

class ProvisionServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    bleClientConnected = true;
    Serial.println("BLE client connected");
    flushBlePayload();
  }

  void onDisconnect(BLEServer* pServer) override {
    bleClientConnected = false;
    Serial.println("BLE client disconnected, advertising again");
    delay(200);
    startAdvertising();
  }
};

class ProvisionWriteCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String rawValue = characteristic->getValue();
    if (rawValue.isEmpty()) {
      Serial.println("Empty BLE provisioning payload");
      return;
    }

    Serial.print("BLE provisioning payload: ");
    Serial.println(rawValue);

    DynamicJsonDocument doc(512);
    DeserializationError error = deserializeJson(doc, rawValue);
    if (error) {
      Serial.println("BLE JSON parse failed");
      queueBlePayload("{\"status\":\"fail\",\"reason\":\"json_parse_error\"}");
      return;
    }

    String ssid = doc["ssid"] | "";
    String password = doc["password"] | "";
    if (ssid.isEmpty()) {
      queueBlePayload("{\"status\":\"fail\",\"reason\":\"ssid_empty\"}");
      return;
    }
    connectToWiFi(ssid, password, true);
  }
};

void startAdvertising() {
  if (bleAdvertising == nullptr) bleAdvertising = BLEDevice::getAdvertising();
  bleAdvertising->stop();
  bleAdvertising->addServiceUUID(SERVICE_UUID);
  bleAdvertising->setScanResponse(true);
  bleAdvertising->setMinPreferred(0x06);
  bleAdvertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.println("BLE advertising started");
}

void switchToBleProvisioningMode() {
  startupWifiPending = false;
  deviceProvisioned = false;
  provisionState = PROV_WAITING_CREDENTIALS;
  if (mdnsStarted) {
    MDNS.end();
    mdnsStarted = false;
  }
  WiFi.disconnect(false, false);
  WiFi.mode(WIFI_OFF);
  delay(300);
  if (bleServer == nullptr) {
    startBleProvisioning();
  } else {
    startAdvertising();
    Serial.println("BLE provisioning mode restored");
  }
}

void startBleProvisioning() {
  BLEDevice::init(DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ProvisionServerCallbacks());
  bleService = bleServer->createService(SERVICE_UUID);

  notifyCharacteristic = bleService->createCharacteristic(
    ESP_TO_APP_UUID,
    BLECharacteristic::PROPERTY_READ |
      BLECharacteristic::PROPERTY_NOTIFY |
      BLECharacteristic::PROPERTY_INDICATE
  );
  notifyCharacteristic->addDescriptor(new BLE2902());
  notifyCharacteristic->setValue("{\"status\":\"ready\"}");

  writeCharacteristic = bleService->createCharacteristic(
    APP_TO_ESP_UUID,
    BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_WRITE_NR
  );
  writeCharacteristic->setCallbacks(new ProvisionWriteCallbacks());

  bleService->start();
  bleAdvertising = BLEDevice::getAdvertising();
  provisionState = PROV_WAITING_CREDENTIALS;
  startAdvertising();
  Serial.println("BLE provisioning service started");
}

void clearProvisioningDataAndRestart() {
  Serial.println("Reset button held for 5 seconds, clearing WiFi and configs");
  clearSavedCredentials();
  preferences.begin("pins", false);
  preferences.clear();
  preferences.end();
  WiFi.disconnect(true, true);
  deviceProvisioned = false;
  provisionState = PROV_IDLE;
  savedSsid = "";
  savedPassword = "";
  deviceIp = "";

  ESP.restart();
}

void handleResetButton() {
  if (findConfigIndex(RESET_BUTTON_PIN) >= 0) {
    return;
  }
  bool pressed = digitalRead(RESET_BUTTON_PIN) == LOW;
  if (pressed && !resetButtonPressed) {
    resetButtonPressed = true;
    resetAlreadyHandled = false;
    resetButtonPressedAt = millis();
    Serial.println("Reset button pressed");
    return;
  }
  if (!pressed && resetButtonPressed) {
    resetButtonPressed = false;
    resetAlreadyHandled = false;
    Serial.println("Reset button released");
    return;
  }
  if (pressed && !resetAlreadyHandled && millis() - resetButtonPressedAt >= RESET_HOLD_MILLIS) {
    resetAlreadyHandled = true;
    clearProvisioningDataAndRestart();
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
  pinMode(RESET_BUTTON_PIN, INPUT_PULLUP);
  setupAuxiliaryGroundPins();
  analogReadResolution(12);
  resetFsrReportBaselines();
  randomSeed((uint32_t)esp_random());

  printBanner();
  Serial.println("Tip: hold reset/user button on GPIO11 for 5 seconds to clear provisioning");

  releaseUnusedManagedPins();
  loadConfigs();
  applySavedConfigs();

  WiFi.onEvent(WiFiEvent);
  startBleProvisioning();

  if (loadSavedWiFi()) {
    Serial.println("Saved WiFi config detected, trying auto-connect");
    connectToWiFi(savedSsid, savedPassword, false);
  } else {
    Serial.println("No WiFi config, entering BLE provisioning mode");
  }
}

void loop() {
  updateMultiplexedCombos();
  if (webServerStarted && WiFi.status() == WL_CONNECTED) {
    server.handleClient();
  }
  updateMultiplexedCombos();
  handleResetButton();
  handleProvisioningTimeout();
  flushBlePayload();
  yield();
}
