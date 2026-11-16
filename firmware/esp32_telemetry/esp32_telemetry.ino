#include <WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <WiFiManager.h>
#include <Preferences.h>
#include <time.h>

// ============================ 1. NÚT RESET CẤU HÌNH ==========================
#define TRIGGER_PIN  0     // Nút BOOT trên ESP32 (GPIO 0)

// ============================ 2. BỘ NHỚ LƯU CẤU HÌNH =========================
Preferences preferences;

// Giá trị mặc định (sẽ được ghi đè khi bạn nhập trên Web Portal)
char mqtt_server[40] = "broker.emqx.io";
char mqtt_port_str[6] = "1883";
char device_id[32]    = "esp32-01";

// Cờ kiểm tra có lưu cấu hình mới từ web không
bool shouldSaveConfig = false;

// ============================ 3. ĐỊNH NGHĨA CHÂN & THIẾT BỊ ==================
#define DHTPIN       26
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

bool isRelayActiveLow = false;

inline int getRelayLevel(bool state) {
  if (isRelayActiveLow) {
    return state ? LOW : HIGH;
  } else {
    return state ? HIGH : LOW;
  }
}

#define RELAY_ON   (isRelayActiveLow ? LOW : HIGH)
#define RELAY_OFF  (isRelayActiveLow ? HIGH : LOW)

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

// Khung giờ bật/tắt đèn quang hợp tự động (mặc định 19:00 -> 07:00, lưu vào Flash NVS)
int lightOnHour  = 19;
int lightOnMin   = 0;
int lightOffHour = 7;
int lightOffMin  = 0;

const float RATED_FAN_W   = 1.8;
const float RATED_VALVE_W = 4.8;
const float RATED_LIGHT_W = 9.6;

// ============================ 4. BIẾN THỜI GIAN ĐO ĐẠC ==========================
// Chế độ thủ công đóng vai trò khóa tự động cho đến khi người dùng chuyển lại chế độ tự động.

// Chu kỳ đo và gửi Telemetry:
// Mỗi 2 giây đo 1 lần. Nếu biến động lớn (nhiệt độ >= 1.0°C, độ ẩm KK >= 5%, độ ẩm đất >= 5%, hoặc đổi relay) -> gửi luôn!
// Nếu không thay đổi nhiều thì định kỳ 10 giây gửi 1 lần (Heartbeat).
static const uint32_t MEASURE_INTERVAL_MS   = 2000;   // Mỗi 2 giây đo cảm biến 1 lần
static const uint32_t HEARTBEAT_INTERVAL_MS = 10000;  // Nếu không đổi nhiều thì 10 giây gửi 1 lần
static const uint32_t MQTT_RETRY_MS         = 5000;

// Ngưỡng chênh lệch để kích hoạt gửi ngay tức thì
static const float    DELTA_TEMP_THRESHOLD  = 1.0f;   // Chênh lệch nhiệt độ >= 1.0 °C -> gửi luôn
static const float    DELTA_HUMI_THRESHOLD  = 5.0f;   // Chênh lệch độ ẩm KK >= 5.0 % -> gửi luôn
static const int      DELTA_SOIL_THRESHOLD  = 5;      // Chênh lệch độ ẩm đất >= 5 % -> gửi luôn

WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);

static uint32_t lastMeasureMs    = 0;
static uint32_t lastPublishMs    = 0;
static uint32_t lastMqttRetryMs  = 0;

float airTemp      = 0.0;
float airHumidity  = 0.0;
int   soilRaw      = 0;
int   soilMoisture = 0;

// Lưu trữ giá trị của lần gửi gần nhất để so sánh độ biến động
static float lastSentTemp     = -999.0f;
static float lastSentHumidity = -999.0f;
static int   lastSentSoil     = -999;
static bool  lastSentValve    = false;
static bool  lastSentFan      = false;
static bool  lastSentLight    = false;

String topicTelemetry = "";
String topicControl   = "";

// Khai báo trước các hàm
void measureSensorsAndAutoControl();
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

  // Cập nhật giá trị mốc đã lưu
  lastSentTemp     = airTemp;
  lastSentHumidity = airHumidity;
  lastSentSoil     = soilMoisture;
  lastSentValve    = valveState;
  lastSentFan      = fanState;
  lastSentLight    = lightState;
  lastPublishMs    = millis();

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

// ============================ 5. ĐIỀU KHIỂN & KIỂM TRA CHÂN RELAY ============
void setRelay(int pin, bool state, const char* name) {
  int level = getRelayLevel(state);
  digitalWrite(pin, level);
  int readBack = digitalRead(pin);

  Serial.println(F("--------------------------------------------------------"));
  Serial.print(F("[KIỂM TRA CHÂN] "));
  Serial.print(name);
  Serial.print(F(" (GPIO "));
  Serial.print(pin);
  Serial.println(F("):"));
  Serial.print(F("  -> Lệnh yêu cầu : "));
  Serial.println(state ? F("BẬT (ON)") : F("TẮT (OFF)"));
  Serial.print(F("  -> Xuất mức chân: "));
  Serial.print(level == HIGH ? F("HIGH (3.3V)") : F("LOW (0V)"));
  Serial.print(F(" [Chế độ: "));
  Serial.print(isRelayActiveLow ? F("Active LOW]") : F("Active HIGH]"));
  Serial.print(F(" | Đo thực tế: "));
  Serial.println(readBack == HIGH ? F("HIGH (3.3V)") : F("LOW (0V)"));

  if (readBack == level) {
    Serial.print(F("  => KẾT QUẢ: Chân ESP32 hoạt động chuẩn! "));
    if (state) {
      Serial.println(F("(Relay phải KÊU TẠCH & đèn LED đỏ trên module Relay SÁNG)"));
    } else {
      Serial.println(F("(Relay phải KÊU TẠCH & đèn LED đỏ trên module Relay TẮT)"));
    }
  } else {
    Serial.println(F("  => CẢNH BÁO: Đo lại chân không khớp mức xuất! Kiểm tra dây D32/D33/D25 có bị chập/đứt."));
  }
  Serial.println(F("--------------------------------------------------------"));
}

// Hàm chạy kiểm tra lần lượt từng Relay (Self-Test)
void testRelayPins() {
  Serial.println(F("\n========================================================"));
  Serial.println(F(">>> BẮT ĐẦU QUY TRÌNH KIỂM TRA 3 KÊNH RELAY (PIN TEST) <<<"));
  Serial.println(F("========================================================"));

  int pins[] = { RELAY_VALVE_PIN, RELAY_FAN_PIN, RELAY_LIGHT_PIN };
  const char* names[] = { "Van nước (IN1)", "Quạt gió (IN2)", "Đèn quang hợp (IN3)" };

  for (int i = 0; i < 3; i++) {
    int p = pins[i];
    const char* n = names[i];

    Serial.print(F("\n--- [BƯỚC 1/2] Test BẬT: "));
    Serial.print(n);
    Serial.print(F(" (GPIO "));
    Serial.print(p);
    Serial.println(F(") ---"));
    setRelay(p, true, n);
    Serial.println(F("=> Chờ 2 giây: Hãy quan sát đèn LED trên Relay và nghe tiếng 'tạch' đóng tiếp điểm..."));
    delay(2000);

    Serial.print(F("\n--- [BƯỚC 2/2] Test TẮT: "));
    Serial.print(n);
    Serial.print(F(" (GPIO "));
    Serial.print(p);
    Serial.println(F(") ---"));
    setRelay(p, false, n);
    Serial.println(F("=> Chờ 1 giây: Relay phải nhả 'tạch' và đèn LED trên Relay tắt..."));
    delay(1000);
  }

  Serial.println(F("\n========================================================"));
  Serial.println(F(">>> HOÀN TẤT KIỂM TRA PHẦN CỨNG RELAY! <<<"));
  Serial.println(F("- Nếu đèn LED trên Relay bị NGƯỢC (sáng khi Tắt, tắt khi Bật): Gõ 'invert' để đổi!"));
  Serial.println(F("- Nếu không nghe Relay kêu 'tạch': Kiểm tra nguồn DC+ (5V VIN) và DC- (GND) cấp cho Relay."));
  Serial.println(F("========================================================\n"));
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
    setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Bật van nước."));
    stateChanged = true;
  } else if (message.indexOf("\"valve\":0") >= 0) {
    valveState = false;
    setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Tắt van nước."));
    stateChanged = true;
  }

  if (message.indexOf("\"fan\":1") >= 0) {
    fanState = true;
    setRelay(RELAY_FAN_PIN, true, "Quạt gió (IN2) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Bật quạt gió."));
    stateChanged = true;
  } else if (message.indexOf("\"fan\":0") >= 0) {
    fanState = false;
    setRelay(RELAY_FAN_PIN, false, "Quạt gió (IN2) [THỦ CÔNG]");
    Serial.println(F("[THỦ CÔNG] Tắt quạt gió."));
    stateChanged = true;
  }

  if (message.indexOf("\"light\":1") >= 0) {
    lightState = true;
    setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3) [BẬT]");
    Serial.println(F("[LỆNH] Bật đèn quang hợp (IN3)."));
    stateChanged = true;
  } else if (message.indexOf("\"light\":0") >= 0) {
    lightState = false;
    setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3) [TẮT]");
    Serial.println(F("[LỆNH] Tắt đèn quang hợp (IN3)."));
    stateChanged = true;
  }

  if (message.indexOf("\"auto\":1") >= 0) {
    autoControlMode = true;
    preferences.begin("greenhouse", false);
    preferences.putBool("auto_mode", true);
    preferences.end();
    Serial.println(F("[CHẾ ĐỘ] Bật tự động điều khiển theo ngưỡng (Đã lưu vào Flash NVS)."));
    stateChanged = true;
  } else if (message.indexOf("\"auto\":0") >= 0) {
    autoControlMode = false;
    preferences.begin("greenhouse", false);
    preferences.putBool("auto_mode", false);
    preferences.end();
    Serial.println(F("[CHẾ ĐỘ] Chuyển sang chế độ thủ công (Đã lưu vào Flash NVS). Tự động bị khóa cho đến khi bật lại."));
    stateChanged = true;
  }

  // Nhận ngưỡng nhiệt độ cập nhật trực tiếp từ Web và lưu vào Flash NVS của ESP32
  int tempIdx = message.indexOf("\"tempThreshold\":");
  if (tempIdx >= 0) {
    float newTemp = message.substring(tempIdx + 16).toFloat();
    if (newTemp >= 10.0 && newTemp <= 70.0) {
      tempThreshold = newTemp;
      tempThresholdOff = max(10.0f, tempThreshold - 5.0f);
      preferences.begin("greenhouse", false);
      preferences.putFloat("temp_thresh", tempThreshold);
      preferences.end();
      Serial.print(F("[CẤU HÌNH WEB] Đã lưu ngưỡng nhiệt độ bật quạt vào Flash: > "));
      Serial.print(tempThreshold, 1);
      Serial.println(F(" °C"));
      stateChanged = true;
    }
  }

  // Nhận ngưỡng độ ẩm đất cập nhật trực tiếp từ Web và lưu vào Flash NVS của ESP32
  int soilIdx = message.indexOf("\"soilThreshold\":");
  if (soilIdx >= 0) {
    int newSoil = message.substring(soilIdx + 16).toInt();
    if (newSoil >= 0 && newSoil <= 100) {
      soilThreshold = newSoil;
      soilThresholdOff = min(100, soilThreshold + 25);
      preferences.begin("greenhouse", false);
      preferences.putInt("soil_thresh", soilThreshold);
      preferences.end();
      Serial.print(F("[CẤU HÌNH WEB] Đã lưu ngưỡng độ ẩm đất bật tưới vào Flash: < "));
      Serial.print(soilThreshold);
      Serial.println(F(" %"));
      stateChanged = true;
    }
  }

  // Nhận ngưỡng nhiệt độ bật đèn quang hợp cập nhật trực tiếp từ Web và lưu vào Flash NVS
  int lightTempIdx = message.indexOf("\"lightTempThreshold\":");
  if (lightTempIdx >= 0) {
    float newLightTemp = message.substring(lightTempIdx + 21).toFloat();
    if (newLightTemp >= 5.0 && newLightTemp <= 45.0) {
      lightTempThreshold = newLightTemp;
      lightTempThresholdOff = min(45.0f, lightTempThreshold + 2.0f);
      preferences.begin("greenhouse", false);
      preferences.putFloat("light_thresh", lightTempThreshold);
      preferences.end();
      Serial.print(F("[CẤU HÌNH WEB] Đã lưu ngưỡng nhiệt độ bật đèn vào Flash: < "));
      Serial.print(lightTempThreshold, 1);
      Serial.println(F(" °C"));
      stateChanged = true;
    }
  }

  // Nhận giờ bật đèn từ Web (VD: "lightOn":"19:00")
  int lightOnIdx = message.indexOf("\"lightOn\":\"");
  if (lightOnIdx >= 0) {
    String onStr = message.substring(lightOnIdx + 11, lightOnIdx + 16);
    int colon = onStr.indexOf(':');
    if (colon > 0) {
      lightOnHour = onStr.substring(0, colon).toInt();
      lightOnMin  = onStr.substring(colon + 1).toInt();
      preferences.begin("greenhouse", false);
      preferences.putInt("light_on_h", lightOnHour);
      preferences.putInt("light_on_m", lightOnMin);
      preferences.end();
      Serial.print(F("[LỊCH ĐÈN FLASH] Đã lưu giờ bật đèn: "));
      Serial.print(lightOnHour); Serial.print(F(":")); Serial.println(lightOnMin);
      stateChanged = true;
    }
  }

  // Nhận giờ tắt đèn từ Web (VD: "lightOff":"07:00")
  int lightOffIdx = message.indexOf("\"lightOff\":\"");
  if (lightOffIdx >= 0) {
    String offStr = message.substring(lightOffIdx + 12, lightOffIdx + 17);
    int colon = offStr.indexOf(':');
    if (colon > 0) {
      lightOffHour = offStr.substring(0, colon).toInt();
      lightOffMin  = offStr.substring(colon + 1).toInt();
      preferences.begin("greenhouse", false);
      preferences.putInt("light_off_h", lightOffHour);
      preferences.putInt("light_off_m", lightOffMin);
      preferences.end();
      Serial.print(F("[LỊCH ĐÈN FLASH] Đã lưu giờ tắt đèn: "));
      Serial.print(lightOffHour); Serial.print(F(":")); Serial.println(lightOffMin);
      stateChanged = true;
    }
  }

  // Nhận thời gian Epoch từ máy chủ để đồng bộ giờ tức thì (phòng khi NTP bị chặn)
  int epochIdx = message.indexOf("\"epoch\":");
  if (epochIdx >= 0) {
    long long epochSec = atoll(message.substring(epochIdx + 8).c_str());
    if (epochSec > 1700000000LL) {
      struct timeval tv = { (time_t)epochSec, 0 };
      settimeofday(&tv, NULL);
      Serial.print(F("[ĐỒNG HỒ] Đã đồng bộ giờ từ máy chủ (Epoch): "));
      Serial.println((long)epochSec);
      stateChanged = true;
    }
  }

  // Đánh giá ngay trạng thái đèn sau khi vừa nhận giờ mới nếu đang ở chế độ Tự động
  if (stateChanged && autoControlMode) {
    struct tm ti;
    if (getLocalTime(&ti, 50)) {
      int curMin = ti.tm_hour * 60 + ti.tm_min;
      int onMin  = lightOnHour * 60 + lightOnMin;
      int offMin = lightOffHour * 60 + lightOffMin;
      bool shouldOn = false;
      if (onMin < offMin) {
        shouldOn = (curMin >= onMin && curMin < offMin);
      } else if (onMin > offMin) {
        shouldOn = (curMin >= onMin || curMin < offMin);
      }
      if (shouldOn != lightState) {
        lightState = shouldOn;
        setRelay(RELAY_LIGHT_PIN, lightState, shouldOn ? "Đèn quang hợp (IN3) [LỊCH MỚI - BẬT]" : "Đèn quang hợp (IN3) [LỊCH MỚI - TẮT]");
      }
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

    // Đo cảm biến ngay khi vừa kết nối thành công để có số liệu tươi gửi lên server
    measureSensorsAndAutoControl();
    // Bắn ngay 1 bản tin telemetry lên server để web cập nhật tức thì trạng thái online & công suất
    publishTelemetry();
  } else {
    Serial.print(F("=> Kết nối MQTT thất bại! rc="));
    Serial.println(mqtt.state());
  }
}

// ============================ 9. ĐỌC CẢM BIẾN & TỰ ĐỘNG HÓA KHI GỬI ==========
// KHI NÀO GỬI MỚI ĐO: Hàm này được gọi ngay trước khi gửi Telemetry (hoặc lưu Offline)
void measureSensorsAndAutoControl() {
  // 1. Đọc DHT22
  float h = dht.readHumidity();
  float t = dht.readTemperature();
  bool dhtValid = !isnan(h) && !isnan(t);
  if (dhtValid) {
    airHumidity = h;
    airTemp     = t;
  }

  // 2. Đọc độ ẩm đất HW-390 (D35)
  soilRaw = analogRead(SOIL_PIN);
  float soilVoltage = soilRaw * (3.3 / 4095.0);
  soilMoisture = map(soilRaw, AIR_VALUE, WATER_VALUE, 0, 100);
  soilMoisture = constrain(soilMoisture, 0, 100);

  // 3. Tự động kích Relay theo ngưỡng (chỉ can thiệp khi đang bật chế độ TỰ ĐỘNG)
  if (autoControlMode) {
    // Tự động van nước theo độ ẩm đất
    if (soilMoisture < soilThreshold && !valveState) {
      valveState = true;
      setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [TỰ ĐỘNG - Đất khô]");
    } else if (soilMoisture >= soilThresholdOff && valveState) {
      valveState = false;
      setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [TỰ ĐỘNG - Đủ ẩm]");
    }

    // Tự động quạt làm mát theo nhiệt độ
    if (dhtValid && airTemp > tempThreshold && !fanState) {
      fanState = true;
      setRelay(RELAY_FAN_PIN, true, "Quạt làm mát (IN2) [TỰ ĐỘNG - Quá nhiệt]");
    } else if (dhtValid && airTemp <= tempThresholdOff && fanState) {
      fanState = false;
      setRelay(RELAY_FAN_PIN, false, "Quạt làm mát (IN2) [TỰ ĐỘNG - Mát]");
    }

    // Tự động bật/tắt đèn quang hợp theo khung giờ (chạy độc lập trên ESP32 kể cả khi mất mạng)
    struct tm timeinfo;
    bool hasValidTime = getLocalTime(&timeinfo, 20);
    if (hasValidTime) {
      int curMin = timeinfo.tm_hour * 60 + timeinfo.tm_min;
      int onMin  = lightOnHour * 60 + lightOnMin;
      int offMin = lightOffHour * 60 + lightOffMin;

      bool shouldLightOn = false;
      if (onMin < offMin) {
        // Khung giờ ban ngày (vd: 06:00 -> 18:00)
        shouldLightOn = (curMin >= onMin && curMin < offMin);
      } else if (onMin > offMin) {
        // Khung giờ qua đêm (vd: 19:00 -> 07:00: bật khi curMin >= 19:00 HOẶC curMin < 07:00)
        shouldLightOn = (curMin >= onMin || curMin < offMin);
      }

      if (shouldLightOn && !lightState) {
        lightState = true;
        setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3) [TỰ ĐỘNG - Trong khung giờ]");
      } else if (!shouldLightOn && lightState) {
        lightState = false;
        setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3) [TỰ ĐỘNG - Ngoài khung giờ]");
      }
    } else {
      static uint32_t lastTimeWarn = 0;
      if (millis() - lastTimeWarn >= 30000) {
        lastTimeWarn = millis();
        Serial.println(F("[ĐÈN QUANG HỢP] Đang chờ đồng bộ giờ (cần kết nối Wi-Fi 1 lần lấy NTP hoặc nhận giờ từ Web)..."));
      }
    }
  }

  // 4. In ra Serial Monitor đồng bộ cùng nhịp gửi
  Serial.println(F("--------------------------------------------------------"));
  struct tm ti;
  if (getLocalTime(&ti, 0)) {
    char timeStr[32];
    snprintf(timeStr, sizeof(timeStr), "%02d:%02d:%02d Ngày %02d/%02d/%04d",
             ti.tm_hour, ti.tm_min, ti.tm_sec, ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900);
    Serial.print(F("[ĐỒNG HỒ ESP32] ")); Serial.print(timeStr);
    Serial.print(F(" | Khung giờ đèn: "));
    char schedStr[32];
    snprintf(schedStr, sizeof(schedStr), "%02d:%02d -> %02d:%02d", lightOnHour, lightOnMin, lightOffHour, lightOffMin);
    Serial.print(schedStr);
    Serial.print(F(" | Tự động: "));
    Serial.println(lightState ? F("ĐANG BẬT") : F("ĐANG TẮT"));
  } else {
    Serial.println(F("[ĐỒNG HỒ ESP32] Chưa đồng bộ thời gian (chờ kết nối NTP hoặc Web)"));
  }
  if (dhtValid) {
    Serial.print(F("[DHT22]  Nhiệt độ: "));
    Serial.print(airTemp, 1);
    Serial.print(F(" °C  |  Độ ẩm KK: "));
    Serial.print(airHumidity, 1);
    Serial.println(F(" %"));
  } else {
    Serial.println(F("[DHT22]  LỖI: Chưa đọc được dữ liệu (NaN)! Kiểm tra dây D26 & Nguồn 3.3V"));
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

// ============================ 10. GỬI DỮ LIỆU TELEMETRY =======================
void publishTelemetry() {
  mqtt.setBufferSize(1024);
  char jsonBuffer[1024];
  float valvePower = valveState ? RATED_VALVE_W : 0.0;
  float fanPower   = fanState   ? RATED_FAN_W   : 0.0;
  float lightPower = lightState ? RATED_LIGHT_W : 0.0;
  float totalPower = valvePower + fanPower + lightPower;

  snprintf(jsonBuffer, sizeof(jsonBuffer),
           "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d,\"auto\":%d,"
           "\"tempThreshold\":%.1f,\"soilThreshold\":%d,\"lightTempThreshold\":%.1f,\"totalPowerW\":%.1f,"
           "\"valve\":%d,\"fan\":%d,\"light\":%d,"
           "\"actuators\":["
             "{\"id\":\"valve\",\"name\":\"Van tưới nước (IN1)\",\"pin\":\"GPIO 25 (IN1)\",\"type\":\"VALVE\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f},"
             "{\"id\":\"fan\",\"name\":\"Quạt thông gió (IN2)\",\"pin\":\"GPIO 33 (IN2)\",\"type\":\"FAN\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f},"
             "{\"id\":\"light\",\"name\":\"Đèn quang hợp (IN3)\",\"pin\":\"GPIO 32 (IN3)\",\"type\":\"LIGHT\",\"state\":%d,\"powerW\":%.1f,\"ratedW\":%.1f}"
           "]}",
           airTemp, airHumidity, soilMoisture, autoControlMode ? 1 : 0,
           tempThreshold, soilThreshold, lightTempThreshold, totalPower,
           valveState ? 1 : 0, fanState ? 1 : 0, lightState ? 1 : 0,
           valveState ? 1 : 0, valvePower, RATED_VALVE_W,
           fanState ? 1 : 0, fanPower, RATED_FAN_W,
           lightState ? 1 : 0, lightPower, RATED_LIGHT_W);

  if (mqtt.publish(topicTelemetry.c_str(), jsonBuffer)) {
    Serial.print(F(">> [MQTT ĐÃ GỬI THÀNH CÔNG]: "));
    Serial.println(jsonBuffer);

    // Cập nhật giá trị mốc đã gửi thành công
    lastSentTemp     = airTemp;
    lastSentHumidity = airHumidity;
    lastSentSoil     = soilMoisture;
    lastSentValve    = valveState;
    lastSentFan      = fanState;
    lastSentLight    = lightState;
    lastPublishMs    = millis();
  } else {
    Serial.print(F(">> [LỖI GỬI MQTT] rc="));
    Serial.print(mqtt.state());
    Serial.print(F(" | Độ dài tin: "));
    Serial.println(strlen(jsonBuffer));
  }
}

// ============================ 9. XỬ LÝ LỆNH TEST TỪ SERIAL MONITOR ============
void handleSerialCommands() {
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    String cmdUpper = cmd;
    cmdUpper.toUpperCase();
    if (cmdUpper.length() == 0) return;

    Serial.print(F("\n[LỆNH SERIAL] Đã nhận: "));
    Serial.println(cmd);

    if (cmdUpper == "TEST") {
      testRelayPins();
    } else if (cmdUpper == "LIGHT ON") {
      lightState = true;
      setRelay(RELAY_LIGHT_PIN, true, "Đèn quang hợp (IN3) [SERIAL]");
    } else if (cmdUpper == "LIGHT OFF") {
      lightState = false;
      setRelay(RELAY_LIGHT_PIN, false, "Đèn quang hợp (IN3) [SERIAL]");
    } else if (cmdUpper == "FAN ON") {
      fanState = true;
      setRelay(RELAY_FAN_PIN, true, "Quạt gió (IN2) [SERIAL]");
    } else if (cmdUpper == "FAN OFF") {
      fanState = false;
      setRelay(RELAY_FAN_PIN, false, "Quạt gió (IN2) [SERIAL]");
    } else if (cmdUpper == "VALVE ON") {
      valveState = true;
      setRelay(RELAY_VALVE_PIN, true, "Van nước (IN1) [SERIAL]");
    } else if (cmdUpper == "VALVE OFF") {
      valveState = false;
      setRelay(RELAY_VALVE_PIN, false, "Van nước (IN1) [SERIAL]");
    } else if (cmdUpper == "INVERT") {
      isRelayActiveLow = !isRelayActiveLow;
      preferences.begin("greenhouse", false);
      preferences.putBool("relay_active_low", isRelayActiveLow);
      preferences.end();
      Serial.print(F("[CẤU HÌNH RELAY] Đã đảo mức kích Relay sang: "));
      Serial.println(isRelayActiveLow ? F("Active LOW (Mức LOW = BẬT, Mức HIGH = TẮT)") : F("Active HIGH (Mức HIGH = BẬT, Mức LOW = TẮT)"));
      // Cập nhật lại trạng thái ra 3 chân
      setRelay(RELAY_VALVE_PIN, valveState, "Van nước");
      setRelay(RELAY_FAN_PIN, fanState, "Quạt gió");
      setRelay(RELAY_LIGHT_PIN, lightState, "Đèn quang hợp");
    } else if (cmdUpper == "TIME") {
      struct tm ti;
      if (getLocalTime(&ti, 50)) {
        Serial.printf("[ĐỒNG HỒ ESP32] %02d:%02d:%02d Ngày %02d/%02d/%04d (Giờ VN GMT+7)\n",
                      ti.tm_hour, ti.tm_min, ti.tm_sec, ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900);
      } else {
        Serial.println(F("[ĐỒNG HỒ ESP32] Chưa đồng bộ thời gian thực."));
      }
    } else if (cmdUpper == "SCHEDULE") {
      Serial.printf("[LỊCH ĐÈN FLASH NVS] Bật: %02d:%02d -> Tắt: %02d:%02d | Đèn hiện tại: %s\n",
                    lightOnHour, lightOnMin, lightOffHour, lightOffMin, lightState ? "BẬT" : "TẮT");
    } else if (cmdUpper == "STATUS") {
      Serial.println(F("\n--- [TRẠNG THÁI PHẦN CỨNG TẤT CẢ CÁC CHÂN] ---"));
      Serial.print(F("Chế độ kích Relay : "));
      Serial.println(isRelayActiveLow ? F("Active LOW (LOW=BẬT)") : F("Active HIGH (HIGH=BẬT)"));
      Serial.print(F("Chân D25 (Van IN1): Xuất ")); Serial.print(digitalRead(RELAY_VALVE_PIN) == HIGH ? F("HIGH (3.3V)") : F("LOW (0V)"));
      Serial.print(F(" | Trạng thái: ")); Serial.println(valveState ? F("BẬT") : F("TẮT"));
      Serial.print(F("Chân D33 (Quạt IN2): Xuất ")); Serial.print(digitalRead(RELAY_FAN_PIN) == HIGH ? F("HIGH (3.3V)") : F("LOW (0V)"));
      Serial.print(F(" | Trạng thái: ")); Serial.println(fanState ? F("BẬT") : F("TẮT"));
      Serial.print(F("Chân D32 (Đèn IN3): Xuất ")); Serial.print(digitalRead(RELAY_LIGHT_PIN) == HIGH ? F("HIGH (3.3V)") : F("LOW (0V)"));
      Serial.print(F(" | Trạng thái: ")); Serial.println(lightState ? F("BẬT") : F("TẮT"));
      Serial.print(F("Chế độ hệ thống   : ")); Serial.println(autoControlMode ? F("TỰ ĐỘNG (AUTO)") : F("THỦ CÔNG (MANUAL)"));
      Serial.println(F("---------------------------------------------"));
    } else {
      Serial.println(F("[TRỢ GIÚP] Các lệnh có thể gõ vào Serial Monitor (115200 baud):"));
      Serial.println(F("  - 'test'      : Tự động chạy thử lần lượt 3 Relay (nghe tiếng tạch)"));
      Serial.println(F("  - 'light on'  / 'light off': Bật / Tắt Đèn (Chân D32)"));
      Serial.println(F("  - 'fan on'    / 'fan off'  : Bật / Tắt Quạt (Chân D33)"));
      Serial.println(F("  - 'valve on'  / 'valve off': Bật / Tắt Van (Chân D25)"));
      Serial.println(F("  - 'invert'    : Đảo chiều mức kích Relay (Active LOW <-> HIGH)"));
      Serial.println(F("  - 'status'    : Xem điện áp thực tế trên các chân"));
    }
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

  // Đọc cấu hình MQTT và Relay đã lưu trong bộ nhớ Flash (NVS)
  preferences.begin("greenhouse", false);
  isRelayActiveLow = preferences.getBool("relay_active_low", false);
  bool forcePortal = preferences.getBool("force_portal", false);
  if (forcePortal) {
    preferences.putBool("force_portal", false);
    Serial.println(F("\n[RESET] Phát hiện yêu cầu cài lại Wi-Fi! Xóa kết nối cũ và BẮT BUỘC mở Web Portal Setup..."));
    WiFi.disconnect(true, true);
  }

  String savedServer = preferences.getString("mqtt_server", "broker.emqx.io");
  // Tự động chuyển sang Cloud broker nếu Flash đang lưu IP LAN nội bộ cũ (192.168.x.x) hoặc rỗng
  if (savedServer.startsWith("192.168.") || savedServer == "localhost" || savedServer.length() == 0) {
    savedServer = "broker.emqx.io";
    preferences.putString("mqtt_server", "broker.emqx.io");
    Serial.println(F("[CẤU HÌNH] Đã tự động cập nhật MQTT Server Cloud thành: broker.emqx.io"));
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

  // Đọc cấu hình chế độ và các ngưỡng từ bộ nhớ Flash NVS của ESP32
  autoControlMode       = preferences.getBool("auto_mode", true);
  tempThreshold         = preferences.getFloat("temp_thresh", 35.0f);
  tempThresholdOff      = max(10.0f, tempThreshold - 5.0f);
  soilThreshold         = preferences.getInt("soil_thresh", 30);
  soilThresholdOff      = min(100, soilThreshold + 25);
  lightTempThreshold    = preferences.getFloat("light_thresh", 20.0f);
  lightTempThresholdOff = min(45.0f, lightTempThreshold + 2.0f);

  lightOnHour           = preferences.getInt("light_on_h", 19);
  lightOnMin            = preferences.getInt("light_on_m", 0);
  lightOffHour          = preferences.getInt("light_off_h", 7);
  lightOffMin           = preferences.getInt("light_off_m", 0);

  Serial.println(F("[CẤU HÌNH NGƯỠNG TỪ FLASH NVS]:"));
  Serial.print(F("  - Chế độ tự động  : ")); Serial.println(autoControlMode ? F("BẬT (Auto)") : F("TẮT (Thủ công)"));
  Serial.print(F("  - Ngưỡng nhiệt độ : > ")); Serial.print(tempThreshold, 1); Serial.println(F(" °C"));
  Serial.print(F("  - Ngưỡng độ ẩm đất: < ")); Serial.print(soilThreshold); Serial.println(F(" %"));
  Serial.print(F("  - Lịch bật đèn    : ")); Serial.print(lightOnHour); Serial.print(F(":")); Serial.print(lightOnMin);
  Serial.print(F(" -> ")); Serial.print(lightOffHour); Serial.print(F(":")); Serial.println(lightOffMin);

  // Cấu hình đồng bộ thời gian thực NTP (máy chủ Việt Nam, GMT+7)
  configTime(7 * 3600, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");

  // Bật chế độ tự động ghi nhớ và tự động kết nối lại Wi-Fi ngầm của phần cứng ESP32
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(true);

  // Đăng ký sự kiện Wi-Fi: Khi mất mạng, LẬP TỨC chuyển sang chế độ TỰ ĐỘNG để bảo vệ cây trồng
  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    Serial.println(F("\n[WI-FI] Bị ngắt kết nối! Lập tức chuyển sang chế độ TỰ ĐỘNG để bảo vệ cây trồng..."));
    if (!autoControlMode) {
      autoControlMode = true;
      preferences.begin("greenhouse", false);
      preferences.putBool("auto_mode", true);
      preferences.end();
      Serial.println(F("[MẤT MẠNG] Đã tự động kích hoạt và lưu chế độ TỰ ĐỘNG (Auto) vào Flash NVS!"));
    }
    WiFi.reconnect();
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_DISCONNECTED);

  WiFi.onEvent([](WiFiEvent_t event, WiFiEventInfo_t info) {
    Serial.print(F("\n[WI-FI] ĐÃ KẾT NỐI THÀNH CÔNG! IP của ESP32: "));
    Serial.println(WiFi.localIP());
    // Khởi động đồng bộ NTP ngay khi có mạng Internet
    configTime(7 * 3600, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");
  }, WiFiEvent_t::ARDUINO_EVENT_WIFI_STA_GOT_IP);

  // 1. Thử kết nối trực tiếp vào Wi-Fi đã lưu trong Flash (tránh bị kẹt trong AP captive portal)
  // Chỉ thử kết nối nếu KHÔNG có cờ ép buộc mở Portal (forcePortal == false)
  if (!forcePortal && WiFi.SSID().length() > 0) {
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

  // 2. Nếu chưa kết nối được hoặc có yêu cầu Reset -> Mở Web Captive Portal
  if (forcePortal || WiFi.status() != WL_CONNECTED) {
    WiFiManager wm;
    wm.setSaveConfigCallback(saveConfigCallback);
    wm.setConfigPortalTimeout(180); // Chờ 180 giây (3 phút) để bạn kịp kết nối cấu hình
    wm.setConnectTimeout(30);

    WiFiManagerParameter custom_mqtt_server("server", "MQTT Server (Domain / IP)", mqtt_server, 40);
    WiFiManagerParameter custom_mqtt_port("port", "Cổng MQTT (mặc định 1883)", mqtt_port_str, 6);
    WiFiManagerParameter custom_device_id("devid", "Device ID (trùng với Web)", device_id, 32);

    wm.addParameter(&custom_mqtt_server);
    wm.addParameter(&custom_mqtt_port);
    wm.addParameter(&custom_device_id);

    Serial.println(F("\n[KẾT NỐI MẠNG] Đang mở Web Portal: 'GreenHouse_ESP32_Setup' (Mật khẩu: 12345678)"));
    Serial.println(F("=> Hãy lấy điện thoại/laptop kết nối vào Wi-Fi: 'GreenHouse_ESP32_Setup'"));
    Serial.println(F("=> Sau đó mở trình duyệt gõ: 192.168.4.1 để cài đặt Wi-Fi và Server!"));

    bool res = false;
    if (forcePortal) {
      // Bắt buộc mở thẳng trang cấu hình AP
      res = wm.startConfigPortal("GreenHouse_ESP32_Setup", "12345678");
    } else {
      res = wm.autoConnect("GreenHouse_ESP32_Setup", "12345678");
    }

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
  // 0. Luôn lắng nghe lệnh kiểm tra chân trực tiếp từ Serial Monitor (không phụ thuộc Wi-Fi)
  handleSerialCommands();

  // Kiểm tra mất mạng: Khi mất Wi-Fi hoặc mất MQTT -> LẬP TỨC chuyển ngay sang chế độ TỰ ĐỘNG để bảo vệ cây trồng
  bool isOnline = (WiFi.status() == WL_CONNECTED) && mqtt.connected();
  if (!isOnline && !autoControlMode) {
    autoControlMode = true;
    preferences.begin("greenhouse", false);
    preferences.putBool("auto_mode", true);
    preferences.end();
    Serial.println(F("\n[SỰ CỐ MẤT MẠNG] Phát hiện mất kết nối! Lập tức chuyển sang chế độ TỰ ĐỘNG (Auto) để bảo vệ nhà kính!"));
  }

  // 1. Kiểm tra nút BOOT: Chỉ reset khi người dùng NHẤN GIỮ LIÊN TỤC >= 3 GIÂY (chống nhiễu điện)
  if (digitalRead(TRIGGER_PIN) == LOW) {
    uint32_t pressStart = millis();
    while (digitalRead(TRIGGER_PIN) == LOW) {
      delay(50);
      if (millis() - pressStart >= 3000) {
        Serial.println(F("\n[RESET] Đã giữ nút BOOT đủ 3 giây! Xóa cấu hình Wi-Fi và phát lại Web Portal..."));
        preferences.begin("greenhouse", false);
        preferences.putBool("force_portal", true);
        preferences.end();
        WiFi.disconnect(true, true);
        delay(600);
        ESP.restart();
      }
    }
  }

  // 2. Chu kỳ đo mỗi 2 giây: kiểm tra biến động lớn -> gửi luôn; ổn định -> 10s gửi 1 lần
  if (millis() - lastMeasureMs >= MEASURE_INTERVAL_MS) {
    lastMeasureMs = millis();

    // Bước 1: Tiến hành đo cảm biến định kỳ mỗi 2 giây và chạy kiểm tra tự động kích Relay
    measureSensorsAndAutoControl();

    // Bước 2: Kiểm tra xem các giá trị có cách nhau quá lớn hoặc đổi trạng thái relay không
    bool tempChanged  = (lastSentTemp < -900.0f) || (fabs(airTemp - lastSentTemp) >= DELTA_TEMP_THRESHOLD);
    bool humiChanged  = (lastSentHumidity < -900.0f) || (fabs(airHumidity - lastSentHumidity) >= DELTA_HUMI_THRESHOLD);
    bool soilChanged  = (lastSentSoil < -900) || (abs(soilMoisture - lastSentSoil) >= DELTA_SOIL_THRESHOLD);
    bool relayChanged = (valveState != lastSentValve) || (fanState != lastSentFan) || (lightState != lastSentLight);

    bool significantChange = tempChanged || humiChanged || soilChanged || relayChanged;
    bool timeoutHeartbeat  = (millis() - lastPublishMs >= HEARTBEAT_INTERVAL_MS);

    // Bước 3: Nếu biến động lớn HOẶC đã đủ 10 giây định kỳ -> gửi lên Server
    if (significantChange || timeoutHeartbeat) {
      if (significantChange && !timeoutHeartbeat) {
        Serial.print(F("[GỬI TỨC THÌ] Phát hiện biến động lớn ("));
        if (tempChanged) Serial.print(F(" NhiệtĐộ"));
        if (humiChanged) Serial.print(F(" ĐộẨmKK"));
        if (soilChanged) Serial.print(F(" ĐộẨmĐất"));
        if (relayChanged) Serial.print(F(" Relay"));
        Serial.println(F(" ) -> Gửi telemetry ngay lập tức!"));
      } else {
        Serial.println(F("[HEARTBEAT 10S] Thông số ổn định -> Gửi định kỳ duy trì kết nối"));
      }

      bool isOnline = (WiFi.status() == WL_CONNECTED) && mqtt.connected();
      if (!isOnline) {
        saveOfflineRecord();
      } else {
        flushOfflineRecords();
        publishTelemetry();
      }
    }
  }

  // 3. DUY TRÌ & KẾT NỐI LẠI WI-FI (KHÔNG GỌI WiFi.disconnect() tránh ngắt quãng bắt tay DHCP)
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
