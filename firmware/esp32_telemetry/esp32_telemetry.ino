/*
 * ==============================================================================
 * DỰ ÁN: IOT GREENHOUSE (Nhà kính thông minh)
 * FIRMWARE: ESP32 Telemetry & Actuator Controller (Tích hợp Web WiFiManager)
 * ==============================================================================
 *
 * TÍNH NĂNG ĐẶC BIỆT (KHÔNG CẦN SỬA CODE KHI ĐỔI WI-FI):
 * ------------------------------------------------------------------------------
 * - Khi bật nguồn, nếu chưa có Wi-Fi đã lưu (hoặc không kết nối được), ESP32
 *   sẽ tự động phát ra một mạng Wi-Fi tên: "GreenHouse_ESP32_Setup".
 * - Bạn chỉ cần dùng điện thoại/máy tính kết nối vào mạng đó, trang web cấu hình
 *   (192.168.4.1) sẽ tự động hiện lên để bạn:
 *      1. Chọn tên Wi-Fi nhà bạn và gõ mật khẩu.
 *      2. Nhập IP Server MQTT (mặc định đã để sẵn: 192.168.1.220).
 *      3. Nhập Device ID (mặc định đã để sẵn: esp32-01).
 *   Bấm [SAVE], ESP32 sẽ lưu vĩnh viễn vào bộ nhớ Flash và kết nối mạng!
 *
 * - NÚT RESET CẤU HÌNH (NÚT BOOT TRÊN ESP32 - GPIO 0):
 *   Nếu muốn đổi sang Wi-Fi khác: Hãy bấm giữ nút BOOT trên board khoảng 3 giây,
 *   ESP32 sẽ xóa cấu hình cũ và phát lại Wi-Fi Setup để bạn nhập lại.
 *
 * SƠ ĐỒ ĐẤU DÂY (ĐÃ CHỐT):
 * ------------------------------------------------------------------------------
 * 1. NGUỒN:
 *    - ESP32: Cấp qua cổng USB máy tính
 *    - Chân VIN (5V) ESP32 -> DC+ Relay (chân phải nhất)
 *    - 1 chân GND ESP32    -> Rail Xanh (Breadboard GND)
 *    - 1 chân 3V3 ESP32    -> Rail Đỏ (Breadboard 3.3V)
 *    - DC- Relay           -> Rail Xanh (GND chung)
 *
 * 2. CẢM BIẾN:
 *    - DHT22 (Nhiệt độ & Độ ẩm không khí):
 *        OUT (Data) -> D27 (GPIO 27)
 *        + (VCC)    -> Rail Đỏ (3.3V)
 *        - (GND)    -> Rail Xanh (GND)
 *    - HW-390 (Cảm biến độ ẩm đất điện dung):
 *        AOUT (Data)-> D35 (GPIO 35, ADC1 - an toàn tuyệt đối khi bật Wi-Fi)
 *        VCC        -> Rail Đỏ (3.3V)
 *        GND        -> Rail Xanh (GND)
 *
 * 3. MODULE RELAY 4 KÊNH (Active LOW: LOW = BẬT, HIGH = TẮT):
 *    - D25 -> IN1 (Van nước)   -- chân thứ 4 từ trái
 *    - D33 -> IN2 (Quạt gió)    -- chân thứ 3 từ trái
 *    - D32 -> IN3 (Đèn quang)   -- chân thứ 2 từ trái
 *    - IN4 -> Bỏ trống
 *    * Quy tắc: digitalWrite(HIGH) TRƯỚC pinMode(OUTPUT) để chống chớp relay lúc boot!
 * ==============================================================================
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <WiFiManager.h>   // Thư viện tạo Web Captive Portal
#include <Preferences.h>   // Lưu cấu hình MQTT Server, Device ID vào Flash ESP32

// ============================ 1. NÚT RESET CẤU HÌNH ==========================
#define TRIGGER_PIN  0     // Nút BOOT trên ESP32 (GPIO 0)

// ============================ 2. BỘ NHỚ LƯU CẤU HÌNH =========================
Preferences preferences;

// Giá trị mặc định (sẽ được ghi đè khi bạn nhập trên Web Portal)
char mqtt_server[40] = "192.168.1.220";
char mqtt_port_str[6] = "1883";
char device_id[32]    = "esp32-01";

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
bool fanState   = false;
bool lightState = false;

// Chế độ tự động điều khiển theo ngưỡng
bool autoControlMode = true;

// ============================ 4. BIẾN THỜI GIAN ==============================
static const uint32_t READ_INTERVAL_MS    = 2000;   // Đọc cảm biến mỗi 2 giây
static const uint32_t PUBLISH_INTERVAL_MS = 10000;  // Gửi lên server mỗi 10 giây
static const uint32_t MQTT_RETRY_MS       = 5000;

WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);

static uint32_t lastReadMs       = 0;
static uint32_t lastPublishMs    = 0;
static uint32_t lastMqttRetryMs  = 0;

float airTemp      = 0.0;
float airHumidity  = 0.0;
int   soilRaw      = 0;
int   soilMoisture = 0;

String topicTelemetry = "";
String topicControl   = "";

// ============================ 5. ĐIỀU KHIỂN RELAY ============================
void setRelay(int pin, bool state, const char* name) {
  digitalWrite(pin, state ? RELAY_ON : RELAY_OFF);
  Serial.print(F("[RELAY] "));
  Serial.print(name);
  Serial.println(state ? F(" -> BẬT (ON) [Đèn LED Relay SÁNG]") : F(" -> TẮT (OFF) [Đèn LED Relay TẮT]"));
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

  if (message.indexOf("\"valve\":1") >= 0) {
    valveState = true;
    setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1)");
  } else if (message.indexOf("\"valve\":0") >= 0) {
    valveState = false;
    setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1)");
  }

  if (message.indexOf("\"fan\":1") >= 0) {
    fanState = true;
    setRelay(RELAY_FAN_PIN, true, "Quạt gió (IN2)");
  } else if (message.indexOf("\"fan\":0") >= 0) {
    fanState = false;
    setRelay(RELAY_FAN_PIN, false, "Quạt gió (IN2)");
  }

  if (message.indexOf("\"light\":1") >= 0) {
    lightState = true;
    setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3)");
  } else if (message.indexOf("\"light\":0") >= 0) {
    lightState = false;
    setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3)");
  }

  if (message.indexOf("\"auto\":1") >= 0) {
    autoControlMode = true;
    Serial.println(F("[CHẾ ĐỘ] Bật tự động điều khiển theo ngưỡng."));
  } else if (message.indexOf("\"auto\":0") >= 0) {
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
    mqtt.subscribe(topicControl.c_str());
    Serial.print(F("=> Đã lắng nghe topic lệnh: "));
    Serial.println(topicControl);
  } else {
    Serial.print(F("=> Kết nối MQTT thất bại! rc="));
    Serial.println(mqtt.state());
  }
}

// ============================ 9. GỬI DỮ LIỆU TELEMETRY =======================
void publishTelemetry() {
  char jsonBuffer[160];
  snprintf(jsonBuffer, sizeof(jsonBuffer),
           "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d}",
           airTemp, airHumidity, soilMoisture);

  if (mqtt.publish(topicTelemetry.c_str(), jsonBuffer)) {
    Serial.print(F(">> [MQTT ĐÃ GỬI THÀNH CÔNG]: "));
    Serial.println(jsonBuffer);
  } else {
    Serial.print(F(">> [LỖI GỬI MQTT] rc="));
    Serial.println(mqtt.state());
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
  pinMode(SOIL_PIN, INPUT);

  // Đọc cấu hình MQTT đã lưu trong bộ nhớ Flash (NVS)
  preferences.begin("greenhouse", false);
  String savedServer = preferences.getString("mqtt_server", "192.168.1.220");
  String savedPort   = preferences.getString("mqtt_port", "1883");
  String savedDevId  = preferences.getString("device_id", "esp32-01");

  savedServer.toCharArray(mqtt_server, sizeof(mqtt_server));
  savedPort.toCharArray(mqtt_port_str, sizeof(mqtt_port_str));
  savedDevId.toCharArray(device_id, sizeof(device_id));

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

  wm.addParameter(&custom_mqtt_server);
  wm.addParameter(&custom_mqtt_port);
  wm.addParameter(&custom_device_id);

  Serial.println(F("\n[KẾT NỐI MẠNG] Đang kết nối Wi-Fi..."));
  Serial.println(F("=> NẾU CHƯA CÓ WI-FI, ESP32 SẼ TỰ ĐỘNG PHÁT WI-FI: 'GreenHouse_ESP32_Setup'"));
  Serial.println(F("=> Hãy kết nối vào đó và mở trình duyệt gõ: 192.168.4.1 để cấu hình!"));

  // Tự động kết nối, nếu không được sẽ phát Wi-Fi AP tên "GreenHouse_ESP32_Setup" (mật khẩu: 12345678)
  bool res = wm.autoConnect("GreenHouse_ESP32_Setup", "12345678");

  if (!res) {
    Serial.println(F("=> Hết thời gian chờ cấu hình Wi-Fi hoặc kết nối thất bại! Đang khởi động lại..."));
    delay(2000);
    ESP.restart();
  }

  // Nếu người dùng đã nhập thông số mới trên trang Web -> Lưu vào Flash
  if (shouldSaveConfig) {
    strcpy(mqtt_server, custom_mqtt_server.getValue());
    strcpy(mqtt_port_str, custom_mqtt_port.getValue());
    strcpy(device_id, custom_device_id.getValue());

    preferences.putString("mqtt_server", mqtt_server);
    preferences.putString("mqtt_port", mqtt_port_str);
    preferences.putString("device_id", device_id);

    Serial.println(F("=> Đã lưu thành công cấu hình mới vào bộ nhớ Flash!"));
  }
  preferences.end();

  // Khởi tạo topic MQTT theo Device ID
  topicTelemetry = "greenhouse/" + String(device_id) + "/telemetry";
  topicControl   = "greenhouse/" + String(device_id) + "/control";

  Serial.println(F("\n=> WI-FI ĐÃ KẾT NỐI THÀNH CÔNG!"));
  Serial.print(F("=> IP của ESP32: "));
  Serial.println(WiFi.localIP());

  // Cấu hình MQTT Client
  WiFi.setSleep(false);
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

  // 2. Đọc cảm biến định kỳ mỗi 2 giây
  if (millis() - lastReadMs >= READ_INTERVAL_MS) {
    lastReadMs = millis();

    // Đọc DHT22
    float h = dht.readHumidity();
    float t = dht.readTemperature();
    bool dhtValid = !isnan(h) && !isnan(t);
    if (dhtValid) {
      airHumidity = h;
      airTemp     = t;
    }

    // Đọc độ ẩm đất HW-390 (D35)
    soilRaw = analogRead(SOIL_PIN);
    float soilVoltage = soilRaw * (3.3 / 4095.0);
    soilMoisture = map(soilRaw, AIR_VALUE, WATER_VALUE, 0, 100);
    soilMoisture = constrain(soilMoisture, 0, 100);

    // Tự động kích Relay theo ngưỡng (chỉ kích khi có số liệu hợp lệ)
    if (autoControlMode) {
      if (soilMoisture < 30 && !valveState) {
        valveState = true;
        setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [TỰ ĐỘNG - Đất khô]");
      } else if (soilMoisture >= 60 && valveState) {
        valveState = false;
        setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [TỰ ĐỘNG - Đủ ẩm]");
      }

      if (dhtValid && airTemp > 35.0 && !fanState) {
        fanState = true;
        setRelay(RELAY_FAN_PIN, true, "Quạt làm mát (IN2) [TỰ ĐỘNG - Quá nhiệt]");
      } else if (dhtValid && airTemp <= 30.0 && fanState) {
        fanState = false;
        setRelay(RELAY_FAN_PIN, false, "Quạt làm mát (IN2) [TỰ ĐỘNG - Nhiệt độ mát]");
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

    Serial.print(F("[HW-390] Raw ADC: "));
    Serial.print(soilRaw);
    Serial.print(F(" ("));
    Serial.print(soilVoltage, 2);
    Serial.print(F("V)  |  Độ ẩm đất: "));
    Serial.print(soilMoisture);
    Serial.println(F(" %"));

    Serial.print(F("[RELAY]  Van(IN1): "));
    Serial.print(valveState ? F("BẬT") : F("TẮT"));
    Serial.print(F(" | Quạt(IN2): "));
    Serial.print(fanState ? F("BẬT") : F("TẮT"));
    Serial.print(F(" | Đèn(IN3): "));
    Serial.println(lightState ? F("BẬT") : F("TẮT"));
  }

  // 3. Duy trì MQTT & Gửi Telemetry
  if (WiFi.status() == WL_CONNECTED) {
    connectMqtt();
    if (mqtt.connected()) {
      mqtt.loop();

      if (millis() - lastPublishMs >= PUBLISH_INTERVAL_MS) {
        lastPublishMs = millis();
        publishTelemetry();
      }
    }
  }

  delay(50);
}
