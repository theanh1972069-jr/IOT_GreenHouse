/*
 * FloraCore - ESP32 do moi truong + gui len web
 * ---------------------------------------------------------------------------
 * Ban do doc cam bien va in ra Serial Monitor (phan ban test tren Arduino).
 * Ban nay giu nguyen logic do, THEM phan ket noi WiFi + MQTT de gui len backend.
 *
 * Luong du lieu:
 *   ESP32 --MQTT--> broker --> Spring Boot --> PostgreSQL --> web
 *
 * Backend tu dong lang nghe, firmware KHONG can goi REST API.
 *
 * Topic:  greenhouse/esp32-01/telemetry
 * Payload JSON: {"temperature":..,"humidity":..,"soilMoisture":..,"light":..}
 *
 * Arduino IDE: Library Manager -> cai dat "PubSubClient"
 *               (va "Adafruit BH1750 Library" neu dung cam bien anh sang)
 * ---------------------------------------------------------------------------
 */

#include <DHT.h>
#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_BH1750.h>

// ============================ CAU HINH WIFI ================================
static const char* WIFI_SSID     = "TEN_WIFI_CUA_BAN";
static const char* WIFI_PASSWORD = "MAT_KHAU_WIFI_CUA_BAN";

/*
 * IP LAN cua may tinh dang chay backend - KHONG phai "localhost".
 * Xem bang:  ipconfig   -> dong IPv4 tren adapter Wi-Fi, vi du 192.168.1.8
 * ESP32 khong the goi "localhost" vi local voi chinh no.
 */
static const char* MQTT_HOST     = "192.168.1.8";
static const uint16_t MQTT_PORT  = 1883;

// Broker khong yeu cau dang nhap -> de trong.
static const char* MQTT_USER     = "";
static const char* MQTT_PASSWORD = "";

/*
 * DEVICE_ID phai trung voi devices.device_id trong database.
 * Sai ten o day -> backend bao "unknown device" va BO QUA im lang.
 */
static const char* DEVICE_ID = "esp32-01";

static const char* TOPIC = "greenhouse/esp32-01/telemetry";  // phai chua DEVICE_ID

// ======================== CAU HINH CAM BIEN (gia nguyen) ====================
#define DHTPIN 4          // Chan DATA cua DHT22 noi GPIO 4
#define DHTTYPE DHT22
DHT dht(DHTPIN, DHTTYPE);

// GPIO 34 la ADC1_CH6 - dung duoc song song voi WiFi.
// (Luu y: KHONG dung chan ADC2 nhu 0, 2, 15, 13, 12, 14... se loi khi bat WiFi)
#define SOIL_PIN 34

// Gia tri ADC can hieu chinh trong thuc te
const int AIR_VALUE   = 3500;   // Doc duoc khi de kho ngoai khong khi (0%)
const int WATER_VALUE = 1500;   // Doc duoc khi nhung ngan nuoc (100%)

#define LIGHT_I2C_SDA 21
#define LIGHT_I2C_SCL 22

// ============================== THOI GIAN ===================================
static const uint32_t READ_INTERVAL_MS    = 2000;   // doc cam bien 2 giay (như bản cũ)
static const uint32_t PUBLISH_INTERVAL_MS = 15000;  // gui len web 15 giay
static const uint32_t WIFI_RETRY_MS       = 5000;

WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);
Adafruit_BH1750 bh1750;
static bool lightSensorReady = false;

static uint32_t lastReadMs      = 0;
static uint32_t lastPublishMs   = 0;
static uint32_t lastWifiRetryMs = 0;

// Cac gia tri cam bien, dung o ca hai noi
float air_temp = 0, air_humidity = 0;
int   soil_humidity = 0, soil_raw = 0;
float lightLux = -1;

// ============================ WI-FI / MQTT ==================================
void connectWifi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (millis() - lastWifiRetryMs < WIFI_RETRY_MS) return;
  lastWifiRetryMs = millis();

  Serial.print(F("Dang ket noi Wi-Fi: "));
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < 15000) {
    delay(500);
    Serial.print('.');
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print(F("\nDa ket noi Wi-Fi. IP: "));
    Serial.println(WiFi.localIP());
    Serial.print(F("Serial Monitor baud phai doi thanh "));
    Serial.println(WiFi.baudRate());
  } else {
    Serial.println(F("\nKet noi Wi-Fi that bai, se thu lai..."));
  }
}

void connectMqtt() {
  if (mqtt.connected()) return;

  Serial.print(F("Dang ket noi MQTT "));
  Serial.print(MQTT_HOST);
  Serial.print(F(":"));
  Serial.println(MQTT_PORT);

  bool ok;
  if (MQTT_USER[0] == '\0') {
    ok = mqtt.connect(DEVICE_ID);
  } else {
    ok = mqtt.connect(DEVICE_ID, MQTT_USER, MQTT_PASSWORD);
  }

  if (ok) {
    Serial.println(F("MQTT da ket noi."));
  } else {
    Serial.print(F("MQTT that bai, state = "));
    Serial.println(mqtt.state());
  }
}

// ============================== GUI LEN WEB =================================
void publishMqtt() {
  char msg[192];
  if (lightSensorReady) {
    snprintf(msg, sizeof(msg),
      "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d,\"light\":%.1f}",
      air_temp, air_humidity, soil_humidity, lightLux);
  } else {
    snprintf(msg, sizeof(msg),
      "{\"temperature\":%.1f,\"humidity\":%.1f,\"soilMoisture\":%d}",
      air_temp, air_humidity, soil_humidity);
  }

  if (mqtt.publish(TOPIC, msg, true)) {   // retained = true
    Serial.print(F(">> DA GUI: "));
    Serial.println(msg);
  } else {
    Serial.print(F(">> Gui that bai, state = "));
    Serial.println(mqtt.state());
  }
}

// ============================== SETUP =======================================
void setup() {
  Serial.begin(115200);
  delay(1000);

  Serial.println(F("============================================="));
  Serial.println(F("Khoi dong he thong do moi truong..."));
  Serial.println(F("============================================="));

  dht.begin();

  // Cam bien anh sang (khong bat buoc - van chay duoc neu thieu)
  Wire.begin(LIGHT_I2C_SDA, LIGHT_I2C_SCL);
  lightSensorReady = bh1750.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
  if (!lightSensorReady) {
    Serial.println(F("[BH1750] Khong tim thay cam bien anh sang (van chay tiep)."));
  }

  WiFi.mode(WIFI_STDOUT);
  WiFi.setSleep(false);   // tranh modem tu dong tat WiFi
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);
}

// ============================== LOOP ========================================
void loop() {
  connectWifi();

  // --- doc cam bien moi 2 giay (giu nguyen nhu ban cu) ---
  if (millis() - lastReadMs >= READ_INTERVAL_MS) {
    lastReadMs = millis();

    // 1. DHT22
    air_humidity = dht.readHumidity();
    air_temp     = dht.readTemperature();

    // 2. Cam bien do am dat
    soil_raw = analogRead(SOIL_PIN);
    soil_humidity = map(soil_raw, AIR_VALUE, WATER_VALUE, 0, 100);
    soil_humidity = constrain(soil_humidity, 0, 100);

    // 3. Cam bien anh sang
    if (lightSensorReady) {
      lightLux = bh1750.readLightLevel();
    }

    // 4. In ra Serial Monitor
    Serial.println(F("---------------------------------------------"));
    if (isnan(air_humidity) || isnan(air_temp)) {
      Serial.println(F("[DHT22] Loi: Khong doc duoc du lieu!"));
    } else {
      Serial.print(F("[DHT22] Nhiet do: "));
      Serial.print(air_temp, 1);
      Serial.print(F("°C  |  Do am KK: "));
      Serial.print(air_humidity, 1);
      Serial.println(F("%"));
    }
    Serial.print(F("[Dat] Gia tri Raw ADC: "));
    Serial.print(soil_raw);
    Serial.print(F("  |  Do am dat: "));
    Serial.print(soil_humidity);
    Serial.println(F("%"));
    if (lightSensorReady) {
      Serial.print(F("[Anh sang] "));
      Serial.print(lightLux, 1);
      Serial.println(F(" lux"));
    }
  }

  // --- phan MQTT: chi chay khi da co Wi-Fi ---
  if (WiFi.status() != WL_CONNECTED) {
    delay(500);
    return;
  }

  connectMqtt();
  if (!mqtt.connected()) {
    mqtt.loop();
    delay(200);
    return;
  }

  mqtt.loop();

  // --- gui len web moi 15 giay ---
  if (millis() - lastPublishMs >= PUBLISH_INTERVAL_MS && !isnan(air_temp)) {
    lastPublishMs = millis();
    publishMqtt();
  }

  delay(100);
}
