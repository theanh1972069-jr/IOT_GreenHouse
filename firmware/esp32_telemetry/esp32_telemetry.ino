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
char mqtt_server[40] = "192.168.1.120";
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

// Các chân Relay
#define RELAY_VALVE_PIN  25    // IN1: Van nước
#define RELAY_FAN_PIN    33    // IN2: Quạt gió
#define RELAY_LIGHT_PIN  32    // IN3: Đèn quang hợp

// CẤU HÌNH MỨC KÍCH RELAY (Đã đảo sang Active HIGH để khớp chuẩn BẬT / TẮT trên Web):
// Khi bật trên Web: ESP32 xuất HIGH -> Relay đóng (BẬT)
// Khi tắt trên Web: ESP32 xuất LOW  -> Relay nhả (TẮT)
#define RELAY_ON   HIGH
#define RELAY_OFF  LOW

bool valveState = false;
bool fanState   = false;
bool lightState = false;

// Chế độ tự động điều khiển theo ngưỡng
bool autoControlMode = true;

// Ngưỡng điều khiển (có thể cập nhật động trực tiếp từ Web Dashboard qua MQTT)
float tempThreshold         = 35.0; // Bật quạt khi nhiệt độ > tempThreshold
float tempThresholdOff      = 30.0; // Tắt quạt khi nhiệt độ <= tempThresholdOff
int   soilThreshold         = 30;   // Bật van khi độ ẩm đất < soilThreshold
int   soilThresholdOff      = 60;   // Tắt van khi độ ẩm đất >= soilThresholdOff
float lightTempThreshold    = 20.0; // Bật đèn khi nhiệt độ < lightTempThreshold (mặc định < 20°C)
float lightTempThresholdOff = 22.0; // Tắt đèn khi nhiệt độ >= lightTempThresholdOff (đủ ấm)

// ==============================================================================
// CÔNG SUẤT THỰC TẾ CỦA THIẾT BỊ (Watts = Volt * Ampere):
// - Quạt 12V 0.15A: P = 12 * 0.15 = 1.8 Watts
// - Van điện từ 12V ~0.4A: P = 12 * 0.4 = 4.8 Watts
// - Đèn quang hợp LED 12V ~0.8A: P = 12 * 0.8 = 9.6 Watts
// ==============================================================================
const float RATED_FAN_W   = 1.8; // Quạt 12V 0.15A = 1.8W thực tế
const float RATED_VALVE_W = 4.8; // Van điện từ 12V ~4.8W
const float RATED_LIGHT_W = 9.6; // Đèn LED 12V ~9.6W

// ============================ 4. BIẾN THỜI GIAN & CAN THIỆP THỦ CÔNG =========
// Bỏ qua tự động hóa trong 5 phút (300,000 ms) sau khi người dùng can thiệp thủ công
const uint32_t MANUAL_OVERRIDE_DURATION_MS = 300000UL;
uint32_t valveOverrideUntilMs = 0;
uint32_t fanOverrideUntilMs   = 0;
uint32_t lightOverrideUntilMs = 0;

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

// Khai báo trước các hàm
void publishTelemetry();
void saveOfflineRecord();
void flushOfflineRecords();

// ============================ BỘ NHỚ LƯU TRỮ KHI MẤT MẠNG (OFFLINE BUFFER) ============================
// Chỉ lưu các thông số cảm biến và trạng thái bật tắt thiết bị
struct OfflineRecord {
  float temp;
  float humidity;
  int   soil;
  bool  valve;
  bool  fan;
  bool  light;
};

#define MAX_OFFLINE_RECORDS 60 // Lưu tối đa 60 bản tin (tương đương 10 phút mất kết nối)
OfflineRecord offlineBuffer[MAX_OFFLINE_RECORDS];
int offlineCount = 0;
int offlineHead  = 0;
int offlineTail  = 0;

void saveOfflineRecord() {
  offlineBuffer[offlineHead].temp     = airTemp;
  offlineBuffer[offlineHead].humidity = airHumidity;
  offlineBuffer[offlineHead].soil     = soilMoisture;
  offlineBuffer[offlineHead].valve    = valveState;
  offlineBuffer[offlineHead].fan      = fanState;
  offlineBuffer[offlineHead].light    = lightState;

  offlineHead = (offlineHead + 1) % MAX_OFFLINE_RECORDS;
  if (offlineCount < MAX_OFFLINE_RECORDS) {
    offlineCount++;
  } else {
    offlineTail = (offlineTail + 1) % MAX_OFFLINE_RECORDS; // Ghi đè bản ghi cũ nhất khi đầy
  }

  Serial.print(F("[OFFLINE] Mất mạng! Đã lưu trạng thái cảm biến & thiết bị vào RAM. Đang chờ gửi: "));
  Serial.print(offlineCount);
  Serial.println(F(" bản ghi."));
}

void flushOfflineRecords() {
  if (offlineCount == 0 || !mqtt.connected()) return;

  Serial.print(F("\n[KẾT NỐI LẠI] Đang gửi bù "));
  Serial.print(offlineCount);
  Serial.println(F(" bản ghi cảm biến & thiết bị đã lưu trong lúc mất mạng lên server..."));

  while (offlineCount > 0 && mqtt.connected()) {
    OfflineRecord rec = offlineBuffer[offlineTail];
    offlineTail = (offlineTail + 1) % MAX_OFFLINE_RECORDS;
    offlineCount--;

    char jsonBuffer[384];
    snprintf(jsonBuffer, sizeof(jsonBuffer),
             "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d,"
             "\"valve\":%d,\"fan\":%d,\"light\":%d,\"buffered\":1}",
             rec.temp, rec.humidity, rec.soil,
             rec.valve ? 1 : 0, rec.fan ? 1 : 0, rec.light ? 1 : 0);

    mqtt.publish(topicTelemetry.c_str(), jsonBuffer);
    delay(40); // Độ trễ ngắn để TCP gửi đi mượt mà
  }

  Serial.println(F("=> Đã gửi bù toàn bộ dữ liệu offline lên server thành công!\n"));
}

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

  bool stateChanged = false;

  if (message.indexOf("\"valve\":1") >= 0) {
    valveState = true;
    valveOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Bật van nước. Tạm dừng tự động hóa van 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  } else if (message.indexOf("\"valve\":0") >= 0) {
    valveState = false;
    valveOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Tắt van nước. Tạm dừng tự động hóa van 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  }

  if (message.indexOf("\"fan\":1") >= 0) {
    fanState = true;
    fanOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_FAN_PIN, true, "Quạt gió (IN2) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Bật quạt gió. Tạm dừng tự động hóa quạt 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  } else if (message.indexOf("\"fan\":0") >= 0) {
    fanState = false;
    fanOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_FAN_PIN, false, "Quạt gió (IN2) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Tắt quạt gió. Tạm dừng tự động hóa quạt 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  }

  if (message.indexOf("\"light\":1") >= 0) {
    lightState = true;
    lightOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Bật đèn quang hợp. Tạm dừng tự động hóa đèn 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  } else if (message.indexOf("\"light\":0") >= 0) {
    lightState = false;
    lightOverrideUntilMs = millis() + MANUAL_OVERRIDE_DURATION_MS;
    setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Tắt đèn quang hợp. Tạm dừng tự động hóa đèn 5 phút. Cảm biến vẫn đo bình thường."));
    stateChanged = true;
  }

  if (message.indexOf("\"auto\":1") >= 0) {
    autoControlMode = true;
    Serial.println(F("[CHẾ ĐỘ] Bật tự động điều khiển theo ngưỡng."));
    stateChanged = true;
  } else if (message.indexOf("\"auto\":0") >= 0) {
    autoControlMode = false;
    Serial.println(F("[CHẾ ĐỘ] Tắt tự động (chuyển sang điều khiển thủ công)."));
    stateChanged = true;
  }

  // Nhận ngưỡng nhiệt độ cập nhật trực tiếp từ Web
  int tempIdx = message.indexOf("\"tempThreshold\":");
  if (tempIdx >= 0) {
    float newTemp = message.substring(tempIdx + 16).toFloat();
    if (newTemp >= 10.0 && newTemp <= 70.0) {
      tempThreshold = newTemp;
      tempThresholdOff = max(10.0f, tempThreshold - 5.0f);
      Serial.print(F("[CẤU HÌNH WEB] Đã nhận ngưỡng nhiệt độ bật quạt: > "));
      Serial.print(tempThreshold, 1);
      Serial.println(F(" °C"));
      stateChanged = true;
    }
  }

  // Nhận ngưỡng độ ẩm đất cập nhật trực tiếp từ Web
  int soilIdx = message.indexOf("\"soilThreshold\":");
  if (soilIdx >= 0) {
    int newSoil = message.substring(soilIdx + 16).toInt();
    if (newSoil >= 0 && newSoil <= 100) {
      soilThreshold = newSoil;
      soilThresholdOff = min(100, soilThreshold + 25);
      Serial.print(F("[CẤU HÌNH WEB] Đã nhận ngưỡng độ ẩm đất bật tưới: < "));
      Serial.print(soilThreshold);
      Serial.println(F(" %"));
      stateChanged = true;
    }
  }

  // Nhận ngưỡng nhiệt độ bật đèn quang hợp cập nhật trực tiếp từ Web
  int lightTempIdx = message.indexOf("\"lightTempThreshold\":");
  if (lightTempIdx >= 0) {
    float newLightTemp = message.substring(lightTempIdx + 21).toFloat();
    if (newLightTemp >= 5.0 && newLightTemp <= 45.0) {
      lightTempThreshold = newLightTemp;
      lightTempThresholdOff = min(45.0f, lightTempThreshold + 2.0f);
      Serial.print(F("[CẤU HÌNH WEB] Đã nhận ngưỡng nhiệt độ bật đèn quang hợp: < "));
      Serial.print(lightTempThreshold, 1);
      Serial.println(F(" °C"));
      stateChanged = true;
    }
  }

  // Phản hồi gửi cập nhật telemetry ngay lập tức để màn hình web cập nhật trạng thái
  if (stateChanged && mqtt.connected()) {
    publishTelemetry();
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
    mqtt.setBufferSize(1024);
    mqtt.subscribe(topicControl.c_str());
    Serial.print(F("=> Đã lắng nghe topic lệnh: "));
    Serial.println(topicControl);
    // Gửi bù toàn bộ bản ghi cảm biến & thiết bị đã lưu trong lúc mất mạng
    flushOfflineRecords();

    // Bắn ngay 1 bản tin telemetry lên server để web cập nhật tức thì trạng thái online & công suất
    publishTelemetry();
  } else {
    Serial.print(F("=> Kết nối MQTT thất bại! rc="));
    Serial.println(mqtt.state());
  }
}

// ============================ 9. GỬI DỮ LIỆU TELEMETRY =======================
void publishTelemetry() {
  mqtt.setBufferSize(1024);
  char jsonBuffer[1024];
  float valvePower = valveState ? RATED_VALVE_W : 0.0;
  float fanPower   = fanState   ? RATED_FAN_W   : 0.0;
  float lightPower = lightState ? RATED_LIGHT_W : 0.0;
  float totalPower = valvePower + fanPower + lightPower;

  uint32_t valveRemSec = (millis() < valveOverrideUntilMs) ? ((valveOverrideUntilMs - millis()) / 1000) : 0;
  uint32_t fanRemSec   = (millis() < fanOverrideUntilMs)   ? ((fanOverrideUntilMs - millis()) / 1000) : 0;
  uint32_t lightRemSec = (millis() < lightOverrideUntilMs) ? ((lightOverrideUntilMs - millis()) / 1000) : 0;

  snprintf(jsonBuffer, sizeof(jsonBuffer),
           "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d,\"auto\":%d,"
           "\"tempThreshold\":%.1f,\"soilThreshold\":%d,\"lightTempThreshold\":%.1f,\"totalPowerW\":%.1f,"
           "\"valve\":%d,\"fan\":%d,\"light\":%d,"
           "\"valveOverrideSec\":%u,\"fanOverrideSec\":%u,\"lightOverrideSec\":%u,"
           "\"actuators\":["
             "{\"id\":\"valve\",\"name\":\"Van tưới nước (IN1)\",\"pin\":\"GPIO 25 (IN1)\",\"type\":\"VALVE\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f},"
             "{\"id\":\"fan\",\"name\":\"Quạt thông gió (IN2)\",\"pin\":\"GPIO 33 (IN2)\",\"type\":\"FAN\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f},"
             "{\"id\":\"light\",\"name\":\"Đèn quang hợp (IN3)\",\"pin\":\"GPIO 32 (IN3)\",\"type\":\"LIGHT\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f}"
           "]}",
           airTemp, airHumidity, soilMoisture, autoControlMode ? 1 : 0,
           tempThreshold, soilThreshold, lightTempThreshold, totalPower,
           valveState ? 1 : 0, fanState ? 1 : 0, lightState ? 1 : 0,
           valveRemSec, fanRemSec, lightRemSec,
           valveState ? 1 : 0, valvePower, RATED_VALVE_W,
           fanState ? 1 : 0, fanPower, RATED_FAN_W,
           lightState ? 1 : 0, lightPower, RATED_LIGHT_W);

  if (mqtt.publish(topicTelemetry.c_str(), jsonBuffer)) {
    Serial.print(F(">> [MQTT ĐÃ GỬI THÀNH CÔNG]: "));
    Serial.println(jsonBuffer);
  } else {
    Serial.print(F(">> [LỖI GỬI MQTT] rc="));
    Serial.print(mqtt.state());
    Serial.print(F(" | Độ dài tin: "));
    Serial.println(strlen(jsonBuffer));
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
  String savedServer = preferences.getString("mqtt_server", "192.168.1.120");
  // Tự động sửa lại nếu Flash đang lưu IP cũ .220
  if (savedServer == "192.168.1.220" || savedServer.length() == 0) {
    savedServer = "192.168.1.120";
    preferences.putString("mqtt_server", "192.168.1.120");
    Serial.println(F("[CẤU HÌNH] Đã tự động cập nhật IP MQTT Server thành: 192.168.1.120"));
  }
  String savedPort   = preferences.getString("mqtt_port", "1883");
  String savedDevId  = preferences.getString("device_id", "esp32-01");

  savedServer.toCharArray(mqtt_server, sizeof(mqtt_server));
  savedPort.toCharArray(mqtt_port_str, sizeof(mqtt_port_str));
  savedDevId.toCharArray(device_id, sizeof(device_id));

  Serial.println(F("[CẤU HÌNH HIỆN TẠI TỪ FLASH]:"));
  Serial.print(F("  - MQTT Server : ")); Serial.println(mqtt_server);
  Serial.print(F("  - MQTT Port   : ")); Serial.println(mqtt_port_str);
  Serial.print(F("  - Device ID   : ")); Serial.println(device_id);

  // Bật chế độ tự động ghi nhớ và tự động kết nối lại Wi-Fi ngầm của phần cứng ESP32
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(true);

  // Đăng ký sự kiện Wi-Fi để tự động kết nối lại tức thì khi mất mạng
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    Serial.println(F("\n[WI-FI] Bị ngắt kết nối! ESP32 đang tự động kết nối lại..."));
    WiFi.reconnect();
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_DISCONNECTED);

  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    Serial.print(F("\n[WI-FI] ĐÃ KẾT NỐI THÀNH CÔNG! IP của ESP32: "));
    Serial.println(WiFi.localIP());
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_GOT_IP);

  // 1. Thử kết nối trực tiếp vào Wi-Fi đã lưu trong Flash (tránh bị kẹt trong AP captive portal)
  if (WiFi.SSID().length() > 0) {
    Serial.print(F("\n[KẾT NỐI MẠNG] Tìm thấy Wi-Fi đã lưu: "));
    Serial.println(WiFi.SSID());
    Serial.print(F("Đang kết nối..."));
    WiFi.begin();

    uint32_t startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 15000) {
      delay(500);
      Serial.print(F("."));
    }
    Serial.println();
  }

  // 2. Nếu chưa kết nối được (lần đầu dùng hoặc đổi router) -> Mở Web Captive Portal
  if (WiFi.status() != WL_CONNECTED) {
    WiFiManager wm;
    wm.setSaveConfigCallback(saveConfigCallback);
    wm.setConfigPortalTimeout(90); // Chờ tối đa 90 giây trong trang web setup
    wm.setConnectTimeout(25);

    WiFiManagerParameter custom_mqtt_server("server", "IP Server Máy Tính (MQTT)", mqtt_server, 40);
    WiFiManagerParameter custom_mqtt_port("port", "Cổng MQTT (mặc định 1883)", mqtt_port_str, 6);
    WiFiManagerParameter custom_device_id("devid", "Device ID (trùng với Web)", device_id, 32);

    wm.addParameter(&custom_mqtt_server);
    wm.addParameter(&custom_mqtt_port);
    wm.addParameter(&custom_device_id);

    Serial.println(F("\n[KẾT NỐI MẠNG] Đang mở Web Portal: 'GreenHouse_ESP32_Setup' (Mật khẩu: 12345678)"));
    Serial.println(F("=> Hãy kết nối vào đó và mở trình duyệt gõ: 192.168.4.1 để cấu hình!"));

    bool res = wm.autoConnect("GreenHouse_ESP32_Setup", "12345678");

    if (!res) {
      Serial.println(F("=> Hết thời gian chờ Web Portal, tiếp tục thử kết nối lại Wi-Fi..."));
    }

    if (shouldSaveConfig) {
      strcpy(mqtt_server, custom_mqtt_server.getValue());
      strcpy(mqtt_port_str, custom_mqtt_port.getValue());
      strcpy(device_id, custom_device_id.getValue());

      preferences.putString("mqtt_server", mqtt_server);
      preferences.putString("mqtt_port", mqtt_port_str);
      preferences.putString("device_id", device_id);

      Serial.println(F("=> Đã lưu thành công cấu hình mới vào bộ nhớ Flash!"));
    }
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
  mqtt.setBufferSize(1024);

  Serial.println(F("[HỆ THỐNG] Đã sẵn sàng hoạt động."));
}

// ============================ 11. LOOP =======================================
void loop() {
  // 1. Kiểm tra nút BOOT: Chỉ reset khi người dùng NHẤN GIỮ LIÊN TỤC >= 3 GIÂY (chống nhiễu điện)
  if (digitalRead(TRIGGER_PIN) == LOW) {
    uint32_t pressStart = millis();
    while (digitalRead(TRIGGER_PIN) == LOW) {
      delay(50);
      if (millis() - pressStart >= 3000) {
        Serial.println(F("\n[RESET] Đã giữ nút BOOT đủ 3 giây! Xóa cấu hình Wi-Fi và phát lại Web Portal..."));
        WiFiManager wmReset;
        wmReset.resetSettings();
        delay(500);
        ESP.restart();
      }
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

    // Tự động kích Relay theo ngưỡng (chỉ can thiệp khi KHÔNG trong thời gian override thủ công 5 phút)
    if (autoControlMode) {
      // 1. Tự động van nước (bỏ qua nếu đang trong 5 phút can thiệp thủ công)
      if (valveOverrideUntilMs != 0 && millis() >= valveOverrideUntilMs) {
        valveOverrideUntilMs = 0; // Đã hết 5 phút can thiệp thủ công
        Serial.println(F("[TỰ ĐỘNG] Hết 5 phút can thiệp thủ công của Van nước. Trở lại chế độ tự động theo ngưỡng."));
      }

      if (valveOverrideUntilMs == 0) {
        if (soilMoisture < soilThreshold && !valveState) {
          valveState = true;
          setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [TỰ ĐỘNG - Đất khô]");
        } else if (soilMoisture >= soilThresholdOff && valveState) {
          valveState = false;
          setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [TỰ ĐỘNG - Đủ ẩm]");
        }
      } else {
        uint32_t remSec = (valveOverrideUntilMs > millis()) ? ((valveOverrideUntilMs - millis()) / 1000) : 0;
        Serial.print(F("[KHÓA TỰ ĐỘNG] Van nước đang được giữ thủ công, còn "));
        Serial.print(remSec);
        Serial.println(F(" giây trước khi tự động kích hoạt lại."));
      }

      // 2. Tự động quạt làm mát (bỏ qua nếu đang trong 5 phút can thiệp thủ công)
      if (fanOverrideUntilMs != 0 && millis() >= fanOverrideUntilMs) {
        fanOverrideUntilMs = 0; // Đã hết 5 phút can thiệp thủ công
        Serial.println(F("[TỰ ĐỘNG] Hết 5 phút can thiệp thủ công của Quạt gió. Trở lại chế độ tự động theo ngưỡng."));
      }

      if (fanOverrideUntilMs == 0) {
        if (dhtValid && airTemp > tempThreshold && !fanState) {
          fanState = true;
          setRelay(RELAY_FAN_PIN, true, "Quạt làm mát (IN2) [TỰ ĐỘNG - Quá nhiệt]");
        } else if (dhtValid && airTemp <= tempThresholdOff && fanState) {
          fanState = false;
          setRelay(RELAY_FAN_PIN, false, "Quạt làm mát (IN2) [TỰ ĐỘNG - Mát]");
        }
      } else {
        uint32_t remSec = (fanOverrideUntilMs > millis()) ? ((fanOverrideUntilMs - millis()) / 1000) : 0;
        Serial.print(F("[KHÓA TỰ ĐỘNG] Quạt gió đang được giữ thủ công, còn "));
        Serial.print(remSec);
        Serial.println(F(" giây trước khi tự động kích hoạt lại."));
      }

      // 3. Tự động đèn quang hợp (bật khi nhiệt độ < lightTempThreshold, bỏ qua nếu đang trong 5 phút can thiệp thủ công)
      if (lightOverrideUntilMs != 0 && millis() >= lightOverrideUntilMs) {
        lightOverrideUntilMs = 0; // Đã hết 5 phút can thiệp thủ công
        Serial.println(F("[TỰ ĐỘNG] Hết 5 phút can thiệp thủ công của Đèn quang hợp. Trở lại chế độ tự động theo ngưỡng."));
      }

      if (lightOverrideUntilMs == 0) {
        if (dhtValid && airTemp < lightTempThreshold && !lightState) {
          lightState = true;
          setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3) [TỰ ĐỘNG - Nhiệt độ thấp]");
        } else if (dhtValid && airTemp >= lightTempThresholdOff && lightState) {
          lightState = false;
          setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3) [TỰ ĐỘNG - Đủ ấm]");
        }
      } else {
        uint32_t remSec = (lightOverrideUntilMs > millis()) ? ((lightOverrideUntilMs - millis()) / 1000) : 0;
        Serial.print(F("[KHÓA TỰ ĐỘNG] Đèn quang hợp đang được giữ thủ công, còn "));
        Serial.print(remSec);
        Serial.println(F(" giây trước khi tự động kích hoạt lại."));
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

  // 3. Quản lý chu kỳ gửi Telemetry hoặc lưu Offline khi mất mạng
  bool isOnline = (WiFi.status() == WL_CONNECTED) && mqtt.connected();
  if (millis() - lastPublishMs >= PUBLISH_INTERVAL_MS) {
    lastPublishMs = millis();

    if (!isOnline) {
      // MẤT MẠNG: Tự động lưu các thông số cảm biến và trạng thái bật/tắt thiết bị vào RAM
      saveOfflineRecord();
    } else {
      // CÓ MẠNG: Gửi bù các bản ghi đã lưu trong lúc mất mạng trước, sau đó gửi bản tin hiện tại
      flushOfflineRecords();
      publishTelemetry();
    }
  }

  // 4. DUY TRÌ & KẾT NỐI LẠI WI-FI (KHÔNG GỌI WiFi.disconnect() tránh ngắt quãng bắt tay DHCP)
  static uint32_t lastWifiRetryMs = 0;
  static uint32_t disconnectedStartMs = 0;

  if (WiFi.status() != WL_CONNECTED) {
    if (disconnectedStartMs == 0) disconnectedStartMs = millis();

    if (millis() - lastWifiRetryMs >= 10000) {
      lastWifiRetryMs = millis();
      Serial.println(F("[MẠNG] Wi-Fi chưa sẵn sàng, đang thử kết nối lại..."));
      
      // Nếu mất kết nối quá 30 giây, nạp lại profile Wi-Fi từ Flash NVS
      if (millis() - disconnectedStartMs >= 30000) {
        Serial.println(F("[MẠNG] Làm mới cấu hình Wi-Fi từ bộ nhớ Flash..."));
        WiFi.begin();
      } else {
        WiFi.reconnect();
      }
    }
    delay(50);
    return; // Đợi Wi-Fi có IP xong mới xử lý MQTT
  } else {
    disconnectedStartMs = 0;
  }

  // 5. Duy trì MQTT
  connectMqtt();
  if (mqtt.connected()) {
    mqtt.loop();
  }

  delay(50);
}
