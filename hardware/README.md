# Thiết Kế Phần Cứng — IOT GreenHouse

Thư mục này chứa sơ đồ nguyên lý mạch điện, mô phỏng và tài liệu kết nối phần cứng của hệ thống giám sát và điều khiển nhà kính thông minh sử dụng vi điều khiển ESP32.

---

## 1. Danh sách linh kiện

| Linh kiện | Chức năng | Điện áp hoạt động |
| :--- | :--- | :--- |
| **ESP32 DevKit V1 (30 chân)** | Vi điều khiển trung tâm, kết nối Wi-Fi & MQTT | 3.3V / 5V (Micro-USB) |
| **DHT22 (AM2302)** | Cảm biến đo nhiệt độ và độ ẩm không khí | 3.3V – 5V DC |
| **HW-390 (Capacitive Soil Moisture)** | Cảm biến đo độ ẩm đất điện dung (chống ăn mòn) | 3.3V DC |
| **Module Relay 3 kênh** | Kích hoạt đóng/ngắt các thiết bị ngoại vi (Active HIGH) | 5V DC (Tín hiệu kích 3.3V/5V) |
| **Van điện từ (Solenoid Valve)** | Đóng/mở đường nước tưới cây | 12V DC |
| **Quạt thông gió mini** | Thông gió, tản nhiệt và hạ nhiệt độ nhà kính | 12V DC |
| **Đèn LED quang hợp nông nghiệp** | Cung cấp ánh sáng cho cây trồng theo lịch | 12V DC |
| **Nguồn DC 12V 3A** | Nguồn công suất cấp cho van, quạt, đèn | 220V AC -> 12V DC |

---

## 2. Bảng phân bổ chân kết nối (Pinout Mapping)

| Thiết bị / Module | Chân linh kiện | Chân kết nối ESP32 | Ghi chú |
| :--- | :--- | :--- | :--- |
| **DHT22** | DATA | `GPIO 26` | Điện trở pull-up 10kΩ lên 3.3V |
| **HW-390** | AOUT (Analog) | `GPIO 35` (ADC1_CH7) | Kênh ADC1 hoạt động độc lập với Wi-Fi |
| **Relay Kênh 1 (Van tưới)** | IN1 | `GPIO 25` | Điều khiển van nước |
| **Relay Kênh 2 (Quạt thông gió)**| IN2 | `GPIO 33` | Điều khiển quạt tản nhiệt |
| **Relay Kênh 3 (Đèn quang hợp)** | IN3 | `GPIO 32` | Điều khiển đèn chiếu sáng |

> **Nguyên tắc cấp nguồn an toàn:**
> - ESP32 và các cảm biến sử dụng nguồn 5V/3.3V riêng biệt (qua cổng USB hoặc mạch hạ áp).
> - Nguồn 12V 3A cấp riêng cho tải động lực (van tưới, quạt, đèn LED) thông qua các tiếp điểm thường mở (NO - Normally Open) của module Relay.
> - Tuyệt đối không nối chung nguồn 12V vào đường cấp nguồn của ESP32.

---

## 3. Danh mục tài liệu và sơ đồ

- **`so-do-mach-cirkit.png`**: Sơ đồ nguyên lý đấu nối chi tiết thiết kế trên phần mềm Cirkit Designer.
- **`so_do_cirkit_chu_thich.png`**: Sơ đồ mạch Cirkit có đầy đủ chú thích các khối ngoại vi, cảm biến và tiếp điểm relay.
- **`so_do_wokwi_chu_thich.png`**: Sơ đồ thiết kế và mô phỏng mạch trên nền tảng Wokwi kèm chú thích từng chân kết nối.
- **`so_do_linh_kien_web.png`**: Hình ảnh trực quan tổng hợp các linh kiện phần cứng sử dụng trong thực tế.
