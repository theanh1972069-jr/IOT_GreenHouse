# Firmware ESP32 — Node 1 / Node 2

## 1. Kiến trúc

```
Node 1 (luôn bật) ──MQTT──┐
                          ├──▶ Broker ──▶ Spring Boot ──▶ PostgreSQL ──REST──▶ Web
Node 2 (ngủ sâu 15 phút) ─┘
```

Một sketch có thể nạp cho cả hai ESP32; chọn `node1` hoặc `node2` trên captive
portal của từng board. Mỗi board phải có `device_id` riêng và đã được đăng ký
trong dashboard.

- **Node 1:** DHT22 + quạt (GPIO 33) + đèn (GPIO 32), luôn bật; quạt bật trên
  30 °C và tắt dưới 28 °C.
- **Node 2:** DHT22 + van (GPIO 25); gửi dữ liệu rồi deep sleep 15 phút. Cửa sổ
  nhận lệnh sau khi gửi là 5 giây; nếu van đang mở, node giữ thức và tự đóng
  sau tối đa 30 giây. Cảm biến đất là tùy chọn, mặc định tắt.

Backend tự động nhận telemetry. Hiện backend chưa phát lệnh MQTT và firmware
chưa gửi command acknowledgement; dashboard vì vậy chưa cung cấp điều khiển
end-to-end.

## 2. Hợp đồng topic và payload

| | |
|---|---|
| Topic | `sh/home1/{deviceId}/telemetry` |
| Payload | JSON object có metadata và đơn vị, ví dụ `{"device_id":"esp32-n1","ts":"2026-11-02T08:15:00Z","seq":1042,"zone":"livingroom","temp":{"v":25.1,"u":"C"},"hum":{"v":58.3,"u":"%"},"q":"ok","fw":"1.0.2"}` |

Backend vẫn chấp nhận payload phẳng cũ và topic cũ `greenhouse/{deviceId}/telemetry`
để tương thích ngược. Với payload mới, `seq` được dùng để loại bản tin trùng;
`ts` phải là ISO-8601 có múi giờ. Nhiệt độ được giới hạn trong dải DHT22 (-40..80 °C).
Các trường đo có thể dùng cấu trúc `{ "v": số, "u": đơn_vị }`; giá trị lỗi hoặc
ngoài dải bị loại và ghi log.

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

> Backend tự reconnect khi broker tạm ngắt. Đảm bảo broker đã hoạt động trước
> khi kiểm thử để tránh nhầm lỗi kết nối với lỗi thiết bị.

### c) `deviceId` phải tồn tại trong DB
Nếu không, backend ghi `Received telemetry for unknown device` và **bỏ qua im lặng**.
Tạo qua UI: admin → **Thiết bị** → đăng ký với mã khớp `DEVICE_ID` trong firmware.

## 4. Firmware và phần cứng

Source: `firmware/esp32_telemetry/esp32_telemetry.ino`.

Arduino IDE cài: **PubSubClient**, **DHT sensor library**, **Adafruit Unified
Sensor**, **WiFiManager**.

Trong captive portal cấu hình Wi-Fi, nhập:

| Trường | Giá trị |
|---|---|
| Wi-Fi | SSID và mật khẩu mạng 2,4 GHz |
| MQTT server | **IP LAN của PC**, không phải `localhost` |
| MQTT port | `1883` cho mạng thử nghiệm hiện tại |
| Vai trò | `node1` hoặc `node2` |
| Device ID | Riêng từng node và khớp `devices.device_id` |
| Zone | `livingroom` cho Node 1, `balcony` cho Node 2 (hoặc zone đã đăng ký) |

Tìm IP LAN:
```powershell
ipconfig    # dòng IPv4 trên adapter Wi-Fi, ví dụ 192.168.1.8
```

Đây mới là cấu hình MQTT TCP/1883 cho mạng thử nghiệm LAN. Firmware hiện chưa
cấu hình TLS 1.2/8883, credential MQTT riêng hoặc persistent session cho Node 2;
không đưa bản firmware này ra Internet hoặc dùng với dữ liệu nhạy cảm.

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
  -t 'sh/home1/esp32-n1/telemetry' -q 0 `
  -m '{"device_id":"esp32-n1","ts":"2026-11-02T08:15:00Z","seq":1042,"zone":"livingroom","temp":{"v":25.1,"u":"C"},"hum":{"v":58.3,"u":"%"},"q":"ok","fw":"1.0.2"}'
```

Sau tầng 4, mở dashboard. Với Node 1, số liệu đổi sau tối đa khoảng 10 giây. Node 2 chỉ gửi sau mỗi lần
thức dậy (mặc định 15 phút).

> **Lưu ý về PowerShell:** `-m '{...}'` hay bị mất dấu nháy kép trong
> PowerShell. Nếu nghi ngờ, dùng file thay cho chuỗi:
> ```powershell
> [System.IO.File]::WriteAllText("$env:TEMP\p.json",
>   '{"temperature":25.1,"humidity":58.3,"light":733.2}',
>   (New-Object System.Text.UTF8Encoding($false)))
> docker cp "$env:TEMP\p.json" greenhouse-mqtt:/tmp/p.json
> docker exec greenhouse-mqtt mosquitto_pub -h 192.168.1.8 `
>   -t 'sh/home1/esp32-n1/telemetry' -q 1 -f /tmp/p.json
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

## 8. Giới hạn và an toàn

- Giới hạn van 30 giây là giá trị khởi điểm theo yêu cầu hiện tại; phải xác nhận
  bằng lưu lượng thực tế trước khi nối tải. Dùng nguồn DC riêng và diode
  flyback cho cuộn van; không đấu điện lưới.
- Node 2 chưa có cơ chế persistent MQTT queue, vì vậy lệnh gửi trong lúc node
  ngủ có thể bị bỏ lỡ. Điều khiển/ack end-to-end cần hoàn thành cùng API backend
  và broker ACL trước khi nghiệm thu.
