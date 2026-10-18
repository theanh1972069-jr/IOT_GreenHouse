/*
 * ==============================================================================
 * DỰ ÁN: IOT GREENHOUSE (Nhà kính thông minh)
 * FIRMWARE: ESP32 Greenhouse Node 1 / Node 2
 * ==============================================================================
 *
 * TÍNH NĂNG ĐẶC BIỆT (KHÔNG CẦN SỬA CODE KHI ĐỔI WI-FI):
 * ------------------------------------------------------------------------------
 * - Chọn vai trò node1 hoặc node2 trong captive portal. Mỗi ESP32 được cấu hình
 *   riêng với device_id và zone riêng.
 * - Khi bật nguồn, nếu chưa có Wi-Fi đã lưu (hoặc không kết nối được), ESP32
 *   sẽ phát mạng cấu hình "GreenHouse_ESP32_Setup" với mật khẩu ngẫu nhiên.
 * - Bạn chỉ cần dùng điện thoại/máy tính kết nối vào mạng đó, trang web cấu hình
 *   (192.168.4.1) sẽ tự động hiện lên để bạn:
 *      1. Chọn tên Wi-Fi nhà bạn và gõ mật khẩu.
 *      2. Nhập địa chỉ MQTT broker.
 *      3. Chọn vai trò, zone và device_id riêng cho node.
 *   Bấm [SAVE], ESP32 sẽ lưu vĩnh viễn vào bộ nhớ Flash và kết nối mạng!
 *
 * - NÚT RESET CẤU HÌNH (NÚT BOOT TRÊN ESP32 - GPIO 0):
 *   Nếu muốn đổi sang Wi-Fi khác: Hãy bấm giữ nút BOOT trên board khoảng 3 giây,
 *   ESP32 sẽ xóa cấu hình cũ và phát lại Wi-Fi Setup để bạn nhập lại.
 *
 * PHÂN BỔ CHÂN THEO NODE:
 * ------------------------------------------------------------------------------
 * 1. NGUỒN:
 *    - Node 1/Node 2: cấp riêng theo board và đo dòng thực tế.
 *    - Quạt, đèn, van dùng nguồn DC riêng; không cấp tải từ chân ESP32.
 *    - Van DC cần diode flyback; chỉ dùng điện DC, không đấu điện lưới.
 *
 * 2. CẢM BIẾN:
 *    - DHT22 (Nhiệt độ & Độ ẩm không khí):
 *        OUT (Data) -> D27 (GPIO 27)
 *        + (VCC)    -> Rail Đỏ (3.3V)
 *        - (GND)    -> Rail Xanh (GND)
 *    - Cảm biến đất điện dung (tùy chọn, chỉ Node 2; mặc định tắt):
 *        AOUT (Data)-> D35 (GPIO 35, ADC1)
 *
 * 3. MODULE RELAY 4 KÊNH (Active LOW: LOW = BẬT, HIGH = TẮT):
 *    - Node 1: D33 -> relay quạt, D32 -> relay đèn.
 *    - Node 2: D25 -> relay van; van tự đóng sau tối đa 30 giây.
 *    - DHT22 trên cả hai node dùng D27.
 *    * Quy tắc: digitalWrite(HIGH) TRƯỚC pinMode(OUTPUT) để chống chớp relay lúc boot!
 * ==============================================================================
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <WiFiManager.h>   // Thư viện tạo Web Captive Portal
#include <Preferences.h>   // Lưu cấu hình MQTT Server, Device ID vào Flash ESP32
#include <ctype.h>
#include <esp_sleep.h>
#include <sys/time.h>
#include <time.h>

#define ENABLE_SOIL_SENSOR 0

// ============================ 1. NÚT RESET CẤU HÌNH ==========================
#define TRIGGER_PIN  0     // Nút BOOT trên ESP32 (GPIO 0)

// ============================ 2. BỘ NHỚ LƯU CẤU HÌNH =========================
Preferences preferences;

// Giá trị mặc định (sẽ được ghi đè khi bạn nhập trên Web Portal)
char mqtt_server[40] = "192.168.1.220";
char mqtt_port_str[6] = "1883";
char device_id[32]    = "esp32-01";
char device_zone[51]  = "livingroom";
char node_role[6]     = "node1";

// Cờ kiểm tra có lưu cấu hình mới từ web không
bool shouldSaveConfig = false;

// ============================ 3. ĐỊNH NGHĨA CHÂN & THIẾT BỊ ==================
#define DHTPIN       27
#define DHTTYPE      DHT22
DHT dht(DHTPIN, DHTTYPE);

#define SOIL_PIN     35

// Hiệu chỉnh cảm biến HW-390
const int AIR_VALUE   = 3200;  // Để khô ngoài không khí (0%)
const int WATER_VALUE = 1400;  // Nhúng vào cốc nước (100%)

// Các chân Relay (Active LOW)
#define RELAY_VALVE_PIN  25    // IN1: Van nước
#define RELAY_FAN_PIN    33    // IN2: Quạt gió
#define RELAY_LIGHT_PIN  32    // IN3: Đèn quang hợp

#define RELAY_ON   LOW
#define RELAY_OFF  HIGH

bool valveState = false;
bool valveMaxDurationReached = false;
bool fanState   = false;
bool lightState = false;

// Chế độ tự động điều khiển theo ngưỡng
bool autoControlMode = true;

// ============================ 4. BIẾN THỜI GIAN ==============================
static const uint32_t READ_INTERVAL_MS    = 2000;   // Đọc cảm biến mỗi 2 giây
static const uint32_t PUBLISH_INTERVAL_MS = 10000;  // Gửi lên server mỗi 10 giây
static const uint32_t MQTT_RETRY_MS       = 5000;
static const uint32_t VALVE_MAX_OPEN_MS   = 30000;  // Giá trị khởi điểm, cần đo lưu lượng thực tế
static const uint32_t NODE2_SLEEP_MS      = 15UL * 60UL * 1000UL;
static const uint32_t NODE2_WAKE_LIMIT_MS = 120UL * 1000UL;
static const uint32_t NODE2_COMMAND_WINDOW_MS = 5000;

WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);

static uint32_t lastReadMs       = 0;
static uint32_t lastPublishMs    = 0;
static uint32_t lastMqttRetryMs  = 0;
static uint32_t valveOpenedAtMs  = 0;
static uint32_t node2WakeStartedMs = 0;
static uint32_t node2PublishedAtMs = 0;
static bool node2Published = false;

float airTemp      = 0.0;
float airHumidity  = 0.0;
int   soilRaw      = 0;
int   soilMoisture = 0;
bool  airReadingValid = false;

String topicTelemetry = "";
String topicControl   = "";
String legacyTopicControl = "";

bool isNode2() {
  return strcmp(node_role, "node2") == 0;
}

bool isSafeDeviceId(const String& value) {
  if (value.length() < 3 || value.length() >= sizeof(device_id)) return false;
  for (unsigned int i = 0; i < value.length(); i++) {
    char c = value.charAt(i);
    if (!isalnum(static_cast<unsigned char>(c)) && c != '.' && c != '_' && c != ':' && c != '-') {
      return false;
    }
  }
  return true;
}

// ============================ 5. ĐIỀU KHIỂN RELAY ============================
void setRelay(int pin, bool state, const char* name) {
  digitalWrite(pin, state ? RELAY_ON : RELAY_OFF);
  Serial.print(F("[RELAY] "));
  Serial.print(name);
  Serial.println(state ? F(" -> BẬT (ON) [Đèn LED Relay SÁNG]") : F(" -> TẮT (OFF) [Đèn LED Relay TẮT]"));
}

void setValveState(bool state, const char* reason) {
  if (valveState == state) return;
  valveState = state;
  if (state) {
    valveOpenedAtMs = millis();
  } else {
    valveMaxDurationReached = false;
  }
  setRelay(RELAY_VALVE_PIN, state, reason);
}

// ============================ 6. XỬ LÝ LỆNH TỪ SERVER ========================
void onMqttMessage(char* topic, byte* payload, unsigned int length) {
  String message = "";
  for (unsigned int i = 0; i < length; i++) {
    message += (char)payload[i];
  }
  Serial.print(F("[MQTT LỆNH] Nhận từ topic ["));
  Serial.print(topic);
  Serial.print(F("]: "));
  Serial.println(message);

  if (isNode2() && message.indexOf("\"valve\":1") >= 0) {
    setValveState(true, "Van nước (IN1)");
  } else if (isNode2() && message.indexOf("\"valve\":0") >= 0) {
    setValveState(false, "Van nước (IN1)");
  }

  if (!isNode2() && message.indexOf("\"fan\":1") >= 0) {
    fanState = true;
    setRelay(RELAY_FAN_PIN, true, "Quạt gió (IN2)");
  } else if (!isNode2() && message.indexOf("\"fan\":0") >= 0) {
    fanState = false;
    setRelay(RELAY_FAN_PIN, false, "Quạt gió (IN2)");
  }

  if (!isNode2() && message.indexOf("\"light\":1") >= 0) {
    lightState = true;
    setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3)");
  } else if (!isNode2() && message.indexOf("\"light\":0") >= 0) {
    lightState = false;
    setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3)");
  }

  if (!isNode2() && message.indexOf("\"auto\":1") >= 0) {
    autoControlMode = true;
    Serial.println(F("[CHẾ ĐỘ] Bật tự động điều khiển theo ngưỡng."));
  } else if (!isNode2() && message.indexOf("\"auto\":0") >= 0) {
    autoControlMode = false;
    Serial.println(F("[CHẾ ĐỘ] Tắt tự động (chuyển sang điều khiển thủ công)."));
  }
}

// ============================ 7. CALLBACK LƯU TỪ WEB =========================
void saveConfigCallback() {
  Serial.println(F("=> Người dùng đã nhấn lưu cấu hình từ Web!"));
  shouldSaveConfig = true;
}

// ============================ 8. KẾT NỐI MQTT ================================
void connectMqtt() {
  if (mqtt.connected()) return;
  if (millis() - lastMqttRetryMs < MQTT_RETRY_MS) return;
  lastMqttRetryMs = millis();

  uint16_t port = atoi(mqtt_port_str);
  Serial.print(F("Đang kết nối tới MQTT Broker "));
  Serial.print(mqtt_server);
  Serial.print(F(":"));
  Serial.println(port);

  if (mqtt.connect(device_id)) {
    Serial.println(F("=> MQTT Broker đã kết nối thành công!"));
    mqtt.subscribe(topicControl.c_str(), 1);
    mqtt.subscribe(legacyTopicControl.c_str(), 1);
    Serial.print(F("=> Đã lắng nghe topic lệnh: "));
    Serial.println(topicControl);
  } else {
    Serial.print(F("=> Kết nối MQTT thất bại! rc="));
    Serial.println(mqtt.state());
  }
}

// ============================ 9. GỬI DỮ LIỆU TELEMETRY =======================
bool publishTelemetry() {
  struct tm timeInfo;
  if (!getLocalTime(&timeInfo, 1000) || timeInfo.tm_year < 120) {
    Serial.println(F("[MQTT] Chưa đồng bộ NTP; bỏ qua telemetry để không gán timestamp sai."));
    return false;
  }

  char timestamp[25];
  strftime(timestamp, sizeof(timestamp), "%Y-%m-%dT%H:%M:%SZ", &timeInfo);
  struct timeval preciseTime;
  gettimeofday(&preciseTime, nullptr);
  uint64_t sequence = static_cast<uint64_t>(preciseTime.tv_sec) * 1000000ULL
          + static_cast<uint64_t>(preciseTime.tv_usec);

  bool dhtValid = airReadingValid;
  char jsonBuffer[320];
  if (dhtValid && isNode2() && ENABLE_SOIL_SENSOR) {
    snprintf(jsonBuffer, sizeof(jsonBuffer),
             "{\"device_id\":\"%s\",\"ts\":\"%s\",\"seq\":%llu,\"zone\":\"%s\","
             "\"temp\":{\"v\":%.1f,\"u\":\"C\"},\"hum\":{\"v\":%.1f,\"u\":\"%%\"},"
             "\"soilMoisture\":{\"v\":%d,\"u\":\"%%\"},\"q\":\"ok\",\"fw\":\"1.0.2\"}",
             device_id, timestamp, static_cast<unsigned long long>(sequence),
             device_zone,
             airTemp, airHumidity, soilMoisture);
  } else if (dhtValid) {
    snprintf(jsonBuffer, sizeof(jsonBuffer),
             "{\"device_id\":\"%s\",\"ts\":\"%s\",\"seq\":%llu,\"zone\":\"%s\","
             "\"temp\":{\"v\":%.1f,\"u\":\"C\"},\"hum\":{\"v\":%.1f,\"u\":\"%%\"},"
             "\"q\":\"ok\",\"fw\":\"1.0.2\"}",
             device_id, timestamp, static_cast<unsigned long long>(sequence),
             device_zone, airTemp, airHumidity);
  } else {
    snprintf(jsonBuffer, sizeof(jsonBuffer),
             "{\"device_id\":\"%s\",\"ts\":\"%s\",\"seq\":%llu,\"zone\":\"%s\","
             "\"temp\":{\"v\":null,\"u\":\"C\"},\"hum\":{\"v\":null,\"u\":\"%%\"},"
             "\"q\":\"sensor_fault\",\"fw\":\"1.0.2\"}",
             device_id, timestamp, static_cast<unsigned long long>(sequence), device_zone);
  }

  if (mqtt.publish(topicTelemetry.c_str(), jsonBuffer)) {
    Serial.print(F(">> [MQTT ĐÃ GỬI THÀNH CÔNG]: "));
    Serial.println(jsonBuffer);
    return true;
  } else {
    Serial.print(F(">> [LỖI GỬI MQTT] rc="));
    Serial.println(mqtt.state());
    return false;
  }
}

// ============================ 10. SETUP ======================================
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println(F("\n========================================================"));
  Serial.println(F("   HỆ THỐNG IOT GREENHOUSE - ESP32 KHỞI ĐỘNG            "));
  Serial.println(F("========================================================"));

  // --- CẤU HÌNH RELAY (ĐẶC BIỆT QUAN TRỌNG) ---
  // digitalWrite(HIGH) TRƯỚC pinMode(OUTPUT) để chống chớp giật relay lúc boot
  digitalWrite(RELAY_VALVE_PIN, RELAY_OFF);
  pinMode(RELAY_VALVE_PIN, OUTPUT);

  digitalWrite(RELAY_FAN_PIN, RELAY_OFF);
  pinMode(RELAY_FAN_PIN, OUTPUT);

  digitalWrite(RELAY_LIGHT_PIN, RELAY_OFF);
  pinMode(RELAY_LIGHT_PIN, OUTPUT);

  Serial.println(F("[RELAY] Đã khởi tạo 3 kênh Relay ở trạng thái TẮT an toàn."));

  // Nút BOOT (GPIO 0) làm nút reset Wi-Fi
  pinMode(TRIGGER_PIN, INPUT_PULLUP);

  // Khởi tạo cảm biến
  pinMode(DHTPIN, INPUT_PULLUP);
  dht.begin();

  // Đọc cấu hình MQTT đã lưu trong bộ nhớ Flash (NVS)
  preferences.begin("greenhouse", false);
  String savedServer = preferences.getString("mqtt_server", "192.168.1.220");
  String savedPort   = preferences.getString("mqtt_port", "1883");
  String savedDevId  = preferences.getString("device_id", "esp32-01");
  String savedZone   = preferences.getString("zone", "livingroom");
  String savedRole   = preferences.getString("node_role", "node1");

  savedServer.toCharArray(mqtt_server, sizeof(mqtt_server));
  savedPort.toCharArray(mqtt_port_str, sizeof(mqtt_port_str));
  savedDevId.toCharArray(device_id, sizeof(device_id));
  savedZone.toCharArray(device_zone, sizeof(device_zone));
  savedRole.toCharArray(node_role, sizeof(node_role));
  if (strcmp(node_role, "node1") != 0 && strcmp(node_role, "node2") != 0) {
    strlcpy(node_role, "node1", sizeof(node_role));
  }
  if (!preferences.isKey("device_id")) {
    strlcpy(device_id, isNode2() ? "esp32-n2" : "esp32-n1", sizeof(device_id));
  }
  if (!preferences.isKey("zone")) {
    strlcpy(device_zone, isNode2() ? "balcony" : "livingroom", sizeof(device_zone));
  }
  Serial.println(F("[CẤU HÌNH HIỆN TẠI TỪ FLASH]:"));
  Serial.print(F("  - MQTT Server : ")); Serial.println(mqtt_server);
  Serial.print(F("  - MQTT Port   : ")); Serial.println(mqtt_port_str);
  Serial.print(F("  - Device ID   : ")); Serial.println(device_id);

  // Kiểm tra nếu đang bấm giữ nút BOOT khi khởi động -> Xóa Wi-Fi để cài đặt lại
  if (digitalRead(TRIGGER_PIN) == LOW) {
    Serial.println(F("\n[CẢNH BÁO] Phát hiện bấm giữ nút BOOT! Đang xóa cấu hình Wi-Fi..."));
    WiFiManager wmReset;
    wmReset.resetSettings();
    delay(1000);
  }

  // --- CẤU HÌNH WIFIMANAGER (WEB CAPTIVE PORTAL) ---
  WiFiManager wm;
  wm.setSaveConfigCallback(saveConfigCallback);
  wm.setConfigPortalTimeout(180); // Chờ tối đa 3 phút trong trang web setup

  // Thêm các ô nhập dữ liệu tùy biến lên trang Web
  WiFiManagerParameter custom_mqtt_server("server", "IP Server Máy Tính (MQTT)", mqtt_server, 40);
  WiFiManagerParameter custom_mqtt_port("port", "Cổng MQTT (mặc định 1883)", mqtt_port_str, 6);
  WiFiManagerParameter custom_device_id("devid", "Device ID (trùng với Web)", device_id, 32);
  WiFiManagerParameter custom_zone("zone", "Khu vực thiết bị (livingroom/balcony)", device_zone, 50);
  WiFiManagerParameter custom_role("role", "Vai trò thiết bị (node1/node2)", node_role, 5);

  wm.addParameter(&custom_mqtt_server);
  wm.addParameter(&custom_mqtt_port);
  wm.addParameter(&custom_device_id);
  wm.addParameter(&custom_zone);
  wm.addParameter(&custom_role);

  Serial.println(F("\n[KẾT NỐI MẠNG] Đang kết nối Wi-Fi..."));
  Serial.println(F("=> NẾU CHƯA CÓ WI-FI, ESP32 SẼ TỰ ĐỘNG PHÁT WI-FI: 'GreenHouse_ESP32_Setup'"));
  Serial.println(F("=> Hãy kết nối vào đó và mở trình duyệt gõ: 192.168.4.1 để cấu hình!"));

  char setupPassword[9];
  snprintf(setupPassword, sizeof(setupPassword), "%08lx",
           static_cast<unsigned long>(esp_random()));
  Serial.print(F("Mật khẩu tạm thời của Wi-Fi cấu hình: "));
  Serial.println(setupPassword);
  bool res = wm.autoConnect("GreenHouse_ESP32_Setup", setupPassword);

  if (!res) {
    Serial.println(F("=> Hết thời gian chờ cấu hình Wi-Fi hoặc kết nối thất bại! Đang khởi động lại..."));
    delay(2000);
    ESP.restart();
  }

  // Nếu người dùng đã nhập thông số mới trên trang Web -> Lưu vào Flash
  if (shouldSaveConfig) {
    String serverValue = custom_mqtt_server.getValue();
    if (!serverValue.isEmpty() && serverValue.length() < sizeof(mqtt_server)) {
      serverValue.toCharArray(mqtt_server, sizeof(mqtt_server));
    } else {
      Serial.println(F("[CẤU HÌNH] MQTT server không hợp lệ; giữ server đã lưu."));
    }
    int portValue = String(custom_mqtt_port.getValue()).toInt();
    if (portValue > 0 && portValue <= 65535) {
      snprintf(mqtt_port_str, sizeof(mqtt_port_str), "%d", portValue);
    } else {
      Serial.println(F("[CẤU HÌNH] MQTT port không hợp lệ; giữ port đã lưu."));
    }
    String idValue = custom_device_id.getValue();
    if (isSafeDeviceId(idValue)) {
      idValue.toCharArray(device_id, sizeof(device_id));
    } else {
      Serial.println(F("[CẤU HÌNH] Device ID không hợp lệ; giữ ID đã lưu."));
    }
    String roleValue = custom_role.getValue();
    if (roleValue == "node1" || roleValue == "node2") {
      roleValue.toCharArray(node_role, sizeof(node_role));
      if (roleValue == "node2" && strcmp(device_id, "esp32-n1") == 0) {
        strlcpy(device_id, "esp32-n2", sizeof(device_id));
      } else if (roleValue == "node1" && strcmp(device_id, "esp32-n2") == 0) {
        strlcpy(device_id, "esp32-n1", sizeof(device_id));
      }
    } else {
      Serial.println(F("[CẤU HÌNH] Vai trò không hợp lệ; giữ vai trò đã lưu."));
    }
    String zoneValue = custom_zone.getValue();
    if (roleValue == "node2" && zoneValue == "livingroom") {
      zoneValue = "balcony";
    } else if (roleValue == "node1" && zoneValue == "balcony") {
      zoneValue = "livingroom";
    }
    bool validZone = !zoneValue.isEmpty() && zoneValue.length() <= 50;
    for (unsigned int i = 0; validZone && i < zoneValue.length(); i++) {
      char c = zoneValue.charAt(i);
      validZone = isalnum(static_cast<unsigned char>(c)) || c == '-' || c == '_' || c == '.';
    }
    if (validZone) {
      zoneValue.toCharArray(device_zone, sizeof(device_zone));
    } else {
      Serial.println(F("[CẤU HÌNH] Zone không hợp lệ; giữ zone đã lưu trước đó."));
    }

    preferences.putString("mqtt_server", mqtt_server);
    preferences.putString("mqtt_port", mqtt_port_str);
    preferences.putString("device_id", device_id);
    preferences.putString("zone", device_zone);
    preferences.putString("node_role", node_role);

    Serial.println(F("=> Đã lưu thành công cấu hình mới vào bộ nhớ Flash!"));
  }
  preferences.end();
  if (isNode2() && ENABLE_SOIL_SENSOR) {
    pinMode(SOIL_PIN, INPUT);
  }
  node2WakeStartedMs = millis();

  // Khởi tạo topic MQTT theo Device ID
  topicTelemetry = "sh/home1/" + String(device_id) + "/telemetry";
  topicControl   = "sh/home1/" + String(device_id) + "/cmd";
  legacyTopicControl = "greenhouse/" + String(device_id) + "/control";

  Serial.println(F("\n=> WI-FI ĐÃ KẾT NỐI THÀNH CÔNG!"));
  Serial.print(F("=> IP của ESP32: "));
  Serial.println(WiFi.localIP());

  // Cấu hình MQTT Client
  WiFi.setSleep(isNode2());
  configTime(0, 0, "pool.ntp.org", "time.nist.gov");
  mqtt.setServer(mqtt_server, atoi(mqtt_port_str));
  mqtt.setCallback(onMqttMessage);
  mqtt.setBufferSize(512);

  Serial.println(F("[HỆ THỐNG] Đã sẵn sàng hoạt động."));
}

// ============================ 11. LOOP =======================================
void loop() {
  // 1. Kiểm tra nút BOOT: Nếu nhấn giữ nút BOOT trong lúc đang chạy > 3 giây -> Mở lại Web Setup
  if (digitalRead(TRIGGER_PIN) == LOW) {
    delay(50);
    if (digitalRead(TRIGGER_PIN) == LOW) {
      Serial.println(F("\n[RESET] Đang mở lại Web Portal cấu hình Wi-Fi..."));
      WiFiManager wm;
      wm.resetSettings();
      delay(500);
      ESP.restart();
    }
  }

  if (valveState && static_cast<uint32_t>(millis() - valveOpenedAtMs) >= VALVE_MAX_OPEN_MS) {
    setValveState(false, "Van nước tự đóng khi hết thời gian an toàn");
    valveMaxDurationReached = true;
  }

  // 2. Đọc cảm biến định kỳ mỗi 2 giây
  if (millis() - lastReadMs >= READ_INTERVAL_MS) {
    lastReadMs = millis();

    // Đọc DHT22
    float h = dht.readHumidity();
    float t = dht.readTemperature();
    bool dhtValid = !isnan(h) && !isnan(t);
    airReadingValid = dhtValid;
    if (dhtValid) {
      airHumidity = h;
      airTemp     = t;
    }

    if (isNode2() && ENABLE_SOIL_SENSOR) {
      soilRaw = analogRead(SOIL_PIN);
      soilMoisture = constrain(map(soilRaw, AIR_VALUE, WATER_VALUE, 0, 100), 0, 100);
      if (soilMoisture >= 60) {
        valveMaxDurationReached = false;
      }
    }

    if (autoControlMode && isNode2() && ENABLE_SOIL_SENSOR) {
      if (soilMoisture < 30 && !valveState && !valveMaxDurationReached) {
        setValveState(true, "Van nước (IN1) [TỰ ĐỘNG - Đất khô]");
      } else if (soilMoisture >= 60 && valveState) {
        setValveState(false, "Van nước (IN1) [TỰ ĐỘNG - Đủ ẩm]");
      }
    }

    if (autoControlMode && !isNode2()) {
      if (dhtValid && airTemp > 30.0 && !fanState) {
        fanState = true;
        setRelay(RELAY_FAN_PIN, true, "Quạt làm mát (IN2) [TỰ ĐỘNG - Trên 30 độ]");
      } else if (dhtValid && airTemp < 28.0 && fanState) {
        fanState = false;
        setRelay(RELAY_FAN_PIN, false, "Quạt làm mát (IN2) [TỰ ĐỘNG - Dưới 28 độ]");
      }
    }

    // In ra Serial Monitor
    Serial.println(F("--------------------------------------------------------"));
    if (dhtValid) {
      Serial.print(F("[DHT22]  Nhiệt độ: "));
      Serial.print(airTemp, 1);
      Serial.print(F(" °C  |  Độ ẩm KK: "));
      Serial.print(airHumidity, 1);
      Serial.println(F(" %"));
    } else {
      Serial.println(F("[DHT22]  LỖI: Chưa đọc được dữ liệu (NaN)! Kiểm tra dây D27 & Nguồn 3.3V"));
    }

    if (isNode2() && ENABLE_SOIL_SENSOR) {
      Serial.print(F("[HW-390] Raw ADC: "));
      Serial.print(soilRaw);
      Serial.print(F(" | Độ ẩm đất: "));
      Serial.print(soilMoisture);
      Serial.println(F(" %"));
    }

    if (isNode2()) {
      Serial.print(F("[RELAY] Van(IN1): "));
      Serial.println(valveState ? F("BẬT") : F("TẮT"));
    } else {
      Serial.print(F("[RELAY] Quạt(IN2): "));
      Serial.print(fanState ? F("BẬT") : F("TẮT"));
      Serial.print(F(" | Đèn(IN3): "));
      Serial.println(lightState ? F("BẬT") : F("TẮT"));
    }
  }

  // 3. Duy trì MQTT & Gửi Telemetry
  if (WiFi.status() == WL_CONNECTED) {
    connectMqtt();
    if (mqtt.connected()) {
      mqtt.loop();

      if (millis() - lastPublishMs >= PUBLISH_INTERVAL_MS) {
        lastPublishMs = millis();
        if (publishTelemetry() && isNode2()) {
          node2Published = true;
          node2PublishedAtMs = millis();
        }
      }
    }
  }

  if (isNode2() && !valveState) {
    bool commandWindowElapsed = node2Published
            && static_cast<uint32_t>(millis() - node2PublishedAtMs) >= NODE2_COMMAND_WINDOW_MS;
    bool wakeLimitElapsed = static_cast<uint32_t>(millis() - node2WakeStartedMs) >= NODE2_WAKE_LIMIT_MS;
    if (commandWindowElapsed || wakeLimitElapsed) {
      mqtt.disconnect();
      WiFi.disconnect(true);
      WiFi.mode(WIFI_OFF);
      esp_sleep_enable_timer_wakeup(static_cast<uint64_t>(NODE2_SLEEP_MS) * 1000ULL);
      esp_deep_sleep_start();
    }
  }

  delay(50);
}
