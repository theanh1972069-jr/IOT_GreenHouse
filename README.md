# IoT Greenhouse

Ứng dụng giám sát nhà thông minh/nhà kính gồm ESP32, MQTT, Spring Boot,
PostgreSQL và dashboard React. Thiết kế phần cứng dùng hai ESP32:

- Node 1 luôn bật: DHT22, quạt và đèn; quạt dùng hysteresis 30/28 °C.
- Node 2 chạy pin: DHT22 và van; gửi dữ liệu rồi ngủ sâu 15 phút; van tự đóng
  cục bộ sau tối đa 30 giây. Cảm biến độ ẩm đất là phần mở rộng, mặc định tắt.

## Chạy local

1. Tạo PostgreSQL database và chạy [`database/create_tables.sql`](database/create_tables.sql).
   Với database cũ, áp dụng migration mới trong `database/migrations/`.
2. Chạy backend trong PowerShell:

   ```powershell
   cd backend
   $env:DB_URL="jdbc:postgresql://localhost:5432/iot_greenhouse"
   $env:DB_USERNAME="postgres"
   $env:DB_PASSWORD="<mật khẩu database>"
   $env:BOOTSTRAP_ADMIN_PASSWORD="<mật khẩu quản trị mạnh>"
   $env:MQTT_ENABLED="true"
   $env:MQTT_BROKER_URL="tcp://localhost:1883"
   .\mvnw.cmd spring-boot:run
   ```

   `AuthBootstrap` chỉ tạo tài khoản admin lần đầu nếu `BOOTSTRAP_ADMIN_PASSWORD`
   được cung cấp. Không có mật khẩu mặc định trong ứng dụng.
3. Chạy frontend trong cửa sổ khác:

   ```powershell
   cd frontend
   npm install
   npm run dev
   ```

4. Tạo hai thiết bị trong dashboard, sau đó nạp
   [`firmware/esp32_telemetry/esp32_telemetry.ino`](firmware/esp32_telemetry/esp32_telemetry.ino)
   lên mỗi board. Trong captive portal cấu hình riêng `node1`/`node2`, device ID
   và zone. Hướng dẫn chi tiết ở
   [`firmware/esp32_telemetry/README.md`](firmware/esp32_telemetry/README.md).

## Giao thức dữ liệu

Telemetry mới được gửi lên `sh/home1/{device_id}/telemetry` với timestamp,
sequence, zone, quality, phiên bản firmware và đơn vị đo. Backend còn hỗ trợ
topic/payload phẳng cũ để tương thích ngược; sequence trùng bị loại bỏ.

## Kiểm tra

```powershell
cd backend
.\mvnw.cmd test
```

```powershell
cd frontend
npm run build
```

## Giới hạn hiện tại cần hoàn tất trước nghiệm thu

- MQTT của firmware hiện dùng TCP/1883, chưa bật TLS/8883, credential riêng hoặc
  broker ACL; chỉ chạy trong mạng LAN thử nghiệm.
- Backend chưa phát lệnh MQTT và firmware chưa gửi ACK/heartbeat; dashboard
  chưa thực hiện điều khiển end-to-end.
- Node 2 chưa dùng persistent MQTT session; lệnh gửi trong khi node ngủ có thể
  bị bỏ lỡ.
- Dashboard vẫn polling REST, chưa có WebSocket; phạm vi quyền theo zone, xuất
  CSV và thống kê theo giờ/ngày chưa đầy đủ.
- Giới hạn mở van 30 giây và chu kỳ deep sleep 15 phút là giá trị khởi điểm;
  cần xác nhận bằng prototype và số liệu đo thực tế.