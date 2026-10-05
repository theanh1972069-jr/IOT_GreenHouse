# Firmware ESP32 — gửi dữ liệu cảm biến lên web

## 1. Kiến trúc

```
ESP32 ──MQTT──▶ Broker ──subscribe──▶ Spring Boot ──▶ PostgreSQL ──REST──▶ Web
```

Backend **tự động lắng nghe**, bạn **không cần** gọi REST API từ firmware.
Firmware chỉ việc `publish` JSON lên đúng topic là xong.

## 2. Hợp đồng topic và payload

| | |
|---|---|
| Topic | `greenhouse/{deviceId}/telemetry` |
| Payload | JSON object, ví dụ `{"temperature":25.1,"humidity":58.3,"light":733.2}` |

Tên field được chấp nhận: `temperature`, `humidity`, `soilMoisture` (hoặc
`soil_moisture`), `light`. Có thể gửi một phần — field vắng mặt sẽ lưu `NULL`.
Field nào sai kiểu dữ liệu hoặc nằm ngoài phạm vi sẽ bị bỏ qua và ghi log.

## 3. Ba điều kiện bắt buộc — thiếu một là hỏng

### a) Broker phải đang chạy
```powershell
docker run -d --name greenhouse-mqtt -p 1883:1883 eclipse-mosquitto:2
```

### b) Backend phải bật MQTT
```powershell
cd backend
$env:MQTT_ENABLED="true"
$env:MQTT_BROKER_URL="tcp://localhost:1883"
.\mvnw.cmd spring-boot:run
```

> Backend **phải khởi động sau khi broker** đã lên. Nếu broker chết rồi mới bật
> lại, phải restart backend — nó subscribe một lần lúc startup và không tự
> kết nối lại.

### c) `deviceId` phải tồn tại trong DB
Nếu không, backend ghi `Received telemetry for unknown device` và **bỏ qua im lặng**.
Tạo qua UI: admin → **Thiết bị** → đăng ký với mã khớp `DEVICE_ID` trong firmware.

## 4. Firmware

Source: `firmware/esp32_telemetry/esp32_telemetry.ino` (DHT22 + BH1750).

Arduino IDE cài: **PubSubClient**, **Adafruit BH1750 Library**.

Sửa 4 giá trị:

| Hằng số | Ghi gì |
|---|---|
| `WIFI_SSID` / `WIFI_PASSWORD` | Wi-Fi nhà bạn |
| `MQTT_HOST` | **IP LAN của PC**, không phải `localhost` |
| `DEVICE_ID` | Khớp `devices.device_id` |
| `MQTT_TOPIC` | Phải chứa đúng `DEVICE_ID` |

Tìm IP LAN:
```powershell
ipconfig    # dòng IPv4 trên adapter Wi-Fi, ví dụ 192.168.1.8
```

ESP32 chỉ gọi được broker trong cùng mạng LAN. Nếu dùng mạng công ty hoặc
Wi-Fi khách (có "Client isolation"), ESP32 sẽ không mở được cổng 1883.

## 5. Chẩn đoán khi không thấy dữ liệu

Chạy theo thứ tự, từ từng tầng:

```powershell
# Tầng 1 — broker còn sống?
docker ps --filter "name=greenhouse-mqtt"
#   rỗng => broker đã chết. Chạy lại lệnh ở mục 3a, RỒI restart backend.

# Tầng 2 — backend đã subscribe chưa?  (phải ra 1)
docker exec greenhouse-mqtt mosquitto_sub -h localhost -t '$SYS/broker/subscriptions/count' -C 1 -W 5
#   ra 0 => backend chưa bật MQTT, hoặc chạy trước khi broker có.

# Tầng 3 — đường LAN tới broker có thông? (thay bằng IP của bạn)
Test-NetConnection -ComputerName 192.168.1.8 -Port 1883
#   TcpTestSucceeded=False => tường lửa hoặc IP sai.

# Tầng 4 — giả lập firmware (không cần ESP32)
docker exec greenhouse-mqtt mosquitto_pub -h 192.168.1.8 `
  -t 'greenhouse/esp32-01/telemetry' -q 1 `
  -m '{"temperature":25.1,"humidity":58.3,"light":733.2}'
```

Sau tầng 4, mở dashboard. Số liệu đổi sau tối đa 10 giây.

> **Lưu ý về PowerShell:** `-m '{...}'` hay bị mất dấu nháy kép trong
> PowerShell. Nếu nghi ngờ, dùng file thay cho chuỗi:
> ```powershell
> [System.IO.File]::WriteAllText("$env:TEMP\p.json",
>   '{"temperature":25.1,"humidity":58.3,"light":733.2}',
>   (New-Object System.Text.UTF8Encoding($false)))
> docker cp "$env:TEMP\p.json" greenhouse-mqtt:/tmp/p.json
> docker exec greenhouse-mqtt mosquitto_pub -h 192.168.1.8 `
>   -t 'greenhouse/esp32-01/telemetry' -q 1 -f /tmp/p.json
> ```

## 6. Đọc log backend

Trong cửa sổ chạy `spring-boot:run`, các dòng cần chú ý:

| Log | Ý nghĩa |
|---|---|
| `Received MQTT message on topic: ...` | Broker → backend thành công |
| `Saved telemetry for device: ...` | Đã lưu DB. **Đây là dòng bạn cần thấy** |
| `Raised ... alert` | Vượt ngưỡng, đã sinh cảnh báo |
| `Discarding malformed telemetry payload` | JSON hỏng — thường do mất dấu nháy |
| `Received telemetry for unknown device` | `deviceId` chưa có trong DB |
| `Ignoring out-of-range ...` | Giá trị vượt phạm vi (vd humidity > 100) |

Chỉ thấy `Received MQTT message` mà **không** thấy `Saved telemetry` ⇒ payload
bị parser loại. Đọc thông điệp WARN ngay dưới để biết lý do.

## 7. LED trên ESP32

| Trạng thái | Nghĩa |
|---|---|
| Nhấp nháy hoặc tắt | Bình thường, đang publish |
| Sáng đặc | Chưa có Wi-Fi hoặc chưa kết nối MQTT |

Theo dõi bằng Serial Monitor ở **115200 baud**.
