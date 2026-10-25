# Quyết định triển khai IoT Greenhouse

Ngày: 2026-10-25  
Trạng thái: các mặc định dưới đây được chọn để tiến hành triển khai; có thể thay đổi trước khi nạp firmware lên thiết bị thật.

## Giao thức MQTT

1. `{site}` dùng mã `site-01`. Định danh này là cấu hình ở firmware/backend, không phải tên miền nhà thông minh.
2. Topic mới dùng `greenhouse/{site}/{device_id}/{telemetry|heartbeat|status|cmd|cmd/ack|config|event}`.
3. Chọn chuyển đổi atomic: backend và toàn bộ firmware node phải được phát hành/nạp đồng bộ. Không duy trì subscription/schema cũ sau cutover nhằm tránh tạo hai luồng dữ liệu. **Mọi ESP32 hiện có cần nạp lại firmware trước khi backend chuyển sang topic mới.**
4. QoS: telemetry 0; heartbeat 0; status QoS 1 retained và Last Will; cmd QoS 1; cmd/ack QoS 1; config QoS 1 retained; event QoS 1. Không sử dụng QoS 2.
5. Telemetry dùng `device_id`, `ts` UTC ISO-8601, `seq`, `zone`, `temp{v,u}`, `hum{v,u}`, `soil{v,u}`, `q`, `fw`. `soil` có thể null khi cảm biến lỗi; thiết bị không phát giá trị NaN như số hợp lệ.

## Điều khiển và hiện diện

1. `cmd_id` là UUID; `exp` là timestamp UTC. Thiết bị bỏ qua lệnh trùng/hết hạn và gửi ack có `cmd_id`, `result`, `state`, `ts`.
2. Backend timeout lệnh sau 30 giây nếu chưa nhận ack. Lệnh hết hạn không được thực thi sau khi reconnect.
3. Giới hạn cứng thời gian mở van chọn 30 giây cho phiên đầu tiên; firmware tự ngắt relay khi hết hạn, không phụ thuộc mạng. Cần xác minh với lưu lượng tưới thực tế trước khi dùng ngoài demo.
4. Thiết bị gửi heartbeat mỗi 10 giây; backend chuyển offline sau 30 giây không có heartbeat. Trạng thái retained/LWT là nguồn bổ trợ khi mất kết nối bất thường.
5. Cấu hình đo/gửi ban đầu giữ chu kỳ hiện có (đọc 2 giây, publish 10 giây), có thể điều chỉnh qua config retained sau khi firmware hỗ trợ.

## RBAC và vùng

1. Admin có quyền toàn cục.
2. Operator được xem/điều khiển/xác nhận cảnh báo trong các zone được gán.
3. Viewer chỉ được đọc trong các zone được gán.
4. Operator/Viewer không được xem zone nào theo mặc định; Admin gán quyền theo zone. Người dùng legacy có role `USER` được xem như Viewer nhưng không được tự động cấp zone.
5. Zone lấy từ `devices.zone` hiện có; bảng quan hệ user-zone bổ sung bằng migration mới. Không xóa/sửa dữ liệu migration cũ.

## Bảo mật và triển khai

1. Môi trường production dùng HTTPS và MQTT TLS 1.2+ cổng 8883, credential riêng từng device và ACL theo nhánh topic. Không dùng broker 1883 anonymous bên ngoài mạng local cô lập.
2. Tạm thời giữ bearer session hiện hữu; chuyển sang cookie HttpOnly cần thay đổi frontend/backend và CSRF đồng bộ, là hạng mục riêng.
3. Retention telemetry thô giữ 30 ngày. Dữ liệu tổng hợp lâu dài sẽ được thêm khi API aggregate được triển khai.
4. Realtime ưu tiên polling 10 giây đang có; chỉ chuyển sang WebSocket/SSE nếu đo được polling không đáp ứng mục tiêu độ trễ/tải.
5. TLS cert, password file, ACL credentials và mật khẩu bootstrap được cấp từ secret/volume bên ngoài Git; repository chỉ chứa cấu hình mẫu không bí mật.

## Phạm vi

- Không đổi framework hoặc phần cứng; giữ React/Vite, Spring Boot, PostgreSQL, Mosquitto và ESP32 hiện có.
- Không triển khai OTA hoặc AI.
- Các lựa chọn timeout van, offline và chuyển giao thức là mặc định an toàn để phát triển, không thay cho kiểm thử phần cứng/đo lưu lượng tại vườn.
