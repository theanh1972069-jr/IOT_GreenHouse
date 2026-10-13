/*
 * FloraCore - ESP32 telemetry sender
 * ---------------------------------------------------------------------------
 * Publishes sensor readings to the MQTT broker that the Spring Boot backend
 * subscribes to. The backend stores them in PostgreSQL and the web dashboard
 * reads them through GET /api/telemetry?deviceId=<DEVICE_ID>.
 *
 * Topic contract (see backend MqttAdapter):
 *   greenhouse/{deviceId}/telemetry   payload is a JSON object, e.g.
 *   {"temperature":26.4,"humidity":61.8,"light":842.5}
 *
 * Accepted field names: temperature, humidity, soilMoisture (or soil_moisture),
 * light. Any subset is fine; fields that are absent are stored as NULL.
 *
 * Boards:
 *   ESP32 DevKit + DHT22 (temp/humidity, DATA on GPIO 4)
 *   BH1750 ambient light sensor over I2C (SDA=GPIO 21, SCL=GPIO 22)
 *
 * Arduino IDE: install "PubSubClient" and "Adafruit BH1750 Library" from
 * Library Manager, then upload this file.
 */

#include <WiFi.h>
#include <PubSubClient.h>
#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BH1750.h>
#include <DHT.h>

// ---------------------------------------------------------------- configuration
static const char* WIFI_SSID     = "Tang 3";
static const char* WIFI_PASSWORD = "Anh@0711";

// Must match devices.device_id in PostgreSQL, otherwise the backend logs
// "Received telemetry for unknown device" and drops the reading.
static const char* DEVICE_ID = "esp32-01";

static const char* MQTT_HOST     = "192.168.1.100";   // <-- your PC's LAN IP, not localhost
static const uint16_t MQTT_PORT  = 1883;
static const char* MQTT_USER     = "";               // broker without auth: leave empty
static const char* MQTT_PASSWORD = "";
static const char* MQTT_TOPIC    = "greenhouse/esp32-01/telemetry";  // must contain DEVICE_ID

static const uint8_t  DHT_PIN   = 4;
static const uint32_t PUBLISH_INTERVAL_MS = 15000;    // 15 s
static const uint32_t WIFI_RETRY_MS        = 5000;

// ------------------------------------------------------------------- instances
WiFiClient wifiClient;
PubSubClient mqtt(wifiClient);
DHT dht(DHT_PIN, DHT22);
Adafruit_BH1750 bh1750;

static uint32_t lastPublishMs = 0;
static uint32_t lastWifiAttemptMs = 0;
static bool lightSensorReady = false;

// ----------------------------------------------------------------- wifi / mqtt
void connectWifi() {
  if (WiFi.status() == WL_CONNECTED) return;

  if (millis() - lastWifiAttemptMs < WIFI_RETRY_MS) return;
  lastWifiAttemptMs = millis();

  Serial.printf("Connecting to Wi-Fi: %s\n", WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  uint32_t started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < 15000) {
    delay(500);
    Serial.print('.');
  }

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("\nWi-Fi connected. IP: %s\n", WiFi.localIP().toString().c_str());
  } else {
    Serial.println("\nWi-Fi connection failed, will retry.");
  }
}

void connectMqtt() {
  if (mqtt.connected()) return;

  Serial.printf("Connecting to MQTT at %s:%u\n", MQTT_HOST, MQTT_PORT);
  bool ok;
  if (MQTT_USER[0] == '\0') {
    ok = mqtt.connect(DEVICE_ID);
  } else {
    ok = mqtt.connect(DEVICE_ID, MQTT_USER, MQTT_PASSWORD);
  }

  if (ok) {
    Serial.println("MQTT connected.");
  } else {
    Serial.printf("MQTT failed, state=%d\n", mqtt.state());
  }
}

// ------------------------------------------------------------------ publishing
void publishTelemetry() {
  // snprintf into a fixed buffer: avoids String fragmentation on the heap.
  char payload[160];
  float temperature = dht.readTemperature();
  float humidity    = dht.readHumidity();
  float light       = lightSensorReady ? bh1750.readLightLevel() : -1.0f;

  // isnan guards the DHT's NAN return on a failed I2C read, which would otherwise
  // be published as a bogus value.
  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("DHT read failed, skipping this cycle.");
    return;
  }

  if (lightSensorReady) {
    snprintf(payload, sizeof(payload),
             "{\"temperature\":%.2f,\"humidity\":%.2f,\"light\":%.2f}",
             temperature, humidity, light);
  } else {
    snprintf(payload, sizeof(payload),
             "{\"temperature\":%.2f,\"humidity\":%.2f}",
             temperature, humidity);
  }

  // Retained so a backend starting later immediately receives the last reading.
  if (mqtt.publish(MQTT_TOPIC, payload, /*retained=*/true)) {
    Serial.printf("Published: %s\n", payload);
  } else {
    Serial.printf("Publish failed, state=%d\n", mqtt.state());
  }
}

// ----------------------------------------------------------------------- setup
void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println("\nFloraCore ESP32 telemetry node");

  pinMode(LED_BUILTIN, OUTPUT);

  dht.begin();
  Wire.begin(21, 22);            // ESP32 default I2C pins
  lightSensorReady = bh1750.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
  if (!lightSensorReady) {
    Serial.println("BH1750 not found - light will be omitted from payloads.");
  }

  WiFi.mode(WIFI_STDOUT);
  WiFi.setSleep(false);           // avoids the modem dropping the connection
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setBufferSize(512);
}

// ----------------------------------------------------------------------- loop
void loop() {
  connectWifi();
  if (WiFi.status() != WL_CONNECTED) {
    digitalWrite(LED_BUILTIN, HIGH);   // solid = no network
    delay(1000);
    return;
  }

  connectMqtt();
  if (!mqtt.connected()) {
    digitalWrite(LED_BUILTIN, HIGH);
    mqtt.loop();
    delay(500);
    return;
  }

  digitalWrite(LED_BUILTIN, LOW);      // off = publishing normally

  mqtt.loop();

  uint32_t now = millis();
  if (now - lastPublishMs >= PUBLISH_INTERVAL_MS) {
    lastPublishMs = now;
    publishTelemetry();
  }

  delay(200);
}
