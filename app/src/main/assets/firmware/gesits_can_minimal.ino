/*
  Gesits CAN Minimalist (CAN Reader + ACK + MID Emulator + CAN Tool + BLE)
  Board: DOIT ESP32 DEVKIT V1 / ESP32 Dev Module (Arduino ESP32 core 2.x / 3.x)
  Partition Scheme: Minimal SPIFFS (1.9MB APP with OTA / 190KB SPIFFS)
  Library eksternal: TIDAK ADA (100% bawaan ESP32 Arduino Core).

  Hardware:
    ESP32       <->  SN65HVD230 / TJA1050 (3.3V-safe)  <->  CAN Bus
      GPIO21 (TX) -> CTX
      GPIO22 (RX) -> CRX
      3.3V        -> VCC
      GND         -> GND

  Web Dashboard (WiFi AP):
    SSID : "Gesits CAN" | Pass : "12345678"
    URL  : http://192.168.4.1/         (Monitor CAN live + decode + ACK + MID Emulator)
    URL  : http://192.168.4.1/cantool  (Auto-baud, Monitor per-ID/stream, TX manual, RTR scan)

  BLE (opsional ke aplikasi Android/PWA, matikan dengan ENABLE_BLE 0):
    Nama : "Gesits-ESP3-REMOTE"
    UART : 6e400001-b5a3-f393-e0a9-e50e24dcca9e
*/

#include <WiFi.h>
#include <esp_wifi.h>
#include <esp_system.h>
#include <Preferences.h>
#include <Update.h>
#include "driver/twai.h"

#define FW_VERSION "1.0.0-minimal"
#define ENABLE_BLE 1   // 1 = aktifkan BLE ke app Android, 0 = WiFi web saja (lebih ringan)

#if ENABLE_BLE
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#endif

// ---------------- CONFIG ----------------
#define CAN_TX_PIN GPIO_NUM_21
#define CAN_RX_PIN GPIO_NUM_22

const char* AP_SSID     = "Gesits CAN";
const char* AP_PASSWORD = "12345678";

#if ENABLE_BLE
#define BLE_DEVICE_NAME         "Gesits-ESP3-REMOTE"
#define BLE_SERVICE_UUID        "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_CHARACTERISTIC_UUID "6e400003-b5a3-f393-e0a9-e50e24dcca9e" // TX notify
#define BLE_CHAR_RX_UUID        "6e400002-b5a3-f393-e0a9-e50e24dcca9e" // RX write
#endif

// ---------------- STATE ----------------
WiFiServer server(80);
WiFiClient sseClient;
bool sseClientActive = false;

uint32_t canBaud = 250000;
volatile bool listenOnlyMode = true; // aman saat boot (pasif, tidak ACK)
uint32_t frameCount = 0;
Preferences canPrefs;

enum OpMode { OP_OFF = 0, OP_MID = 1, OP_CONTROLLER_SIM = 2, OP_BATTERY_SIM = 3 };
volatile int opMode = OP_OFF;

struct Telemetry {
  float soc = -1, soh = -1, tbat = -1, vbat = -1, ibat = -999, cbat = -1;
  float tinv = -1, tmot = -1, speedKmh = -1, throttlePct = -1, torqueRaw = 0;
  int   mode = -1;      // 0=Eco, 1=Urban, 2=Sport
  int   park = -1;      // 1=Park, 0=Drive
  int   cruise = -1;    // 1=ON, 0=OFF
  int   rem = -1;       // 1=ON, 0=OFF
  int   sidestand = -1; // 1=turun, 0=naik
} telem;

struct SimBatteryInput {
  int soc = 80, soh = 95, tbat = 28, vbat = 72, ibat = 0, cycle = 50;
} simBatt;

struct SimControllerInput {
  float speedKmh = 0;
  int throttlePct = 0, mode = 0, tinv = 25, tmot = 25;
  bool cruise = false, rem = false, park = true, sidestandDown = true;
} simCtrl;

// ---------------- HELPER ----------------
String bytesToHex(const uint8_t* data, uint8_t len) {
  const char* hex = "0123456789ABCDEF";
  String out;
  out.reserve(len * 3);
  for (uint8_t i = 0; i < len; i++) {
    if (i) out += ' ';
    out += hex[(data[i] >> 4) & 0x0F];
    out += hex[data[i] & 0x0F];
  }
  return out;
}

String extractQuery(const String& reqLine) {
  int q = reqLine.indexOf('?');
  int sp = reqLine.indexOf(' ', q);
  return (q >= 0 && sp > q) ? reqLine.substring(q + 1, sp) : "";
}

bool getQueryParamStr(const String& query, const char* key, String& out) {
  String k = String(key) + "=";
  int p = 0;
  while (true) {
    p = query.indexOf(k, p);
    if (p < 0) return false;
    if (p == 0 || query[p - 1] == '&') break;
    p++;
  }
  int e = query.indexOf('&', p);
  if (e < 0) e = query.length();
  out = query.substring(p + k.length(), e);
  return true;
}

int getQueryParamInt(const String& query, const char* key, int def) {
  String s;
  return getQueryParamStr(query, key, s) ? s.toInt() : def;
}

float getQueryParamFloat(const String& query, const char* key, float def) {
  String s;
  return getQueryParamStr(query, key, s) ? s.toFloat() : def;
}

#define CT_CASE(B, M) case B: { twai_timing_config_t x = M; t = x; return true; }
bool canTimingFor(uint32_t baud, twai_timing_config_t& t) {
  switch (baud) {
    CT_CASE(50000,   TWAI_TIMING_CONFIG_50KBITS())
    CT_CASE(100000,  TWAI_TIMING_CONFIG_100KBITS())
    CT_CASE(125000,  TWAI_TIMING_CONFIG_125KBITS())
    CT_CASE(250000,  TWAI_TIMING_CONFIG_250KBITS())
    CT_CASE(500000,  TWAI_TIMING_CONFIG_500KBITS())
    CT_CASE(800000,  TWAI_TIMING_CONFIG_800KBITS())
    CT_CASE(1000000, TWAI_TIMING_CONFIG_1MBITS())
    default: return false;
  }
}

void reinitTWAI(bool listenOnly) {
  twai_stop();
  twai_driver_uninstall();
  delay(30);

  twai_general_config_t g_config = TWAI_GENERAL_CONFIG_DEFAULT(
      CAN_TX_PIN, CAN_RX_PIN, listenOnly ? TWAI_MODE_LISTEN_ONLY : TWAI_MODE_NORMAL);
  g_config.rx_queue_len = 64;
  g_config.tx_queue_len = 16;

  twai_timing_config_t t_config = TWAI_TIMING_CONFIG_250KBITS();
  canTimingFor(canBaud, t_config);
  twai_filter_config_t f_config = TWAI_FILTER_CONFIG_ACCEPT_ALL();

  if (twai_driver_install(&g_config, &t_config, &f_config) != ESP_OK) {
    Serial.println("ERROR: TWAI install gagal");
    return;
  }
  if (twai_start() != ESP_OK) {
    Serial.println("ERROR: TWAI start gagal");
    return;
  }
  listenOnlyMode = listenOnly;
  Serial.printf("CAN started: %lu bps | mode=%s\n",
                (unsigned long)canBaud,
                listenOnly ? "LISTEN_ONLY (pasif)" : "NORMAL (ACK + TX)");
}

void setupCAN() {
  canPrefs.begin("cantool", true);
  canBaud = canPrefs.getUInt("baud", 250000);
  canPrefs.end();
  twai_timing_config_t chk;
  if (!canTimingFor(canBaud, chk)) canBaud = 250000;
  reinitTWAI(true);
}

// ---------------- CAN TOOL (Ring Buffer + Auto-Baud + Scan + TX) ----------------
#define CT_RING 128
uint32_t ctId[CT_RING];
uint32_t ctTime[CT_RING];
uint8_t  ctFlags[CT_RING]; // bit0=ext, bit1=rtr, bit2=tx
uint8_t  ctDlc[CT_RING];
uint8_t  ctData[CT_RING][8];
uint32_t ctSeq = 0;
uint32_t ctFirst = 0;
uint32_t ctRxSinceSwitch = 0;
bool     ctFilterOn = false;
uint32_t ctFilterId = 0;
uint32_t ctTxOk = 0, ctTxFail = 0;

void ctPush(const twai_message_t& m, bool isTx) {
  if (ctFilterOn && m.identifier != ctFilterId) return;
  uint8_t i = ctSeq % CT_RING;
  ctId[i] = m.identifier;
  ctTime[i] = millis();
  ctFlags[i] = (m.extd ? 1 : 0) | (m.rtr ? 2 : 0) | (isTx ? 4 : 0);
  uint8_t n = m.data_length_code > 8 ? 8 : m.data_length_code;
  ctDlc[i] = n;
  memset(ctData[i], 0, 8);
  if (!m.rtr && n > 0) memcpy(ctData[i], m.data, n);
  ctSeq++;
}

// RTR Scan
#define CT_SCAN_MAX 256
bool     scRun = false, scExt = true;
uint32_t scStart = 0, scEnd = 0, scCur = 0, scLastSend = 0, scGapMs = 60, scWinEnd = 0, scSent = 0;
uint8_t  scDlc = 0;
uint16_t scRepl[CT_SCAN_MAX], scOther[CT_SCAN_MAX];
uint8_t  scLen[CT_SCAN_MAX];
uint8_t  scData[CT_SCAN_MAX][8];

void canToolLogRx(const twai_message_t& m) {
  ctRxSinceSwitch++;
  ctPush(m, false);
  if (!m.rtr && (scRun || scSent) && (bool)m.extd == scExt &&
      m.identifier >= scStart && m.identifier <= scEnd) {
    uint32_t ix = m.identifier - scStart;
    if (scRun && m.identifier == scCur - 1 && millis() < scWinEnd) {
      if (scRepl[ix] < 65535) scRepl[ix]++;
    } else {
      if (scOther[ix] < 65535) scOther[ix]++;
    }
    uint8_t n = m.data_length_code > 8 ? 8 : m.data_length_code;
    scLen[ix] = n;
    memcpy(scData[ix], m.data, n);
  }
}

// Manual / Repeat TX
bool ctRepOn = false, ctRepExt = true, ctRepRtr = false;
uint32_t ctRepId = 0, ctRepMs = 1000, ctRepLast = 0, ctRepSent = 0;
uint8_t ctRepLen = 0, ctRepData[8];

bool ctSend(uint32_t id, bool ext, bool rtr, const uint8_t* d, uint8_t len) {
  if (listenOnlyMode || len > 8) return false;
  if (ext ? (id > 0x1FFFFFFFUL) : (id > 0x7FFUL)) return false;
  twai_message_t m = {};
  m.identifier = id;
  m.extd = ext ? 1 : 0;
  m.rtr = rtr ? 1 : 0;
  m.data_length_code = len;
  if (!rtr && len > 0) memcpy(m.data, d, len);
  if (twai_transmit(&m, pdMS_TO_TICKS(50)) == ESP_OK) {
    ctTxOk++;
    ctPush(m, true);
    return true;
  }
  ctTxFail++;
  return false;
}

uint8_t ctParseHex(const String& s, uint8_t* out) {
  uint8_t n = 0;
  int hi = -1;
  for (unsigned i = 0; i < s.length() && n < 8; i++) {
    char c = s[i];
    int v;
    if (c >= '0' && c <= '9') v = c - '0';
    else if (c >= 'a' && c <= 'f') v = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') v = c - 'A' + 10;
    else continue;
    if (hi < 0) hi = v;
    else { out[n++] = (uint8_t)((hi << 4) | v); hi = -1; }
  }
  return n;
}

// Auto-detect bitrate (pasif)
#define CT_NRATES 7
const uint32_t CT_RATES[CT_NRATES] = {1000000, 800000, 500000, 250000, 125000, 100000, 50000};
bool     abRunning = false;
int8_t   abIdx = 0;
uint32_t abStartMs = 0, abPrevBaud = 250000, abBest = 0;
uint32_t abFrames[CT_NRATES], abErrs[CT_NRATES];

void autoBaudStart() {
  abRunning = true;
  abIdx = 0;
  abBest = 0;
  abPrevBaud = canBaud;
  memset(abFrames, 0, sizeof(abFrames));
  memset(abErrs, 0, sizeof(abErrs));
  ctRepOn = false;
  canBaud = CT_RATES[0];
  reinitTWAI(true);
  ctRxSinceSwitch = 0;
  abStartMs = millis();
}

void autoBaudService() {
  if (!abRunning || millis() - abStartMs < 1500) return;
  twai_status_info_t si;
  uint32_t berr = 0;
  if (twai_get_status_info(&si) == ESP_OK) berr = si.bus_error_count;
  abFrames[abIdx] = ctRxSinceSwitch;
  abErrs[abIdx] = berr;
  abIdx++;

  if (abIdx >= CT_NRATES) {
    int best = -1;
    for (int i = 0; i < CT_NRATES; i++) {
      if (abFrames[i] == 0) continue;
      if (best < 0 || abErrs[i] < abErrs[best] ||
          (abErrs[i] == abErrs[best] && abFrames[i] > abFrames[best])) {
        best = i;
      }
    }
    if (best >= 0) {
      abBest = CT_RATES[best];
      canBaud = abBest;
      canPrefs.begin("cantool", false);
      canPrefs.putUInt("baud", canBaud);
      canPrefs.end();
    } else {
      canBaud = abPrevBaud;
    }
    reinitTWAI(true);
    abRunning = false;
    return;
  }

  canBaud = CT_RATES[abIdx];
  reinitTWAI(true);
  ctRxSinceSwitch = 0;
  abStartMs = millis();
}

void scanService() {
  if (!scRun) return;
  if (listenOnlyMode || abRunning) { scRun = false; return; }
  uint32_t now = millis();
  if (now - scLastSend < scGapMs) return;
  if (scCur > scEnd) { scRun = false; return; }
  twai_message_t m = {};
  m.identifier = scCur;
  m.extd = scExt ? 1 : 0;
  m.rtr = 1;
  m.data_length_code = scDlc;
  if (twai_transmit(&m, pdMS_TO_TICKS(20)) == ESP_OK) ctTxOk++; else ctTxFail++;
  scSent++;
  scLastSend = now;
  scWinEnd = now + scGapMs;
  scCur++;
}

void canToolService() {
  autoBaudService();
  scanService();
  uint32_t now = millis();
  if (ctRepOn && !listenOnlyMode && !abRunning && now - ctRepLast >= ctRepMs) {
    ctRepLast = now;
    if (ctSend(ctRepId, ctRepExt, ctRepRtr, ctRepData, ctRepLen)) ctRepSent++;
  }
  static uint32_t lastChk = 0;
  if (now - lastChk > 1000) {
    lastChk = now;
    twai_status_info_t si;
    if (!abRunning && twai_get_status_info(&si) == ESP_OK && si.state == TWAI_STATE_BUS_OFF) {
      Serial.println("CAN: BUS_OFF -> reinit");
      reinitTWAI(listenOnlyMode);
    }
  }
}

// ---------------- MID HEARTBEAT EMULATOR ----------------
struct MidHeartbeatStep {
  uint32_t offsetMs;
  uint32_t id;
  bool     rtr;
  uint8_t  dataByte;
};

const MidHeartbeatStep MID_HEARTBEAT_SEQ[9] = {
  {   0, 0x10261051, true,  0x00 },
  {  15, 0x10261151, true,  0x00 },
  {  30, 0x10261100, true,  0x00 },
  {  45, 0x10261101, true,  0x00 },
  {  60, 0x10261102, true,  0x00 },
  {  75, 0x10261200, true,  0x00 },
  {  90, 0x10261201, true,  0x00 },
  { 105, 0x10261202, true,  0x00 },
  { 120, 0x10261030, false, 0x00 },
};

uint32_t midHeartbeatCycleStart = 0;
uint8_t  midHeartbeatStep = 9;
#define MID_HEARTBEAT_PERIOD_MS 1000UL

bool sendCanFrameExt(uint32_t id, bool rtr, const uint8_t* data, uint8_t len) {
  if (listenOnlyMode) return false;
  twai_message_t m = {};
  m.identifier = id;
  m.extd = 1;
  m.rtr = rtr ? 1 : 0;
  m.data_length_code = rtr ? 0 : len;
  if (!rtr && data && len > 0) memcpy(m.data, data, len);
  bool ok = (twai_transmit(&m, pdMS_TO_TICKS(50)) == ESP_OK);
  if (ok) ctPush(m, true);
  return ok;
}

void serviceMidHeartbeat() {
  if (opMode != OP_MID || listenOnlyMode) return;
  uint32_t now = millis();
  if (midHeartbeatStep >= 9) {
    if (midHeartbeatCycleStart == 0 || now - midHeartbeatCycleStart >= MID_HEARTBEAT_PERIOD_MS) {
      midHeartbeatCycleStart = now;
      midHeartbeatStep = 0;
    } else return;
  }
  const MidHeartbeatStep& s = MID_HEARTBEAT_SEQ[midHeartbeatStep];
  if (now - midHeartbeatCycleStart >= s.offsetMs) {
    sendCanFrameExt(s.id, s.rtr, &s.dataByte, s.rtr ? 0 : 1);
    midHeartbeatStep++;
  }
}

// ---------------- BENCH SIMULATOR (OPSIONAL) ----------------
uint32_t controllerSimLastMs = 0;
bool controllerSimToggle = false;

void serviceControllerSim() {
  if (opMode != OP_CONTROLLER_SIM || listenOnlyMode) return;
  if (millis() - controllerSimLastMs < 20) return;
  controllerSimLastMs = millis();

  if (!controllerSimToggle) {
    uint8_t modeBits = (simCtrl.mode == 2) ? 0xC0 : (simCtrl.mode == 1 ? 0x40 : 0x00);
    uint8_t b1 = modeBits
               | (simCtrl.cruise ? 0x08 : 0x00)
               | (simCtrl.rem ? 0x04 : 0x00)
               | (simCtrl.sidestandDown ? 0x10 : 0x00)
               | (simCtrl.park ? 0x01 : 0x00);
    uint16_t speedRaw = (uint16_t)constrain((long)round(simCtrl.speedKmh * 12.0f), 0, 65535);
    uint8_t d[8] = {0x00, b1, (uint8_t)(speedRaw & 0xFF), (uint8_t)(speedRaw >> 8),
                    (uint8_t)constrain(simCtrl.tinv, 0, 255),
                    (uint8_t)constrain(simCtrl.tmot, 0, 255), 0x00, 0x00};
    sendCanFrameExt(0x10261022, false, d, 8);
  } else {
    uint8_t d[8] = {0x00, 0x00, (uint8_t)constrain(simCtrl.throttlePct, 0, 100), 0, 0, 0, 0, 0};
    sendCanFrameExt(0x10261023, false, d, 8);
  }
  controllerSimToggle = !controllerSimToggle;
}

uint32_t batterySimLastMs = 0;
uint8_t  batterySimStep = 0;

void serviceBatterySim() {
  if (opMode != OP_BATTERY_SIM || listenOnlyMode) return;
  if (millis() - batterySimLastMs < 250) return;
  batterySimLastMs = millis();

  switch (batterySimStep) {
    case 0: {
      uint8_t d[8] = {0x00, (uint8_t)constrain(simBatt.soc, 0, 100),
                      (uint8_t)constrain(simBatt.soh, 0, 100),
                      (uint8_t)constrain(simBatt.tbat, 0, 255),
                      (uint8_t)constrain(simBatt.vbat, 0, 255),
                      (uint8_t)(int8_t)constrain(simBatt.ibat, -128, 127), 0x00, 0x00};
      sendCanFrameExt(0x10261200, false, d, 8);
      break;
    }
    case 1: {
      uint8_t d[8] = {0x00, 0x86, 0x40, 0x06, 0x17, 0x47, 0x4E, 0x57};
      sendCanFrameExt(0x10261201, false, d, 8);
      break;
    }
    case 2: {
      uint8_t d[8] = {0x00, 0x14, 0x04, 0x1E, 0x00, 0x00,
                      (uint8_t)constrain(simBatt.cycle, 0, 255), 0x00};
      sendCanFrameExt(0x10261202, false, d, 8);
      break;
    }
    case 3: {
      uint8_t d[8] = {0x00, 0x00, 0x00, 0x04, 0x2B, 0x01, 0xD2, 0x00};
      sendCanFrameExt(0x10261203, false, d, 8);
      break;
    }
  }
  batterySimStep = (batterySimStep + 1) % 4;
}

// ---------------- BLE BRIDGE (OPSIONAL) ----------------
enum CanCmd : uint8_t {
  CANCMD_NONE = 0, CANCMD_NORMAL, CANCMD_LISTEN,
  CANCMD_MID_ON, CANCMD_MID_OFF, CANCMD_STATUS,
  CANCMD_SIM_CTRL, CANCMD_SIM_BATT, CANCMD_SIM_OFF
};
#define CANCMD_QUEUE_SIZE 8
volatile uint8_t canCmdQueue[CANCMD_QUEUE_SIZE];
volatile uint8_t canCmdHead = 0, canCmdTail = 0;
char pendingSimQuery[128];
volatile uint8_t pendingSimQueryType = 0;

void enqueueCanCmd(uint8_t c) {
  uint8_t next = (canCmdHead + 1) % CANCMD_QUEUE_SIZE;
  if (next == canCmdTail) return;
  canCmdQueue[canCmdHead] = c;
  canCmdHead = next;
}

#if ENABLE_BLE
BLEServer* bleServer = nullptr;
BLECharacteristic* bleTxChar = nullptr;
BLECharacteristic* bleRxChar = nullptr;
bool bleClientConnected = false;

void bleSendLine(const String& line) {
  if (!bleClientConnected || !bleTxChar) return;
  String out = line + "\r\n";
  bleTxChar->setValue((uint8_t*)out.c_str(), out.length());
  bleTxChar->notify();
}

void reportCanStatusBle() {
  bleSendLine(String("FWVER=")   + FW_VERSION);
  bleSendLine(String("CANMODE=") + (listenOnlyMode ? "0" : "1"));
  bleSendLine(String("MIDEMU=")  + (opMode == OP_MID ? "1" : "0"));
  bleSendLine(String("SIMMODE=") + String((int)opMode));
}

class BleRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String rawValue = c->getValue().c_str();
    rawValue.trim();
    String value = rawValue;
    value.toUpperCase();
    if      (value == "CAN_MODE:NORMAL") enqueueCanCmd(CANCMD_NORMAL);
    else if (value == "CAN_MODE:LISTEN") enqueueCanCmd(CANCMD_LISTEN);
    else if (value == "MID:ON")          enqueueCanCmd(CANCMD_MID_ON);
    else if (value == "MID:OFF")         enqueueCanCmd(CANCMD_MID_OFF);
    else if (value == "CAN_STATUS?")     enqueueCanCmd(CANCMD_STATUS);
    else if (value == "SIM:CONTROLLER")  enqueueCanCmd(CANCMD_SIM_CTRL);
    else if (value == "SIM:BATTERY")     enqueueCanCmd(CANCMD_SIM_BATT);
    else if (value == "SIM:OFF")         enqueueCanCmd(CANCMD_SIM_OFF);
    else if (value.startsWith("SIMB:") || value.startsWith("SIMC:")) {
      if (pendingSimQueryType == 0) {
        String q = rawValue.substring(5);
        q.trim();
        q.toLowerCase();
        q.toCharArray(pendingSimQuery, sizeof(pendingSimQuery));
        pendingSimQueryType = value.startsWith("SIMB:") ? 1 : 2;
      }
    }
  }
};

class BleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* s) override { bleClientConnected = true; }
  void onDisconnect(BLEServer* s) override {
    bleClientConnected = false;
    delay(200);
    BLEDevice::startAdvertising();
  }
};

void setupBLE() {
  BLEDevice::init(BLE_DEVICE_NAME);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new BleServerCallbacks());
  BLEService* service = bleServer->createService(BLE_SERVICE_UUID);
  bleTxChar = service->createCharacteristic(
    BLE_CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ
  );
  bleTxChar->addDescriptor(new BLE2902());
  bleRxChar = service->createCharacteristic(
    BLE_CHAR_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  bleRxChar->setCallbacks(new BleRxCallbacks());
  service->start();

  BLEAdvertising* adv = BLEDevice::getAdvertising();
  BLEAdvertisementData advData;
  advData.setFlags(0x06);
  advData.setCompleteServices(BLEUUID(BLE_SERVICE_UUID));
  adv->setAdvertisementData(advData);
  BLEAdvertisementData scanResp;
  scanResp.setName(BLE_DEVICE_NAME);
  adv->setScanResponseData(scanResp);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("BLE aktif: " BLE_DEVICE_NAME);
}
#else
void bleSendLine(const String&) {}
void reportCanStatusBle() {}
void setupBLE() {}
#endif

void processCanCmdQueue() {
  if (pendingSimQueryType != 0) {
    uint8_t t = pendingSimQueryType;
    String q = String(pendingSimQuery);
    if (t == 1) {
      simBatt.soc   = constrain(getQueryParamInt(q, "soc",   simBatt.soc), 0, 100);
      simBatt.soh   = constrain(getQueryParamInt(q, "soh",   simBatt.soh), 0, 100);
      simBatt.tbat  = constrain(getQueryParamInt(q, "tbat",  simBatt.tbat), -40, 120);
      simBatt.vbat  = constrain(getQueryParamInt(q, "vbat",  simBatt.vbat), 0, 200);
      simBatt.ibat  = constrain(getQueryParamInt(q, "ibat",  simBatt.ibat), -300, 300);
      simBatt.cycle = constrain(getQueryParamInt(q, "cycle", simBatt.cycle), 0, 65535);
    } else {
      simCtrl.speedKmh      = constrain(getQueryParamFloat(q, "speed", simCtrl.speedKmh), 0.0f, 200.0f);
      simCtrl.throttlePct   = constrain(getQueryParamInt(q, "throttle", simCtrl.throttlePct), 0, 100);
      simCtrl.mode          = constrain(getQueryParamInt(q, "mode", simCtrl.mode), 0, 2);
      simCtrl.cruise        = getQueryParamInt(q, "cruise", simCtrl.cruise ? 1 : 0) != 0;
      simCtrl.rem           = getQueryParamInt(q, "rem", simCtrl.rem ? 1 : 0) != 0;
      simCtrl.park          = getQueryParamInt(q, "park", simCtrl.park ? 1 : 0) != 0;
      simCtrl.sidestandDown = getQueryParamInt(q, "sidestand", simCtrl.sidestandDown ? 1 : 0) != 0;
      simCtrl.tinv          = constrain(getQueryParamInt(q, "tinv", simCtrl.tinv), -40, 200);
      simCtrl.tmot          = constrain(getQueryParamInt(q, "tmot", simCtrl.tmot), -40, 200);
    }
    pendingSimQueryType = 0;
    bleSendLine(String("SIMSET=") + String((int)t));
  }

  while (canCmdTail != canCmdHead) {
    uint8_t c = canCmdQueue[canCmdTail];
    canCmdTail = (canCmdTail + 1) % CANCMD_QUEUE_SIZE;
    switch (c) {
      case CANCMD_NORMAL:
        if (listenOnlyMode) reinitTWAI(false);
        break;
      case CANCMD_LISTEN:
        opMode = OP_OFF;
        if (!listenOnlyMode) reinitTWAI(true);
        break;
      case CANCMD_MID_ON:
        if (listenOnlyMode) reinitTWAI(false);
        opMode = OP_MID;
        midHeartbeatStep = 9;
        midHeartbeatCycleStart = 0;
        break;
      case CANCMD_MID_OFF:
        if (opMode == OP_MID) opMode = OP_OFF;
        break;
      case CANCMD_SIM_CTRL:
        if (listenOnlyMode) reinitTWAI(false);
        opMode = OP_CONTROLLER_SIM;
        controllerSimToggle = false;
        break;
      case CANCMD_SIM_BATT:
        if (listenOnlyMode) reinitTWAI(false);
        opMode = OP_BATTERY_SIM;
        batterySimStep = 0;
        break;
      case CANCMD_SIM_OFF:
        if (opMode == OP_CONTROLLER_SIM || opMode == OP_BATTERY_SIM) opMode = OP_OFF;
        break;
      default: break;
    }
    reportCanStatusBle();
  }
}

// ---------------- JSON HELPERS ----------------
String ctStatusJson() {
  twai_status_info_t si;
  bool ok = (twai_get_status_info(&si) == ESP_OK);
  const char* st = "?";
  if (ok) {
    switch (si.state) {
      case TWAI_STATE_STOPPED:    st = "STOPPED"; break;
      case TWAI_STATE_RUNNING:    st = "RUNNING"; break;
      case TWAI_STATE_BUS_OFF:    st = "BUS_OFF"; break;
      case TWAI_STATE_RECOVERING: st = "RECOVERING"; break;
      default: break;
    }
  }
  String j = "{\"fw\":\"" FW_VERSION "\",\"baud\":" + String(canBaud);
  j += String(",\"listen\":") + (listenOnlyMode ? "true" : "false");
  j += ",\"opMode\":" + String((int)opMode);
  j += String(",\"state\":\"") + st + "\"";
  if (ok) {
    j += ",\"tec\":" + String(si.tx_error_counter);
    j += ",\"rec\":" + String(si.rx_error_counter);
    j += ",\"berr\":" + String(si.bus_error_count);
    j += ",\"miss\":" + String(si.rx_missed_count);
  }
  j += ",\"seq\":" + String(ctSeq);
  j += ",\"heap\":" + String((uint32_t)ESP.getFreeHeap());
  j += ",\"freeSketch\":" + String((uint32_t)ESP.getFreeSketchSpace());
  j += ",\"txok\":" + String(ctTxOk) + ",\"txfail\":" + String(ctTxFail);
  j += String(",\"rep\":") + (ctRepOn ? "true" : "false") + ",\"repsent\":" + String(ctRepSent);
  j += String(",\"ab\":{\"run\":") + (abRunning ? "true" : "false");
  j += ",\"idx\":" + String((int)abIdx) + ",\"best\":" + String(abBest) + ",\"res\":[";
  for (int i = 0; i < CT_NRATES; i++) {
    if (i) j += ",";
    j += "[" + String(CT_RATES[i]) + "," + String(abFrames[i]) + "," + String(abErrs[i]) + "]";
  }
  j += "]}}";
  return j;
}

String ctRxJson(uint32_t since) {
  uint32_t start = since;
  uint32_t floorSeq = (ctSeq > CT_RING) ? (ctSeq - CT_RING) : 0;
  if (start > ctSeq || start < floorSeq) start = floorSeq;
  if (start < ctFirst) start = ctFirst;
  String s;
  s.reserve(64 + (ctSeq - start) * 52);
  s = "{\"seq\":" + String(ctSeq) + ",\"f\":[";
  for (uint32_t q = start; q < ctSeq; q++) {
    uint8_t i = q % CT_RING;
    if (q != start) s += ",";
    s += "[" + String(ctTime[i]) + ",\"" + String(ctId[i], HEX) + "\"," + String(ctFlags[i]) +
         "," + String(ctDlc[i]) + ",\"" + bytesToHex(ctData[i], ctDlc[i]) + "\"]";
  }
  s += "]}";
  return s;
}

String scanJson() {
  String j = String("{\"run\":") + (scRun ? "true" : "false");
  j += ",\"from\":\"" + String(scStart, HEX) + "\",\"to\":\"" + String(scEnd, HEX) + "\"";
  j += ",\"cur\":\"" + String(scCur, HEX) + "\",\"sent\":" + String(scSent) + ",\"hits\":[";
  bool first = true;
  if (scEnd >= scStart) {
    for (uint32_t ix = 0; ix <= scEnd - scStart && ix < CT_SCAN_MAX; ix++) {
      if (!scRepl[ix] && !scOther[ix]) continue;
      if (!first) j += ",";
      first = false;
      j += "[\"" + String(scStart + ix, HEX) + "\"," + String(scRepl[ix]) + "," + String(scOther[ix]) +
           "," + String(scLen[ix]) + ",\"" + bytesToHex(scData[ix], scLen[ix]) + "\"]";
    }
  }
  j += "]}";
  return j;
}

String frameToJson(const twai_message_t& msg) {
  char idbuf[10];
  sprintf(idbuf, "%X", msg.identifier);
  String json = "{\"id\":\"";
  json += idbuf;
  json += "\",\"ext\":"; json += msg.extd ? "1" : "0";
  json += ",\"dlc\":"; json += msg.data_length_code;
  json += ",\"rtr\":"; json += msg.rtr ? "1" : "0";
  json += ",\"data\":[";
  for (int i = 0; i < msg.data_length_code; i++) {
    if (i) json += ",";
    json += msg.data[i];
  }
  json += "],\"t\":"; json += millis(); json += "}";
  return json;
}

// ---------------- WEB PAGES ----------------
const char INDEX_HTML[] PROGMEM =   "<!DOCTYPE html>\n"
  "<html lang=\"id\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
  "<title>Gesits CAN Minimal</title>\n"
  "<style>\n"
  ":root{--bg:#0e1116;--p:#161b22;--b:#2a313c;--t:#e6edf3;--d:#8b949e;--a:#5ee6a0;--w:#ff7b72}\n"
  "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:13px system-ui,sans-serif;padding:12px}\n"
  "h1{font-size:17px;margin:0 0 4px}.sub{color:var(--d);font-size:12px;margin-bottom:12px}\n"
  ".box{background:var(--p);border:1px solid var(--b);border-radius:10px;padding:12px;margin-bottom:12px}\n"
  ".row{display:flex;gap:8px;flex-wrap:wrap;align-items:center}\n"
  "button{background:var(--a);color:#0e1116;border:none;border-radius:8px;padding:8px 12px;font-weight:700;cursor:pointer}\n"
  "button.sec{background:transparent;color:var(--d);border:1px solid var(--b)}\n"
  "button.sec.on{background:var(--a);color:#0e1116;border-color:var(--a)}\n"
  "button.warn{background:var(--w);color:#0e1116}\n"
  ".grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px}\n"
  "@media(max-width:460px){.grid{grid-template-columns:1fr}}\n"
  ".kv{display:flex;justify-content:space-between;padding:3px 0;font-size:12px;border-bottom:1px dashed #222933}\n"
  ".kv span:first-child{color:var(--d)}.kv span:last-child{font-family:monospace;font-weight:600}\n"
  "table{width:100%;border-collapse:collapse;font:12px monospace;background:var(--p);border-radius:10px;overflow:hidden}\n"
  "th,td{padding:6px;border-bottom:1px solid var(--b);text-align:left;white-space:nowrap}\n"
  "th{color:var(--d);font-size:11px;text-transform:uppercase}.id{color:var(--a);font-weight:700}\n"
  ".dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--w);margin-right:6px}.dot.live{background:var(--a)}\n"
  "</style></head><body>\n"
  "<h1>Gesits CAN Minimal</h1>\n"
  "<div class=\"sub\">CAN Reader + ACK (Normal Mode) + Emulator MID &middot; FW <b id=\"fwTxt\">-</b> &middot; <a href=\"/cantool\" style=\"color:#58a6ff\">CAN Tool</a> &middot; <a href=\"/update\" style=\"color:#5ee6a0\">Update OTA</a></div>\n"
  "\n"
  "<div class=\"box\">\n"
  "  <div class=\"row\" style=\"justify-content:space-between\">\n"
  "    <div>\n"
  "      <div>Mode CAN: <b id=\"modeTxt\">-</b> &middot; Bitrate: <b id=\"baudTxt\">-</b></div>\n"
  "      <div style=\"font-size:11px;color:var(--d)\" id=\"modeSub\">Memuat...</div>\n"
  "    </div>\n"
  "    <div class=\"row\">\n"
  "      <button id=\"btnListen\" class=\"sec\" onclick=\"setMode('listen')\">LISTEN (Pasif)</button>\n"
  "      <button id=\"btnNormal\" class=\"sec\" onclick=\"setMode('normal')\">NORMAL (ACK + TX)</button>\n"
  "    </div>\n"
  "  </div>\n"
  "  <div class=\"row\" style=\"margin-top:10px;padding-top:10px;border-top:1px dashed var(--b)\">\n"
  "    <span style=\"font-size:12px;color:var(--d)\">MID Heartbeat Emulator (RTR 1s):</span>\n"
  "    <button id=\"btnMidOn\" class=\"sec\" onclick=\"setOp('/mid/on')\">MID EMULATE: ON</button>\n"
  "    <button id=\"btnMidOff\" class=\"sec\" onclick=\"setOp('/mid/off')\">MID EMULATE: OFF</button>\n"
  "    <a href=\"/cantool\"><button class=\"sec\" type=\"button\">CAN Tool</button></a>\n"
  "    <a href=\"/update\"><button class=\"sec\" type=\"button\">Update OTA</button></a>\n"
  "  </div>\n"
  "</div>\n"
  "\n"
  "<div class=\"row\" style=\"margin-bottom:10px;font-size:12px\">\n"
  "  <span><span id=\"dot\" class=\"dot\"></span><b id=\"st\">Connecting...</b></span>\n"
  "  <span>Total frame: <b id=\"tot\">0</b></span>\n"
  "  <span>ID unik: <b id=\"uniq\">0</b></span>\n"
  "  <button onclick=\"dlCsv()\">Download CSV</button>\n"
  "  <button class=\"sec\" onclick=\"clr()\">Clear</button>\n"
  "</div>\n"
  "\n"
  "<div class=\"grid\">\n"
  "  <div class=\"box\" style=\"margin:0\">\n"
  "    <b>Baterai</b> (<span id=\"bSrc\">-</span>)\n"
  "    <div class=\"kv\"><span>SOC / SOH</span><span id=\"bSoc\">-</span></div>\n"
  "    <div class=\"kv\"><span>Voltase / Arus</span><span id=\"bVI\">-</span></div>\n"
  "    <div class=\"kv\"><span>Suhu / Cycle</span><span id=\"bTC\">-</span></div>\n"
  "  </div>\n"
  "  <div class=\"box\" style=\"margin:0\">\n"
  "    <b>Motor / Kontroler</b>\n"
  "    <div class=\"kv\"><span>Speed / Mode</span><span id=\"mSpd\">-</span></div>\n"
  "    <div class=\"kv\"><span>Throttle / Torque</span><span id=\"mTrt\">-</span></div>\n"
  "    <div class=\"kv\"><span>Suhu Inv / Mot</span><span id=\"mTmp\">-</span></div>\n"
  "    <div class=\"kv\"><span>Status</span><span id=\"mFlg\">-</span></div>\n"
  "  </div>\n"
  "</div>\n"
  "\n"
  "<div style=\"overflow-x:auto\">\n"
  "  <table><thead><tr><th>ID</th><th>Ext</th><th>DLC</th><th>RTR</th><th>Data (hex)</th><th>Count</th><th>ms</th></tr></thead>\n"
  "  <tbody id=\"tb\"><tr><td colspan=\"7\" style=\"text-align:center;color:var(--d);padding:20px\">Menunggu frame CAN...</td></tr></tbody></table>\n"
  "</div>\n"
  "\n"
  "<script>\n"
  "const $=i=>document.getElementById(i), rows={}, log=[];\n"
  "let tot=0;\n"
  "function s8(v){return v>127?v-256:v;}\n"
  "function s16le(a,b){let v=a|(b<<8);return v>32767?v-65536:v;}\n"
  "\n"
  "async function refreshStatus(){\n"
  "  try{\n"
  "    const s=await (await fetch('/can/status',{cache:'no-store'})).json();\n"
  "    if(s.fw) $('fwTxt').textContent=s.fw;\n"
  "    $('baudTxt').textContent=(s.baud/1e3)+' kbps';\n"
  "    $('modeTxt').textContent=s.listen?'LISTEN_ONLY':'NORMAL (ACK)';\n"
  "    $('modeSub').textContent=s.listen?'Pasif, tidak mengirim ACK':'Aktif meng-ACK frame & siap TX';\n"
  "    $('btnListen').classList.toggle('on',s.listen);\n"
  "    $('btnNormal').classList.toggle('on',!s.listen);\n"
  "    $('btnNormal').classList.toggle('warn',!s.listen);\n"
  "    $('btnMidOn').classList.toggle('on',s.opMode===1);\n"
  "    $('btnMidOff').classList.toggle('on',s.opMode===0);\n"
  "  }catch(e){}\n"
  "}\n"
  "async function setMode(m){\n"
  "  if(m==='normal'&&!confirm('Aktifkan mode NORMAL (ESP32 ikut ACK & bisa TX)?'))return;\n"
  "  await fetch('/mode?set='+m); refreshStatus();\n"
  "}\n"
  "async function setOp(u){ await fetch(u); refreshStatus(); }\n"
  "\n"
  "function render(f){\n"
  "  let r=rows[f.id];\n"
  "  if(!r){\n"
  "    if(Object.keys(rows).length===0) $('tb').innerHTML='';\n"
  "    const tr=document.createElement('tr');\n"
  "    tr.innerHTML='<td class=\"id\"></td><td></td><td></td><td></td><td style=\"font-family:monospace\"></td><td></td><td></td>';\n"
  "    $('tb').appendChild(tr);\n"
  "    r=rows[f.id]={n:0,el:tr};\n"
  "    $('uniq').textContent=Object.keys(rows).length;\n"
  "  }\n"
  "  r.n++;\n"
  "  const hex=f.data.map(b=>b.toString(16).padStart(2,'0').toUpperCase()).join(' ');\n"
  "  const c=r.el.children;\n"
  "  c[0].textContent='0x'+f.id; c[1].textContent=f.ext?'EXT':'STD'; c[2].textContent=f.dlc;\n"
  "  c[3].textContent=f.rtr?'RTR':'-'; c[4].textContent=hex; c[5].textContent=r.n; c[6].textContent=f.t;\n"
  "  if(log.length>15000)log.shift();\n"
  "  log.push(f.t+',0x'+f.id+','+f.ext+','+f.dlc+','+f.rtr+',\"'+hex+'\"');\n"
  "}\n"
  "\n"
  "const es=new EventSource('/events');\n"
  "es.onopen=()=>{$('dot').classList.add('live');$('st').textContent='Live';};\n"
  "es.onerror=()=>{$('dot').classList.remove('live');$('st').textContent='Reconnecting...';};\n"
  "es.onmessage=e=>{\n"
  "  try{\n"
  "    const f=JSON.parse(e.data); tot++; $('tot').textContent=tot; render(f);\n"
  "    if((f.id==='10261100'||f.id==='10261200')&&!f.rtr&&f.dlc>=6){\n"
  "      $('bSrc').textContent='0x'+f.id;\n"
  "      $('bSoc').textContent=f.data[1]+'% / '+f.data[2]+'%';\n"
  "      $('bVI').textContent=f.data[4]+'V / '+s8(f.data[5])+'A';\n"
  "      $('bTC').textContent=f.data[3]+'C / '+($('bTC').textContent.split('/')[1]||'-');\n"
  "    }else if(f.id==='10261202'&&!f.rtr&&f.dlc>=7){\n"
  "      $('bTC').textContent=($('bTC').textContent.split('/')[0]||'-')+' / '+f.data[6]+'x';\n"
  "    }else if(f.id==='10261022'&&!f.rtr&&f.dlc>=8){\n"
  "      const b1=f.data[1], m=(b1&0xC0)===0xC0?'Sport':((b1&0x40)?'Urban':'Eco');\n"
  "      const spd=(f.data[2]|(f.data[3]<<8))/12;\n"
  "      $('mSpd').textContent=spd.toFixed(1)+' km/h ('+m+')';\n"
  "      $('mTmp').textContent=f.data[4]+'C / '+f.data[5]+'C';\n"
  "      $('mTrt').textContent=(($('mTrt').textContent.split('/')[0])||'-')+' / '+s16le(f.data[6],f.data[7]);\n"
  "      $('mFlg').textContent=((b1&1)?'PARK':'DRIVE')+((b1&4)?' REM':'')+((b1&8)?' CRZ':'')+((b1&16)?' STD':'');\n"
  "    }else if(f.id==='10261023'&&!f.rtr&&f.dlc>=3){\n"
  "      $('mTrt').textContent=f.data[2]+'% / '+(($('mTrt').textContent.split('/')[1])||'-');\n"
  "    }\n"
  "  }catch(err){}\n"
  "};\n"
  "function dlCsv(){\n"
  "  if(!log.length)return alert('Kosong');\n"
  "  const a=document.createElement('a');\n"
  "  a.href=URL.createObjectURL(new Blob(['ms,id,ext,dlc,rtr,data\\n'+log.join('\\n')],{type:'text/csv'}));\n"
  "  a.download='gesits_can.csv'; a.click();\n"
  "}\n"
  "function clr(){log.length=0;}\n"
  "let stBusy=0;\n"
  "async function refreshStatusSafe(){if(stBusy)return;stBusy=1;try{await refreshStatus();}finally{stBusy=0;}}\n"
  "refreshStatusSafe(); setInterval(refreshStatusSafe,2500);\n"
  "</script></body></html>";

const char CANTOOL_HTML[] PROGMEM =   "<!doctype html><html><head><meta charset=\"utf-8\">\n"
  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
  "<title>CAN Tool</title>\n"
  "<style>\n"
  ":root{--bg:#0e1116;--c:#171c24;--t:#e6edf3;--m:#8b98a8;--a:#2ea043;--w:#d29922;--e:#f85149;--b:#30363d}\n"
  "*{box-sizing:border-box}body{margin:0;padding:12px;background:var(--bg);color:var(--t);font:14px system-ui,sans-serif}\n"
  ".card{background:var(--c);border:1px solid var(--b);border-radius:12px;padding:12px;margin-bottom:12px}\n"
  "h2{margin:0 0 8px;font-size:15px}label{color:var(--m);font-size:12px;display:block;margin:6px 0 2px}\n"
  "input,select,button{font:inherit;color:var(--t);background:#0b0f14;border:1px solid var(--b);border-radius:8px;padding:8px}\n"
  "input[type=text],select{width:100%}button{background:#21262d;cursor:pointer}button.p{background:var(--a);border-color:var(--a);color:#fff}\n"
  "button.d{background:var(--e);border-color:var(--e)}.r{display:flex;gap:8px;flex-wrap:wrap;align-items:end}.r>*{flex:1;min-width:90px}\n"
  ".k{display:flex;justify-content:space-between;padding:3px 0;border-bottom:1px dashed var(--b)}.k span:first-child{color:var(--m)}\n"
  ".tag{display:inline-block;padding:2px 8px;border-radius:99px;font-size:12px;background:#21262d}.ok{color:var(--a)}.wr{color:var(--w)}.er{color:var(--e)}\n"
  "table{width:100%;border-collapse:collapse;font:12px ui-monospace,monospace}td,th{padding:4px 6px;border-bottom:1px solid var(--b);text-align:left;white-space:nowrap}\n"
  ".sc{overflow-x:auto;max-height:340px;overflow-y:auto}.tx{color:var(--w)}a{color:#58a6ff}\n"
  "</style></head><body>\n"
  "<a href=\"/\">&larr; Dashboard</a>\n"
  "<div class=\"card\"><h2>Status &amp; Konfigurasi</h2>\n"
  "<div class=\"k\"><span>Bitrate aktif</span><b id=\"sBaud\">-</b></div>\n"
  "<div class=\"k\"><span>Mode</span><b id=\"sMode\">-</b></div>\n"
  "<div class=\"k\"><span>Bus state</span><b id=\"sState\">-</b></div>\n"
  "<div class=\"k\"><span>TEC / REC / bus err / missed</span><b id=\"sErr\">-</b></div>\n"
  "<div class=\"k\"><span>TX ok / gagal</span><b id=\"sTx\">-</b></div>\n"
  "<div class=\"k\"><span>Heap bebas</span><b id=\"sHeap\">-</b></div>\n"
  "<div class=\"r\"><div><label>Bitrate</label><select id=\"baud\">\n"
  "<option value=\"1000000\">1 Mbps</option><option value=\"800000\">800 kbps</option><option value=\"500000\">500 kbps</option>\n"
  "<option value=\"250000\">250 kbps</option><option value=\"125000\">125 kbps</option><option value=\"100000\">100 kbps</option><option value=\"50000\">50 kbps</option></select></div>\n"
  "<div><label>Mode</label><select id=\"mode\"><option value=\"listen\">LISTEN (pasif)</option><option value=\"normal\">NORMAL (ACK + TX)</option></select></div></div>\n"
  "<div class=\"r\" style=\"margin-top:8px\"><button class=\"p\" onclick=\"apply()\">Terapkan</button><button onclick=\"autod()\">Auto-detect bitrate</button></div>\n"
  "<div id=\"ab\" style=\"margin-top:8px;color:var(--m);font-size:12px\"></div></div>\n"
  "\n"
  "<div class=\"card\"><h2>Monitor RX/TX</h2>\n"
  "<div class=\"r\"><div><label>Filter ID (hex, kosong = semua)</label><input type=\"text\" id=\"fid\" placeholder=\"mis. 3FE\"></div>\n"
  "<button onclick=\"setF()\">Set filter</button><button id=\"bv\" onclick=\"tv()\">Mode: per-ID</button></div>\n"
  "<div class=\"r\" style=\"margin:8px 0\"><button onclick=\"pz()\" id=\"bp\">Pause</button><button onclick=\"clr()\">Clear</button><button onclick=\"csv()\">CSV</button></div>\n"
  "<div class=\"sc\"><table><thead id=\"th\"></thead><tbody id=\"tb\"></tbody></table></div></div>\n"
  "\n"
  "<div class=\"card\"><h2>Kirim Frame</h2>\n"
  "<div class=\"r\"><div><label>ID (hex)</label><input type=\"text\" id=\"tid\" placeholder=\"3FF\"></div>\n"
  "<div><label>Data (hex, maks 8 byte)</label><input type=\"text\" id=\"tdt\" placeholder=\"09 55 AA AA 00 AA 00 00\"></div></div>\n"
  "<div class=\"r\" style=\"margin-top:6px\"><label style=\"display:flex;gap:6px;align-items:center\"><input type=\"checkbox\" id=\"text\" checked> Extended 29-bit</label>\n"
  "<label style=\"display:flex;gap:6px;align-items:center\"><input type=\"checkbox\" id=\"trtr\"> RTR</label>\n"
  "<div><label>DLC (utk RTR)</label><input type=\"text\" id=\"tdlc\" value=\"0\"></div>\n"
  "<div><label>Interval (ms)</label><input type=\"text\" id=\"tms\" value=\"1000\"></div></div>\n"
  "<div class=\"r\" style=\"margin-top:8px\"><button class=\"p\" onclick=\"send(0)\">Kirim 1x</button><button onclick=\"send(1)\">Ulangi</button><button class=\"d\" onclick=\"stopRep()\">Stop</button></div>\n"
  "<div id=\"tm\" style=\"margin-top:6px;font-size:12px;color:var(--m)\"></div></div>\n"
  "\n"
  "<div class=\"card\"><h2>RTR Scan</h2>\n"
  "<div class=\"r\"><div><label>Dari ID (hex)</label><input type=\"text\" id=\"sf\" value=\"10261200\"></div>\n"
  "<div><label>Sampai ID (hex)</label><input type=\"text\" id=\"sto\" value=\"1026123F\"></div></div>\n"
  "<div class=\"r\" style=\"margin-top:6px\"><div><label>Jeda (ms)</label><input type=\"text\" id=\"sg\" value=\"60\"></div>\n"
  "<div><label>DLC</label><input type=\"text\" id=\"sd\" value=\"0\"></div>\n"
  "<label style=\"display:flex;gap:6px;align-items:center\"><input type=\"checkbox\" id=\"sx\" checked> Extended</label></div>\n"
  "<div class=\"r\" style=\"margin-top:8px\"><button class=\"p\" onclick=\"scanGo()\">Mulai scan</button><button class=\"d\" onclick=\"scanStop()\">Stop</button></div>\n"
  "<div id=\"sm\" style=\"margin-top:6px;font-size:12px;color:var(--m)\"></div>\n"
  "<div class=\"sc\"><table><thead><tr><th>ID</th><th>balas</th><th>lain</th><th>Data terakhir</th></tr></thead><tbody id=\"stb\"></tbody></table></div></div>\n"
  "\n"
  "<script>\n"
  "const $=i=>document.getElementById(i);let since=0,rows=[],paused=false,view='id',mapId={};\n"
  "async function J(u){const r=await fetch(u,{cache:'no-store'});return r.json();}\n"
  "function fb(b){return b>=1e6?(b/1e6)+' Mbps':(b/1e3)+' kbps'}\n"
  "let b1=0,b2=0;async function st(){if(b1||document.hidden)return;b1=1;try{const s=await J('/can/status');\n"
  "$('sBaud').textContent=fb(s.baud);$('sMode').innerHTML=s.listen?'<span class=\"tag wr\">LISTEN</span>':'<span class=\"tag ok\">NORMAL</span>';\n"
  "$('sState').innerHTML='<span class=\"'+(s.state=='RUNNING'?'ok':'er')+'\">'+s.state+'</span>';\n"
  "$('sErr').textContent=s.tec+' / '+s.rec+' / '+s.berr+' / '+s.miss;$('sHeap').textContent=s.heap;$('sTx').textContent=s.txok+' / '+s.txfail+(s.rep?' (ulang: '+s.repsent+')':'');\n"
  "if(document.activeElement!==$('baud'))$('baud').value=s.baud;if(document.activeElement!==$('mode'))$('mode').value=s.listen?'listen':'normal';\n"
  "let a=s.ab,h='';if(a.run)h='Auto-detect... '+(a.idx+1)+'/'+a.res.length+' ('+fb(a.res[Math.min(a.idx,a.res.length-1)][0])+')';\n"
  "else if(a.res.some(x=>x[1]>0||x[2]>0)||a.best){h=(a.best?'<b class=\"ok\">Terdeteksi: '+fb(a.best)+'</b><br>':'<b class=\"er\">Tidak ada frame valid.</b><br>')+a.res.map(x=>fb(x[0])+': '+x[1]+' frame, '+x[2]+' err').join('<br>')}\n"
  "$('ab').innerHTML=h;}catch(e){}b1=0;}\n"
  "async function apply(){await J('/can/cfg?baud='+$('baud').value+'&mode='+$('mode').value);since=0;rows=[];mapId={};st();}\n"
  "async function autod(){if(confirm('Auto-detect ~11 detik, mode jadi LISTEN. Lanjut?')){await J('/can/autodetect');st();}}\n"
  "async function setF(){await J('/can/filter?id='+encodeURIComponent($('fid').value.trim()));since=0;rows=[];mapId={};}\n"
  "async function clr(){await J('/can/clear');rows=[];mapId={};draw();}\n"
  "function pz(){paused=!paused;$('bp').textContent=paused?'Resume':'Pause'}\n"
  "function tv(){view=view=='id'?'raw':'id';$('bv').textContent='Mode: '+(view=='id'?'per-ID':'stream');draw();}\n"
  "function hx(s){return s.replace(/(..)/g,'$1 ').trim()}\n"
  "async function rx(){if(paused||b2||document.hidden)return;b2=1;try{const d=await J('/can/rx?since='+since);if(d.seq<since){since=0;rows=[];mapId={};}since=d.seq;\n"
  "for(const f of d.f){const o={t:f[0],id:f[1].toUpperCase(),ext:f[2]&1,rtr:(f[2]>>1)&1,tx:(f[2]>>2)&1,dlc:f[3],d:hx(f[4])};rows.push(o);\n"
  "const k=o.id+(o.tx?'T':'');const m=mapId[k]||(mapId[k]={n:0,p:0,last:0});m.p=m.last?o.t-m.last:0;m.last=o.t;m.n++;m.o=o;}\n"
  "if(rows.length>600)rows=rows.slice(-600);draw();}catch(e){}b2=0;}\n"
  "function draw(){const tb=$('tb'),th=$('th');\n"
  "if(view=='id'){th.innerHTML='<tr><th>ID</th><th>n</th><th>per(ms)</th><th>DLC</th><th>Data</th></tr>';\n"
  "tb.innerHTML=Object.keys(mapId).sort().map(k=>{const m=mapId[k],o=m.o;return '<tr class=\"'+(o.tx?'tx':'')+'\"><td>'+(o.tx?'TX ':'')+o.id+(o.ext?'x':'')+(o.rtr?' RTR':'')+'</td><td>'+m.n+'</td><td>'+m.p+'</td><td>'+o.dlc+'</td><td>'+o.d+'</td></tr>'}).join('');}\n"
  "else{th.innerHTML='<tr><th>ms</th><th>ID</th><th>DLC</th><th>Data</th></tr>';\n"
  "tb.innerHTML=rows.slice(-120).reverse().map(o=>'<tr class=\"'+(o.tx?'tx':'')+'\"><td>'+o.t+'</td><td>'+(o.tx?'TX ':'')+o.id+(o.ext?'x':'')+(o.rtr?' RTR':'')+'</td><td>'+o.dlc+'</td><td>'+o.d+'</td></tr>').join('');}}\n"
  "function csv(){let c='ms,id,ext,rtr,tx,dlc,data\\n'+rows.map(o=>[o.t,o.id,o.ext,o.rtr,o.tx,o.dlc,o.d].join(',')).join('\\n');\n"
  "const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([c],{type:'text/csv'}));a.download='can_log.csv';a.click();}\n"
  "async function send(rep){const id=$('tid').value.trim();if(!id){$('tm').textContent='ID kosong';return;}\n"
  "const u='/can/tx?id='+encodeURIComponent(id)+'&ext='+($('text').checked?1:0)+'&rtr='+($('trtr').checked?1:0)+'&dlc='+encodeURIComponent($('tdlc').value)+'&data='+encodeURIComponent($('tdt').value)+(rep?'&rep=1&ms='+encodeURIComponent($('tms').value):'');\n"
  "const r=await J(u);$('tm').innerHTML=r.ok?'<span class=\"ok\">OK</span>':'<span class=\"er\">'+(r.err||'gagal')+'</span>';st();}\n"
  "async function stopRep(){await J('/can/txstop');st();}\n"
  "let sc1=0;async function scanPoll(){if(sc1)return;sc1=1;try{const r=await J('/can/scanres');\n"
  "$('sm').innerHTML=(r.run?'Scan... ID '+r.cur.toUpperCase()+' ('+r.sent+')':'Selesai: '+r.sent+' RTR, '+r.hits.length+' hidup.');\n"
  "$('stb').innerHTML=r.hits.map(h=>'<tr><td>'+h[0].toUpperCase()+'</td><td>'+h[1]+'</td><td>'+h[2]+'</td><td>'+hx(h[4])+'</td></tr>').join('');\n"
  "if(r.run)setTimeout(scanPoll,1000);}catch(e){}sc1=0;}\n"
  "async function scanGo(){const r=await J('/can/scan?from='+encodeURIComponent($('sf').value.trim())+'&to='+encodeURIComponent($('sto').value.trim())+'&ext='+($('sx').checked?1:0)+'&gap='+encodeURIComponent($('sg').value)+'&dlc='+encodeURIComponent($('sd').value));\n"
  "if(r.ok===false){$('sm').innerHTML='<span class=\"er\">'+r.err+'</span>';return;}scanPoll();}\n"
  "async function scanStop(){await J('/can/scanstop');scanPoll();}\n"
  "let _busy=0,_tick=0;\n"
  "async function pollStep(){if(_busy||document.hidden){setTimeout(pollStep,500);return;}_busy=1;try{await rx();if(_tick%3===0)await st();_tick++;}finally{_busy=0;setTimeout(pollStep,500);}}\n"
  "st().then(()=>{rx();draw();setTimeout(pollStep,500);});\n"
  "</script></body></html>";

const char UPDATE_HTML[] PROGMEM =   "<!DOCTYPE html>\n"
  "<html lang=\"id\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
  "<title>ESP32 OTA Update</title>\n"
  "<style>\n"
  ":root{--bg:#0e1116;--p:#161b22;--b:#2a313c;--t:#e6edf3;--d:#8b949e;--a:#5ee6a0;--w:#ff7b72}\n"
  "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:14px system-ui,sans-serif;padding:14px;max-width:480px;margin:auto}\n"
  ".box{background:var(--p);border:1px solid var(--b);border-radius:12px;padding:14px;margin-top:12px}\n"
  "h1{font-size:18px;margin:0 0 6px}a{color:#58a6ff}\n"
  ".kv{display:flex;justify-content:space-between;padding:4px 0;font-size:12px;border-bottom:1px dashed #222933}\n"
  ".kv span:first-child{color:var(--d)}.kv b{font-family:monospace}\n"
  "input[type=file]{width:100%;margin:12px 0;padding:10px;background:#0b0f14;border:1px solid var(--b);border-radius:8px;color:var(--t)}\n"
  "button{width:100%;background:var(--a);color:#0e1116;border:none;border-radius:8px;padding:11px;font-weight:700;font-size:14px;cursor:pointer}\n"
  "button:disabled{opacity:.5;cursor:not-allowed}\n"
  ".bar{height:10px;background:#0b0f14;border:1px solid var(--b);border-radius:99px;overflow:hidden;margin-top:12px}\n"
  ".fill{height:100%;width:0%;background:var(--a);transition:width .15s}\n"
  "#msg{font-size:12px;margin-top:8px;color:var(--d)}\n"
  "</style></head><body>\n"
  "<a href=\"/\">&larr; Dashboard</a> &middot; <a href=\"/cantool\">CAN Tool</a>\n"
  "<div class=\"box\">\n"
  "  <h1>Update Firmware OTA</h1>\n"
  "  <div class=\"kv\"><span>Firmware aktif</span><b id=\"fw\">-</b></div>\n"
  "  <div class=\"kv\"><span>Ruang sketch bebas</span><b id=\"sp\">-</b></div>\n"
  "  <div style=\"font-size:12px;color:var(--d);margin-top:10px\">\n"
  "    Pilih file <b>.bin</b> hasil compile Arduino IDE (<i>Sketch &rarr; Export Compiled Binary</i>). Jangan pilih file <code>.ino</code>.\n"
  "  </div>\n"
  "  <input type=\"file\" id=\"f\" accept=\".bin\">\n"
  "  <button id=\"btn\" onclick=\"go()\">Upload &amp; Flash OTA</button>\n"
  "  <div class=\"bar\"><div class=\"fill\" id=\"fill\"></div></div>\n"
  "  <div id=\"msg\">Siap.</div>\n"
  "</div>\n"
  "<script>\n"
  "const $=i=>document.getElementById(i);\n"
  "fetch('/can/status',{cache:'no-store'}).then(r=>r.json()).then(s=>{\n"
  "  $('fw').textContent=s.fw||'-';\n"
  "  $('sp').textContent=s.freeSketch?Math.round(s.freeSketch/1024)+' KB':'-';\n"
  "}).catch(()=>{});\n"
  "function go(){\n"
  "  const file=$('f').files&&$('f').files[0];\n"
  "  if(!file){$('msg').textContent='Pilih file .bin terlebih dahulu.';return;}\n"
  "  $('btn').disabled=true;\n"
  "  $('msg').style.color='#8b949e';\n"
  "  $('msg').textContent='Mengunggah '+file.name+' ('+Math.round(file.size/1024)+' KB)...';\n"
  "  const x=new XMLHttpRequest();\n"
  "  x.open('POST','/ota',true);\n"
  "  x.setRequestHeader('Content-Type','application/octet-stream');\n"
  "  x.upload.onprogress=e=>{\n"
  "    if(e.lengthComputable){\n"
  "      const p=Math.round((e.loaded/e.total)*100);\n"
  "      $('fill').style.width=p+'%';\n"
  "      $('msg').textContent='Mengirim OTA: '+p+'%';\n"
  "    }\n"
  "  };\n"
  "  x.onload=()=>{\n"
  "    try{\n"
  "      const r=JSON.parse(x.responseText||'{}');\n"
  "      if(x.status===200&&r.ok){\n"
  "        $('fill').style.width='100%';\n"
  "        $('msg').style.color='#5ee6a0';\n"
  "        $('msg').textContent='Berhasil! ESP32 reboot... memuat ulang dalam 5 detik.';\n"
  "        setTimeout(()=>location.href='/',5000);\n"
  "        return;\n"
  "      }\n"
  "      $('msg').style.color='#ff7b72';\n"
  "      $('msg').textContent='Gagal: '+(r.err||x.statusText||'error');\n"
  "    }catch(e){\n"
  "      $('msg').style.color='#ff7b72';\n"
  "      $('msg').textContent='Respon error: HTTP '+x.status;\n"
  "    }\n"
  "    $('btn').disabled=false;\n"
  "  };\n"
  "  x.onerror=()=>{\n"
  "    $('msg').style.color='#ff7b72';\n"
  "    $('msg').textContent='Koneksi putus ke 192.168.4.1.';\n"
  "    $('btn').disabled=false;\n"
  "  };\n"
  "  x.send(file);\n"
  "}\n"
  "</script></body></html>";

// ---------------- HTTP SERVER ----------------
String readClientLine(WiFiClient& client, unsigned long waitMs = 150) {
  String line = "";
  unsigned long start = millis();
  while (client.connected() && (millis() - start < waitMs)) {
    if (client.available()) {
      char c = client.read();
      if (c == '\r') {
        if (client.peek() == '\n') client.read();
        return line;
      } else if (c == '\n') {
        return line;
      } else {
        line += c;
      }
    } else {
      delay(1);
    }
  }
  return line;
}

void sendCorsHeaders(WiFiClient& c) {
  c.println("Access-Control-Allow-Origin: *");
  c.println("Access-Control-Allow-Methods: GET, POST, OPTIONS");
  c.println("Access-Control-Allow-Headers: Content-Type");
  c.println("Access-Control-Allow-Private-Network: true");
}

void replyJson(WiFiClient& c, const String& body) {
  c.println("HTTP/1.1 200 OK");
  c.println("Content-Type: application/json");
  c.println("Cache-Control: no-store");
  sendCorsHeaders(c);
  c.println("Connection: close");
  c.println();
  c.print(body);
  c.stop();
}

void replyJsonStatus(WiFiClient& c, int code, const char* statusText, const String& body) {
  c.print("HTTP/1.1 "); c.print(code); c.print(" "); c.println(statusText);
  c.println("Content-Type: application/json");
  c.println("Cache-Control: no-store");
  sendCorsHeaders(c);
  c.println("Connection: close");
  c.println();
  c.print(body);
  c.stop();
}

bool handleCanTool(WiFiClient& client, const String& reqLine) {
  if (reqLine.indexOf("GET /cantool") >= 0) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/html; charset=utf-8");
    client.println("Connection: close");
    client.println();
    client.print(CANTOOL_HTML);
    delay(2);
    client.stop();
    return true;
  }
  if (reqLine.indexOf("GET /can/") < 0) return false;
  String q = extractQuery(reqLine);

  if (reqLine.indexOf("GET /can/status") >= 0) { replyJson(client, ctStatusJson()); return true; }
  if (reqLine.indexOf("GET /can/rx") >= 0) {
    uint32_t since = (uint32_t)getQueryParamInt(q, "since", 0);
    replyJson(client, ctRxJson(since));
    return true;
  }
  if (reqLine.indexOf("GET /can/cfg") >= 0) {
    if (abRunning) { replyJson(client, "{\"ok\":false,\"err\":\"autodetect berjalan\"}"); return true; }
    String sb, sm;
    uint32_t nb = canBaud;
    bool nl = listenOnlyMode;
    if (getQueryParamStr(q, "baud", sb)) nb = (uint32_t)sb.toInt();
    if (getQueryParamStr(q, "mode", sm)) nl = !(sm == "normal");
    twai_timing_config_t tmp;
    if (!canTimingFor(nb, tmp)) { replyJson(client, "{\"ok\":false,\"err\":\"bitrate tidak didukung\"}"); return true; }
    if (nb != canBaud) {
      canBaud = nb;
      canPrefs.begin("cantool", false);
      canPrefs.putUInt("baud", canBaud);
      canPrefs.end();
    }
    ctRepOn = false; scRun = false;
    reinitTWAI(nl);
    replyJson(client, ctStatusJson());
    return true;
  }
  if (reqLine.indexOf("GET /can/filter") >= 0) {
    String sid; ctFilterOn = false;
    if (getQueryParamStr(q, "id", sid) && sid.length() > 0) {
      ctFilterId = strtoul(sid.c_str(), nullptr, 16);
      ctFilterOn = true;
    }
    ctFirst = ctSeq;
    replyJson(client, ctStatusJson());
    return true;
  }
  if (reqLine.indexOf("GET /can/clear") >= 0) { ctFirst = ctSeq; replyJson(client, ctStatusJson()); return true; }
  if (reqLine.indexOf("GET /can/autodetect") >= 0) { if (!abRunning) autoBaudStart(); replyJson(client, ctStatusJson()); return true; }
  if (reqLine.indexOf("GET /can/scanstop") >= 0) { scRun = false; replyJson(client, scanJson()); return true; }
  if (reqLine.indexOf("GET /can/scanres") >= 0) { replyJson(client, scanJson()); return true; }
  if (reqLine.indexOf("GET /can/scan?") >= 0) {
    if (abRunning) { replyJson(client, "{\"ok\":false,\"err\":\"autodetect berjalan\"}"); return true; }
    if (listenOnlyMode) { replyJson(client, "{\"ok\":false,\"err\":\"pindah ke mode NORMAL dulu\"}"); return true; }
    String sf, st2;
    if (!getQueryParamStr(q, "from", sf) || !getQueryParamStr(q, "to", st2) || !sf.length() || !st2.length()) {
      replyJson(client, "{\"ok\":false,\"err\":\"from/to kosong\"}"); return true;
    }
    uint32_t a = strtoul(sf.c_str(), nullptr, 16), b = strtoul(st2.c_str(), nullptr, 16);
    bool ext = getQueryParamInt(q, "ext", 1) != 0;
    if (b < a || b - a >= CT_SCAN_MAX) { replyJson(client, "{\"ok\":false,\"err\":\"maks 256 ID\"}"); return true; }
    uint32_t gap = constrain((uint32_t)getQueryParamInt(q, "gap", 60), 20UL, 2000UL);
    ctRepOn = false;
    memset(scRepl, 0, sizeof(scRepl)); memset(scOther, 0, sizeof(scOther));
    memset(scLen, 0, sizeof(scLen));   memset(scData, 0, sizeof(scData));
    scStart = a; scEnd = b; scCur = a; scExt = ext; scGapMs = gap;
    scDlc = min((uint8_t)(getQueryParamInt(q, "dlc", 0) & 0x0F), (uint8_t)8);
    scSent = 0; scLastSend = 0; scWinEnd = 0; scRun = true;
    replyJson(client, scanJson());
    return true;
  }
  if (reqLine.indexOf("GET /can/tx?") >= 0) {
    if (abRunning || listenOnlyMode) { replyJson(client, "{\"ok\":false,\"err\":\"butuh mode NORMAL\"}"); return true; }
    String sid, sd;
    if (!getQueryParamStr(q, "id", sid) || !sid.length()) { replyJson(client, "{\"ok\":false,\"err\":\"ID kosong\"}"); return true; }
    uint32_t id = strtoul(sid.c_str(), nullptr, 16);
    bool ext = getQueryParamInt(q, "ext", 1) != 0;
    bool rtr = getQueryParamInt(q, "rtr", 0) != 0;
    uint8_t d[8] = {0}, len = 0;
    if (getQueryParamStr(q, "data", sd)) len = ctParseHex(sd, d);
    if (rtr) len = min((uint8_t)(getQueryParamInt(q, "dlc", 0) & 0x0F), (uint8_t)8);
    if (getQueryParamInt(q, "rep", 0) != 0) {
      ctRepId = id; ctRepExt = ext; ctRepRtr = rtr; ctRepLen = len;
      ctRepMs = max(20U, (unsigned)getQueryParamInt(q, "ms", 1000));
      memcpy(ctRepData, d, 8);
      ctRepLast = 0; ctRepSent = 0; ctRepOn = true;
      replyJson(client, "{\"ok\":true,\"rep\":true}");
      return true;
    }
    bool ok = ctSend(id, ext, rtr, d, len);
    replyJson(client, ok ? "{\"ok\":true}" : "{\"ok\":false,\"err\":\"TX gagal (cek bitrate/ACK)\"}");
    return true;
  }
  if (reqLine.indexOf("GET /can/txstop") >= 0) { ctRepOn = false; replyJson(client, ctStatusJson()); return true; }
  return false;
}

void handleOtaUpload(WiFiClient& client, size_t contentLength) {
  if (contentLength == 0 || contentLength > ESP.getFreeSketchSpace()) {
    replyJsonStatus(client, 400, "Bad Request",
      "{\"ok\":false,\"err\":\"Ukuran .bin tidak valid atau melebihi kapasitas flash\"}");
    return;
  }
  opMode = OP_OFF;
  ctRepOn = false;
  scRun = false;
  if (sseClientActive) { sseClient.stop(); sseClientActive = false; }

  if (!Update.begin(contentLength)) {
    replyJsonStatus(client, 500, "Internal Server Error",
      String("{\"ok\":false,\"err\":\"Update.begin gagal: ") + Update.errorString() + "\"}");
    return;
  }

  uint8_t buf[1024];
  size_t received = 0;
  unsigned long lastDataMs = millis();
  bool magicChecked = false;
  bool magicValid = true;

  while (received < contentLength && (millis() - lastDataMs < 15000)) {
    int avail = client.available();
    if (avail > 0) {
      size_t want = contentLength - received;
      if (want > sizeof(buf)) want = sizeof(buf);
      if ((size_t)avail < want) want = (size_t)avail;
      int n = client.read(buf, want);
      if (n > 0) {
        lastDataMs = millis();
        if (!magicChecked) {
          magicChecked = true;
          if (buf[0] != 0xE9) {
            magicValid = false;
            break;
          }
        }
        if (Update.write(buf, (size_t)n) != (size_t)n) {
          Update.abort();
          replyJsonStatus(client, 500, "Internal Server Error",
            String("{\"ok\":false,\"err\":\"Write flash gagal: ") + Update.errorString() + "\"}");
          return;
        }
        received += (size_t)n;
      }
    } else {
      if (!client.connected()) break;
      delay(1);
    }
    yield();
  }

  if (!magicValid) {
    Update.abort();
    replyJsonStatus(client, 400, "Bad Request",
      "{\"ok\":false,\"err\":\"Header file bukan .bin ESP32 (magic != 0xE9)\"}");
    return;
  }
  if (received != contentLength) {
    Update.abort();
    replyJsonStatus(client, 400, "Bad Request",
      "{\"ok\":false,\"err\":\"Upload terputus sebelum selesai\"}");
    return;
  }
  if (!Update.end(true) || Update.hasError()) {
    replyJsonStatus(client, 500, "Internal Server Error",
      String("{\"ok\":false,\"err\":\"Update.end gagal: ") + Update.errorString() + "\"}");
    return;
  }

  replyJson(client, String("{\"ok\":true,\"fw\":\"") + FW_VERSION + "\",\"size\":" + String((unsigned long)received) + "}");
  delay(400);
  ESP.restart();
}

void handleClient(WiFiClient client) {
  String reqLine = readClientLine(client, 80);
  if (reqLine.length() == 0) {
    client.stop();
    return;
  }
  size_t contentLength = 0;
  while (client.connected()) {
    String line = readClientLine(client, 120);
    if (line.length() == 0) break;
    String low = line;
    low.toLowerCase();
    if (low.startsWith("content-length:")) {
      contentLength = (size_t)low.substring(15).toInt();
    }
  }

  if (reqLine.startsWith("OPTIONS ")) {
    client.println("HTTP/1.1 204 No Content");
    sendCorsHeaders(client);
    client.println("Connection: close");
    client.println();
    client.stop();
    return;
  }

  if (reqLine.startsWith("POST /ota")) {
    handleOtaUpload(client, contentLength);
    return;
  }

  if (reqLine.indexOf("GET /update") >= 0) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/html; charset=utf-8");
    client.println("Connection: close");
    client.println();
    client.print(UPDATE_HTML);
    delay(2);
    client.stop();
    return;
  }

  if (handleCanTool(client, reqLine)) return;

  if (reqLine.indexOf("GET /mode?set=normal") >= 0) {
    reinitTWAI(false);
    replyJson(client, "{\"mode\":\"normal\"}");
    return;
  }
  if (reqLine.indexOf("GET /mode?set=listen") >= 0) {
    opMode = OP_OFF;
    reinitTWAI(true);
    replyJson(client, "{\"mode\":\"listen\"}");
    return;
  }
  if (reqLine.indexOf("GET /mode") >= 0) {
    replyJson(client, listenOnlyMode ? "{\"mode\":\"listen\"}" : "{\"mode\":\"normal\"}");
    return;
  }
  if (reqLine.indexOf("GET /mid/on") >= 0) {
    if (listenOnlyMode) reinitTWAI(false); // auto ke NORMAL agar RTR heartbeat bisa TX
    opMode = OP_MID;
    midHeartbeatStep = 9;
    midHeartbeatCycleStart = 0;
    replyJson(client, "{\"midEmulate\":true,\"opMode\":1}");
    return;
  }
  if (reqLine.indexOf("GET /mid/off") >= 0) {
    opMode = OP_OFF;
    replyJson(client, "{\"midEmulate\":false,\"opMode\":0}");
    return;
  }
  if (reqLine.indexOf("GET /events") >= 0) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/event-stream");
    client.println("Cache-Control: no-cache");
    client.println("Connection: keep-alive");
    client.println("Access-Control-Allow-Origin: *");
    client.println();
    sseClient = client;
    sseClientActive = true;
    return;
  }

  client.println("HTTP/1.1 200 OK");
  client.println("Content-Type: text/html; charset=utf-8");
  client.println("Connection: close");
  client.println();
  client.print(INDEX_HTML);
  delay(2);
  client.stop();
}

// ---------------- SETUP & LOOP ----------------
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== Gesits CAN Minimal ===");

  setupCAN();
  setupBLE();

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_PASSWORD, 6);
  WiFi.setTxPower(WIFI_POWER_11dBm);
  esp_wifi_set_ps(WIFI_PS_NONE);
  server.begin();

  Serial.print("WiFi AP: "); Serial.print(AP_SSID);
  Serial.print(" | http://"); Serial.println(WiFi.softAPIP());
}

void decodeGesitsFrame(const twai_message_t& m) {
  if (m.rtr) return;
  if (m.identifier == 0x10261022 && m.data_length_code >= 8) {
    uint8_t b1 = m.data[1];
    int modeBits = b1 & 0xC0;
    telem.mode = (modeBits == 0xC0) ? 2 : (modeBits == 0x40 ? 1 : 0);
    telem.park = (b1 & 0x01) ? 1 : 0;
    telem.rem = (b1 & 0x04) ? 1 : 0;
    telem.cruise = (b1 & 0x08) ? 1 : 0;
    telem.sidestand = (b1 & 0x10) ? 1 : 0;
    uint16_t speedRaw = m.data[2] | (m.data[3] << 8);
    telem.speedKmh = speedRaw / 12.0f;
    telem.tinv = m.data[4];
    telem.tmot = m.data[5];
    telem.torqueRaw = (int16_t)(m.data[6] | (m.data[7] << 8));
  } else if (m.identifier == 0x10261023 && m.data_length_code >= 3) {
    telem.throttlePct = m.data[2];
  } else if ((m.identifier == 0x10261100 || m.identifier == 0x10261200) && m.data_length_code >= 6) {
    telem.soc  = m.data[1];
    telem.soh  = m.data[2];
    telem.tbat = m.data[3];
    telem.vbat = m.data[4];
    telem.ibat = (int8_t)m.data[5];
  } else if (m.identifier == 0x10261202 && m.data_length_code >= 7) {
    telem.cbat = m.data[6];
  }
}

void loop() {
  processCanCmdQueue();

  WiFiClient newClient = server.available();
  if (newClient) handleClient(newClient);

  serviceMidHeartbeat();
  serviceControllerSim();
  serviceBatterySim();
  canToolService();

  // Drain RX sampai 16 frame per iterasi supaya antrean tidak penuh saat bus ramai
  twai_message_t message;
  int rxBatch = 0;
  while (rxBatch < 16 && twai_receive(&message, rxBatch == 0 ? pdMS_TO_TICKS(5) : 0) == ESP_OK) {
    rxBatch++;
    frameCount++;
    canToolLogRx(message);
    decodeGesitsFrame(message);

    if (sseClientActive) {
      if (sseClient.connected()) {
        sseClient.print("data: ");
        sseClient.print(frameToJson(message));
        sseClient.print("\n\n");
      } else {
        sseClientActive = false;
      }
    }
  }

#if ENABLE_BLE
  static uint32_t lastBleSend = 0;
  static int bleFieldIndex = 0;
  if (bleClientConnected && millis() - lastBleSend >= 80) {
    lastBleSend = millis();
    String line = "";
    switch (bleFieldIndex) {
      case 0:  if (telem.speedKmh    >= 0)    line = "SPD="  + String(telem.speedKmh, 1); break;
      case 1:  if (telem.soc         >= 0)    line = "SOC="  + String((int)telem.soc); break;
      case 2:  if (telem.soh         >= 0)    line = "SOH="  + String((int)telem.soh); break;
      case 3:  if (telem.tbat        >= 0)    line = "TBAT=" + String((int)telem.tbat); break;
      case 4:  if (telem.vbat        >= 0)    line = "VBAT=" + String((int)telem.vbat); break;
      case 5:  if (telem.ibat        >= -128) line = "IBAT=" + String((int)telem.ibat); break;
      case 6:  if (telem.cbat        >= 0)    line = "CBAT=" + String((int)telem.cbat); break;
      case 7:  if (telem.tinv        >= 0)    line = "TINV=" + String((int)telem.tinv); break;
      case 8:  if (telem.tmot        >= 0)    line = "TMOT=" + String((int)telem.tmot); break;
      case 9:  if (telem.throttlePct >= 0)    line = "TRT="  + String((int)telem.throttlePct); break;
      case 10: if (telem.mode        >= 0)    line = "MODE=" + String(telem.mode); break;
      case 11: if (telem.park        >= 0)    line = "PARK=" + String(telem.park); break;
      case 12: if (telem.cruise      >= 0)    line = "CRZ="  + String(telem.cruise); break;
      case 13: if (telem.rem         >= 0)    line = "REM="  + String(telem.rem); break;
      case 14: if (telem.sidestand   >= 0)    line = "STD="  + String(telem.sidestand); break;
      case 15: line = String("CANMODE=") + (listenOnlyMode ? "0" : "1"); break;
      case 16: line = String("MIDEMU=")  + (opMode == OP_MID ? "1" : "0"); break;
      case 17: line = String("SIMMODE=") + String((int)opMode); break;
      case 18: line = String("FWVER=")   + FW_VERSION; break;
    }
    bleFieldIndex = (bleFieldIndex + 1) % 19;
    if (line.length() > 0) bleSendLine(line);
  }
#endif
}
