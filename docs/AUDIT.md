# Báo cáo rà soát IoT Smart Greenhouse

Ngày rà soát: 2026-10-25
Phạm vi: mã nguồn, cấu hình, schema, firmware và test đang có trong repository.

Các phát hiện mô tả trạng thái trước khi áp dụng phần sửa an toàn ở cuối báo cáo.

## Giới hạn và nguồn sự thật

Đã rà soát `docs/Báo cáo tổng hợp Hệ thống IoT Nhà thông minh (Chương 2–4).docx` được cung cấp trong workspace. PDF `docs/ĐỀ_BÀI_ĐỒ_ÁN_KẾT_THÚC_HỌC_PHẦN.pdf` vẫn chưa có, nên chưa thể xác minh nguyên văn tiêu chí mục 4.6/7. Báo cáo DOCX dùng ví dụ topic `sh/home1/...` và miền nhà thông minh; theo quy tắc xung đột trong yêu cầu, không áp dụng tiền tố/vùng hay phần cứng đó: tiếp tục miền nhà kính, giữ tiền tố `greenhouse`, ánh xạ zone thành khu/luống/nhà kính và chỉ lấy các nguyên tắc giao thức/độ tin cậy/bảo mật chung. Các đối chiếu bên dưới cũng dựa trên mã nguồn hiện tại.

## Luồng thực tế đã xác minh từ mã

### Telemetry

1. ESP32 trong `firmware/esp32_telemetry/esp32_telemetry.ino` tạo `greenhouse/{device_id}/telemetry`, đọc DHT22 và ADC đất, rồi publish JSON cũ gồm `temperature`, `humidity`, `soilMoisture`. Chu kỳ đọc hiện đặt 2 giây, publish 10 giây; callback lệnh đăng ký topic `greenhouse/{device_id}/control`.
2. Khi `MQTT_ENABLED=true`, `MqttAdapter` subscribe `greenhouse/+/telemetry` với QoS 1. `TelemetryPayloadParser` đọc schema cũ, bỏ payload JSON sai và từng trường không hợp lệ; chưa kiểm tra `device_id` trong payload, timestamp/đơn vị/chất lượng/seq hay trạng thái thiết bị.
3. `TelemetryService` tìm thiết bị theo ID, tự gán timestamp hiện tại nếu thiếu, lưu bản ghi, cập nhật `last_seen`, rồi gọi `AlertEvaluationService`. Không có unique key chống telemetry gửi trùng; thiết bị chưa đăng ký chỉ bị bỏ qua, còn thiết bị khóa/ngừng vẫn ingest được.
4. `GET /api/telemetry?deviceId=...` trả toàn bộ lịch sử của thiết bị. `Dashboard`, `ControlOverview` và vòng đời thiết bị gọi REST, UI làm mới/poll theo chu kỳ khoảng 10 giây. Không có WebSocket/SSE.

### Điều khiển

1. UI có giao diện quạt/bơm/đèn nhưng các công tắc/nút đang disabled và ghi rõ backend chưa có API gửi lệnh.
2. `CommandRestController` chỉ có `GET /api/commands` để xem lịch sử. Không có API tạo lệnh, publish MQTT, xử lý ack hay timeout.
3. Firmware chỉ subscribe `/control`, tìm chuỗi JSON chứa `"valve":1`/`"valve":0`, không xác minh `cmd_id`/hạn lệnh, không gửi ack; chưa có timer an toàn tự đóng van.

## Kiểm kê cấu trúc

| Thành phần được yêu cầu | Hiện trạng |
|---|---|
| `README.md` | Có nhưng chỉ chứa tiêu đề `# IOT_GreenHouse`; chưa có hướng dẫn kiến trúc/cài đặt/chạy. |
| `firmware/` | Có `esp32_telemetry/` và README riêng. |
| `backend/` | Có Spring Boot, schema JPA, REST, MQTT adapter và test Java. |
| `frontend/` | Có React/Vite; `pages.jsx` chứa nhiều trang trong một file lớn. |
| `database/` | Có `create_tables.sql` và migration `002_user_sessions.sql`; chưa thấy migration quản lý các thay đổi schema khác. |
| `deployment/` | Thiếu; không có Compose hoặc cấu hình Mosquitto. |
| `hardware/` | Thiếu sơ đồ nối dây, danh sách linh kiện và chỗ lưu ảnh prototype. |
| `tests/` (thư mục gốc) | Thiếu. Có test trong `backend/src/test/`; chưa có test frontend/firmware hay kiểm thử tích hợp MQTT. |
| `docs/` | Có báo cáo DOCX được cung cấp và `AUDIT.md`; PDF đề bài còn thiếu, slide/sơ đồ chưa có. |
| `.env.example` | Đã thêm trong lượt sửa này. `.gitignore` bỏ qua `.env` và `.env.*`, ngoại trừ file mẫu. |

## Phát hiện

### A. Frontend

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [High] | `frontend/src/App.jsx:95-96, 128-129` | Form đăng nhập điền sẵn credential demo và công khai thông tin truy cập. Credential mặc định cũng được tạo ở backend; đây là tài khoản dễ đoán khi triển khai. | Bỏ credential khỏi bundle/UI; chỉ cấp tài khoản bootstrap bằng cấu hình triển khai an toàn và yêu cầu đổi mật khẩu. |
| [High] | `frontend/src/api.js:1-71` | Token lưu trong `localStorage`, nên có thể bị đánh cắp qua XSS; API client không đặt timeout, không chuẩn hóa status/error body; 401 xóa session nhưng 403/404/500 chỉ ném thông báo không chuẩn. URL API hỗ trợ `VITE_API_BASE_URL`, nhưng mặc định URL rỗng phụ thuộc reverse proxy. | Ưu tiên cookie `HttpOnly; Secure; SameSite` với bảo vệ CSRF phù hợp; nếu tiếp tục bearer/localStorage cần ghi rõ đánh đổi và tăng bảo vệ XSS. Thêm timeout, định dạng lỗi thống nhất và xử lý theo status; bắt buộc cấu hình URL theo môi trường triển khai. |
| [High] | `frontend/src/pages.jsx:557-595`; `backend/.../controller/CommandRestController.java:29-68` | Trang điều khiển chưa thực hiện được thao tác nào: actuator disabled; backend chỉ đọc lịch sử, không có publish/ack/timeout. Chưa đáp ứng điều khiển có trạng thái pending/done/failed/timeout hay xác nhận thao tác rủi ro. | Chỉ bật điều khiển sau khi hoàn thiện API và firmware ack; yêu cầu xác nhận, chống gửi lặp, hiển thị thời hạn mở van do thiết bị áp dụng. |
| [High] | `frontend/src/App.jsx:408-417`; `frontend/src/pages.jsx:803-973, 1210-1360` | UI có ba vai trò và chặn một số route Admin, nhưng danh sách người dùng hiện chỉ tạo/đổi role; chưa có khóa/mở tài khoản, cập nhật hồ sơ/mật khẩu đầy đủ. Phân quyền UI không thay thế kiểm tra backend; chưa có bộ lọc quyền theo zone trong dữ liệu hiển thị. | Hoàn thiện thao tác theo yêu cầu và map Admin/Operator/Viewer; backend phải là điểm thực thi quyền và zone. |
| [Medium] | `frontend/src/pages.jsx:239-355, 380-557`; `frontend/src/App.jsx:441-520` | Có polling và cleanup interval, nhưng lịch sử telemetry tải toàn bộ rồi lọc phía trình duyệt; không có bộ lọc khoảng thời gian/API paging, thống kê giờ/ngày hay CSV. Dashboard lấy dữ liệu thiết bị đầu tiên. Online/offline suy từ `status`/telemetry chứ chưa có heartbeat; dữ liệu cũ chưa nhất quán được cảnh báo. | API paging/filter và tổng hợp; chọn thiết bị rõ ràng; trình bày last-seen, chất lượng và trạng thái stale/offline từ backend. |
| [Medium] | `frontend/src/pages.jsx:1129-1209` | Cài đặt ngưỡng/tần suất là tùy chọn trình duyệt; lưu `localStorage`, không gọi API cấu hình và không tác động tới thiết bị. Cấu hình từ xa không được publish retained. | Phân biệt tùy chọn UI với cấu hình thiết bị; lưu và audit cấu hình server, publish theo giao thức sau khi chốt. |
| [Medium] | `frontend/src/pages.jsx:1-1426` | Một file gộp nhiều trang, widget, chart và logic API; tăng nguy cơ regressions, khó test. Có nhiều fallback nuốt lỗi riêng trong component; cần xác minh nhất quán loading/error/empty, đặc biệt các request phụ. | Tách theo trang khi sửa tính năng; chuẩn hóa component/API error state và thêm kiểm thử frontend. |
| [Low] | `frontend/package.json:1-19`; `frontend/src/api.js:14-16` | Chưa khai báo lint/test script/dependency; xử lý JSON session lỗi âm thầm (dù xóa session an toàn), không có trạng thái mạng/timeout chung. | Bổ sung kiểm thử phù hợp trong kế hoạch; thể hiện lỗi mạng rõ ràng và dùng API client chung. |

### B. Backend

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [Blocker] | `backend/src/main/java/com/iot/greenhouse/controller/CommandRestController.java:29-68`; `backend/src/main/java/com/iot/greenhouse/adapter/MqttAdapter.java:27-100` | Không có đường đi backend tạo/publish lệnh, nhận ack hoặc timeout. UI/fimware hiện không thể thực hiện giao thức lệnh tin cậy. | Xây dựng API/service lệnh với auth→role→zone→trạng thái thiết bị, `cmd_id`/expiry, lưu pending, publish QoS 1, nhận ack idempotent và job timeout; firmware chỉ báo ack khi xử lý. |
| [Blocker] | `backend/src/main/java/com/iot/greenhouse/adapter/MqttAdapter.java:27-28, 64-70`; `backend/src/main/java/com/iot/greenhouse/service/TelemetryService.java:26-44`; `database/create_tables.sql:145-177` | Adapter chỉ hỗ trợ topic cũ; schema telemetry không có seq/metadata chống trùng. Timestamp thiếu được thay bằng giờ server. Payload không ràng buộc với thiết bị trong topic; trạng thái device không được kiểm tra. Dữ liệu replay/duplicate có thể tạo bản ghi và cảnh báo lặp. | Chốt site/migration trước khi đổi đồng thời firmware/backend; validate topic/schema/device/status/ts/units, thêm seq và unique key + migration; xử lý replay có giới hạn và dedupe. |
| [High] | `backend/src/main/java/com/iot/greenhouse/adapter/TelemetryPayloadParser.java:31-104`; `backend/src/main/java/com/iot/greenhouse/service/TelemetryService.java:26-44` | Parser chỉ nhận trường phẳng schema cũ, không xác thực timestamp ISO8601, `device_id`, đơn vị, quality/firmware, giới hạn nhiệt độ theo miền cảm biến; chỉ loại riêng trường sai. Parser bắt exception JSON là tốt nhưng ingest chưa từ chối thiết bị không Active/đã khóa/thu hồi. | Chuyển parser sang schema đã chốt; validate nghiêm ngặt và log có kiểm soát; chỉ nhận thiết bị Active/được cấp quyền. |
| [High] | `backend/src/main/java/com/iot/greenhouse/config/AuthBootstrap.java:22-48`; `backend/src/main/java/com/iot/greenhouse/config/PasswordConfig.java:10-14`; `backend/src/main/java/com/iot/greenhouse/controller/AuthController.java:34-79` | BCrypt có dùng nhưng cost chưa cấu hình/kiểm thử; khi DB rỗng trước đây tự tạo tài khoản với mật khẩu mặc định đã biết. Login không có rate limit/khóa tạm. Session TTL, logout revoke và lọc user ACTIVE đã có; chưa thấy thu hồi toàn bộ session tại đổi mật khẩu/khóa tài khoản trong luồng controller hiện có. | Loại tài khoản/mật khẩu mặc định; bootstrap bằng secret/one-time provisioning; định cấu hình và kiểm thử BCrypt cost; rate limit/lockout; thu hồi session khi credential/status đổi. |
| [High] | `backend/src/main/java/com/iot/greenhouse/config/AuthInterceptor.java:27-90`; các controller REST trong `backend/src/main/java/com/iot/greenhouse/controller/` | Interceptor bảo vệ `/api/**` trừ login và có `@RequireRole`; đây là middleware tùy biến chứ không phải Spring Security chain. Chưa có kiểm tra zone scope ở service. Một số thao tác trái phép bị từ chối nhưng không ghi audit 403; lỗi 401/403 được tự nối JSON. | Bổ sung authorization theo zone ở service, audit truy cập bị từ chối, response lỗi thống nhất và header bảo mật; chọn/kiểm thử rõ cơ chế CSRF theo kiểu token/cookie. |
| [High] | `backend/src/main/java/com/iot/greenhouse/controller/TelemetryRestController.java:16-23`; `backend/src/main/java/com/iot/greenhouse/service/TelemetryService.java:50-55`; `backend/src/main/java/com/iot/greenhouse/controller/CommandRestController.java:31-44` | Telemetry API trả toàn bộ lịch sử, không lọc thời gian/paging/export/tổng hợp. API này và một số danh sách khác không giới hạn zone mà người gọi được xem. | Thêm paging/filter date/device + scope zone; API tổng hợp và CSV; giới hạn kích thước/truy vấn. |
| [High] | `backend/src/main/java/com/iot/greenhouse/service/AlertEvaluationService.java:30-92`; `backend/src/main/java/com/iot/greenhouse/controller/AlertRestController.java:77-113` | Alert engine hiện xét nhiệt độ cao và độ ẩm đất thấp, dedupe khi còn unresolved; thiếu độ ẩm không khí, sensor fault, offline, hysteresis/cooldown và audit cho auto-rule. Zone scope không được xét ở thao tác acknowledge/resolve. | Bổ sung nguồn cảnh báo theo dữ liệu đã thống nhất, dedupe/cooldown/hysteresis và audit nguyên nhân tự động; áp dụng scope zone cho mọi thao tác. |
| [High] | `backend/src/main/java/com/iot/greenhouse/controller/DeviceRestController.java:62-112`; `backend/src/main/java/com/iot/greenhouse/service/DeviceService.java:35-48`; `database/create_tables.sql:69-104` | Có đăng ký/thay trạng thái và các status enum, nhưng API status nhận mọi trạng thái hợp lệ thay vì chuyển trạng thái hợp lệ; chưa có credential/ACL MQTT theo thiết bị, hw version, lifecycle audit đầy đủ hoặc thu hồi broker credential/ngắt client. | State machine có chuyển trạng thái rõ; audit mọi chuyển đổi; provision/disable MQTT identity và ACL; lưu hw/fw; không nhận ingest/lệnh khi khóa/thu hồi. |
| [Medium] | `backend/src/main/java/com/iot/greenhouse/controller/DeviceConfigurationRestController.java:29-85`; `backend/src/main/java/com/iot/greenhouse/config/MqttConfig.java:14-49` | API lưu cấu hình DB và audit, nhưng không publish `config` retained; MQTT client dùng một credential backend chung, không có TLS bắt buộc. | Publish retained QoS 1 sau khi commit; cấu hình TLS/CA và broker ACL/credential riêng từng thiết bị. |
| [Medium] | `database/create_tables.sql:145-177`; `database/migrations/` | Có index `(device_id, timestamp)` và retention 30 ngày; không có unique chống trùng telemetry. Migration hiện chỉ có `002_user_sessions.sql`, thiếu chuỗi migration đầy đủ cho schema hiện hành/đổi schema an toàn. Không thấy bảng tổng hợp dữ liệu lâu dài. | Thêm migration đánh số cho DB đang tồn tại; unique/index thích hợp; giữ aggregate lâu hơn theo quyết định retention. `ddl-auto=none` là lựa chọn an toàn đang có. |
| [Medium] | `backend/src/main/java/com/iot/greenhouse/controller/UserRestController.java:87-160`; `backend/src/main/java/com/iot/greenhouse/controller/AuthController.java:82-92` | Có tạo user và logout, nhưng chưa có endpoint đổi trạng thái user, cập nhật hồ sơ/đổi mật khẩu và thu hồi session gắn với các thao tác đó. | Hoàn thiện lifecycle user và audit; revoke session khi khóa/đổi mật khẩu. |
| [Low] | `backend/src/main/java/com/iot/greenhouse/config/AuthInterceptor.java:83-89`; nhiều controller | Một số endpoint trả `ResponseEntity` ad-hoc; không thấy `@ControllerAdvice` định dạng lỗi thống nhất. Thông báo role denial không audit; một số code có thể trả chi tiết exception. | Chuẩn hóa error envelope/status bằng advice; không đưa stack trace/chi tiết nội bộ cho client; audit denial mà không ghi token/password. |

### C. Firmware

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [Blocker] | `firmware/esp32_telemetry/esp32_telemetry.ino:113-139, 186-205, 308-309` | Topic/payload là giao thức cũ (`greenhouse/{id}/...`, `/control`, payload phẳng); không có site, heartbeat/status/event, seq, timestamp, quality, fw hay command ack. | Chỉ migrate đồng bộ sau khi chốt site và kế hoạch tương thích; bổ sung schema/ack sau khi giao thức được thống nhất. |
| [High] | `firmware/esp32_telemetry/esp32_telemetry.ino:63-65, 176-204, 272-317` | MQTT server mặc định IP riêng `192.168.1.220`, port nhập được; `mqtt.connect(device_id)` không truyền username/password riêng, TLS hay Last Will. Retry là chu kỳ cố định 5 giây, không backoff tăng dần. WiFiManager/NVS có dùng để lưu Wi-Fi, device ID, broker; chưa có quản lý MQTT secret theo thiết bị. | Cấu hình môi trường provision an toàn; MQTT identity riêng, TLS/ACL và LWT; exponential backoff có jitter; bỏ broker mặc định khỏi firmware phát hành. |
| [High] | `firmware/esp32_telemetry/esp32_telemetry.ino:130-145, 176-190, 339-365` | Callback nhận lệnh bằng tìm chuỗi JSON, không xác thực cấu trúc/idempotency/expiry. Van không có timeout tự đóng. Điều khiển tự động tại node có ngưỡng cứng nhưng không phát sinh event/audit ở backend. | Parser JSON/schema; chống trùng và expiry; timer đóng van ở firmware với giới hạn đã xác nhận; phản hồi ack/event. |
| [Medium] | `firmware/esp32_telemetry/esp32_telemetry.ino:97-99, 339-354` | Chu kỳ đọc DHT22 2 giây và kiểm tra NaN lúc lấy mẫu đã có; khi đọc lỗi, firmware giữ giá trị tốt trước đó nhưng payload không có timestamp NTP, seq hay quality để UI phân biệt mẫu cũ với mẫu mới. | Giữ kiểm tra NaN; thêm thời điểm/seq/quality theo schema đã chốt để không trình bày số liệu cũ như mẫu mới. |
| [Medium] | `firmware/esp32_telemetry/esp32_telemetry.ino:70-86, 231-242, 352-355` | Relay kích mức thấp và setup đặt HIGH trước OUTPUT — mặc định boot có vẻ an toàn. ADC dùng GPIO 34 (ADC1), phù hợp Wi-Fi; cảm biến đất chưa thấy điều khiển nguồn riêng hay hiệu chuẩn khô/ướt có tài liệu. Không thể xác nhận an toàn mạch tải/nguồn/diode chỉ từ firmware. | Giữ trạng thái relay fail-safe; bổ sung hiệu chuẩn và tài liệu phần cứng, kiểm tra độc lập điện áp/tải/diode; tuyệt đối không đấu điện lưới trong demo. |

### D. Triển khai và bí mật

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [Blocker] | `render.yaml:19-21`; `backend/src/main/resources/application.properties:22-25`; `deployment/` (thiếu) | Render tắt MQTT mặc định; khi bật, cấu hình mặc định là TCP/1883. Không có Compose/Mosquitto config, xác thực/ACL/TLS/healthcheck/volume. Broker public 1883 không xác thực sẽ cho phép đọc giả mạo telemetry/lệnh, tùy ACL broker. | Không công khai broker 1883; bổ sung triển khai local với broker có auth/ACL/TLS, healthcheck và dữ liệu bền; buộc TLS cho môi trường không tin cậy. |
| [High] | `render.yaml:28-29`; `backend/src/main/resources/application.properties:41` | `CORS_ALLOWED_ORIGINS="*"` trong manifest và mặc định backend cho phép pattern `https://*.onrender.com`; origin quá rộng trong môi trường thật, trong khi CORS bật credentials. | Khai báo allowlist origin cụ thể bằng secret/env triển khai, không wildcard production. |
| [High] | `backend/src/main/java/com/iot/greenhouse/config/AuthBootstrap.java:22-48`; `frontend/src/App.jsx:95-96`; `render.yaml` | Có credential mặc định công khai trong source/UI. `render.yaml` không chứa mật khẩu DB cứng mà tham chiếu managed database; tuy nhiên cấu hình CORS không an toàn. Chưa có `.env.example`, rà soát lịch sử Git hay kết quả scanner bí mật trong lượt này. | Bỏ credential demo; chuyển bootstrap secret qua biến môi trường an toàn; thêm `.env.example` chỉ có placeholder; quét lịch sử và secrets trước khi phát hành. |
| [Medium] | `backend/src/main/resources/application.properties:1-2, 22-45`; `render.yaml:1-53` | `spring.profiles.active=local` bị đặt mặc định trong cấu hình chung; Render vô hiệu MQTT, nên không thể demo end-to-end ở trạng thái hiện tại. Chưa có stack local đầy đủ. | Tách profile local/production, document các env cần thiết và thêm Compose để demo toàn bộ luồng. |

### E. Kiểm thử

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [High] | `backend/src/test/java/` (các test hiện hữu); `tests/` (thiếu) | Có test parser, authorization/device, session/audit, alert, retention và một số hồi quy role; chưa có coverage tự động chứng minh đầy đủ checklist: Viewer/User 403 + audit, token sai/hết hạn, revoked device ingest, duplicate DB constraint, command ack/timeout, offline detection, alert acknowledge. Không thấy frontend/firmware/MQTT integration test. | Tạo bộ test theo đề mục 7; ưu tiên kiểm thử backend security/ingest/command và integration MQTT, thêm test frontend cho luồng chính. |
| [Medium] | `backend/src/test/resources/application.properties`; `backend/src/test/java/com/iot/greenhouse/` | Một số test dùng Testcontainers PostgreSQL; cần Docker để chạy. Chưa có báo cáo chạy test trong lượt audit này. | Ghi rõ prerequisite, có profile/runner test tích hợp và chạy test trước phát hành. |

### F. Tài liệu

| Mức độ | Tệp:dòng | Mô tả và vì sao chưa đạt | Hướng sửa |
|---|---|---|---|
| [High] | `README.md:1`; `docs/` | README chỉ có tiêu đề; thiếu kiến trúc, cài đặt, chạy và tài khoản/demo an toàn. PDF đề bài chưa có trong repo; báo cáo DOCX đã được cung cấp và lưu trong `docs/`. | Viết README tổng quan/hướng dẫn; bổ sung bản đề bài được phép lưu trong repo. |
| [Medium] | `hardware/` (thiếu); `firmware/esp32_telemetry/README.md` | Không có sơ đồ nối dây, BOM, hiệu chuẩn cảm biến hay placeholder ảnh prototype; firmware README chưa thay thế tài liệu phần cứng. | Tạo hardware docs và chừa placeholder ảnh để chủ đồ án bổ sung ảnh thật. |
| [Medium] | `docs/`, `deployment/`, `tests/` | `docs/` hiện có báo cáo DOCX và audit; slide/sơ đồ chưa có. `deployment/` và `tests/` cấp repo vẫn thiếu. | Bổ sung tài liệu sau khi xác nhận quyết định giao thức và môi trường triển khai. |

## Câu hỏi cần xác nhận

Không tự chọn giá trị hoặc âm thầm đổi giao thức vì firmware và backend cần được triển khai đồng bộ:

1. Giá trị `{site}` cần dùng trong topic là gì (ví dụ một site cố định như `site-01`, hay mã site lấy từ cấu hình khi provision)?
2. Khi chuyển sang topic mới, có cần backend nhận tạm topic/schema cũ `greenhouse/{device_id}/telemetry` và firmware hỗ trợ publish đồng thời để migrate dần không, hay chấp nhận đổi firmware + backend cùng lúc và ngừng schema cũ?
3. Giới hạn thời gian mở van tối đa cần chốt là bao nhiêu giây/phút? Firmware phải enforce giới hạn cứng tại thiết bị.
4. Phân quyền Viewer theo zone cần lấy danh sách zone từ đâu và ai được gán/quản lý scope? Hiện schema không có bảng user-zone.
5. Với triển khai MQTT bảo mật, có thể mount/đưa Mosquitto password file, ACL và chứng chỉ TLS từ secret/volume bên ngoài Compose không? Không nên sinh hoặc commit khóa/chứng chỉ production vào repo.
6. Có thể cung cấp PDF đề bài còn thiếu để xác minh chính xác các mục chấm 4.6 và 7 không?

## Thứ tự xử lý khuyến nghị

1. Chốt câu hỏi giao thức và provision; sau đó cập nhật firmware/backend đồng bộ, thêm migration và test tương thích.
2. Hoàn thiện xác thực/RBAC theo zone, vòng đời user/device, ingest idempotent, command ack/timeout và online/offline.
3. Bổ sung API lịch sử/tổng hợp/CSV, alert đầy đủ, cấu hình retained và UI trạng thái/lỗi tương ứng.
4. Bổ sung Compose/Mosquitto an toàn, `.env.example`, README/hardware/test docs; chạy test và quét secret trên lịch sử Git trước khi demo/phát hành.

## Sửa an toàn đã thực hiện sau audit

- Bỏ giá trị đăng nhập demo khỏi form frontend; tài khoản khởi tạo không còn mật khẩu mặc định. Khi database chưa có user, backend yêu cầu `BOOTSTRAP_ADMIN_PASSWORD` đủ độ dài và độ dài UTF-8 tương thích BCrypt; giá trị này cần được cấp qua secret của môi trường chạy.
- Đặt BCrypt strength mặc định là 12, có thể cấu hình bằng `BCRYPT_STRENGTH`.
- Thay CORS wildcard của Render bằng origin frontend cụ thể; backend mặc định chỉ cho origin localhost, có thể ghi đè bằng allowlist `CORS_ALLOWED_ORIGINS`.
- API client frontend có timeout mặc định 15 giây, đưa status/body vào lỗi có cấu trúc và vẫn xóa session/điều hướng đăng nhập lại khi nhận 401.
- Thêm `.env.example` chỉ chứa placeholder và quy tắc `.gitignore` để không đưa các file `.env.*` khác lên Git.
- Cập nhật hai hướng dẫn chạy để bỏ credential mẫu và cảnh báo rõ broker local anonymous không được phơi ra Internet.
- Thêm phân quyền Admin/Operator/Viewer theo zone với migration `003_user_zone_access.sql`; non-admin không được cấp zone mặc định. API thiết bị/telemetry/cấu hình/cảnh báo/lệnh lọc hoặc từ chối ngoài phạm vi và ghi audit khi truy cập trực tiếp bị từ chối. Admin có API/UI gán zone.
- Ẩn nút đăng ký/chuyển trạng thái thiết bị khỏi người không phải Admin; role legacy `USER` được xem như Viewer ở các endpoint read có kiểm soát.
- Bổ sung tự cập nhật tên/email, đổi mật khẩu có xác thực mật khẩu hiện tại, thu hồi toàn bộ session sau đổi mật khẩu và khóa/mở tài khoản Admin; ngăn vô hiệu hóa Admin hoạt động cuối cùng.

Token frontend vẫn nằm trong `localStorage`; đây là rủi ro XSS còn tồn tại. Chuyển sang cookie `HttpOnly` cần thay đổi đồng bộ cơ chế xác thực/CORS và bổ sung CSRF, chưa được thực hiện trong lượt sửa này.

Quét bằng `rg` không còn tìm thấy credential mặc định trong working tree. `gitleaks` không được cài đặt. Kiểm tra Git history cho thấy credential mặc định cũ từng được commit; không viết lại lịch sử trong lượt này vì sẽ thay đổi commit đã chia sẻ. Cần vô hiệu hóa/đổi credential trên mọi database còn dùng giá trị cũ và phối hợp làm sạch history trước khi chia sẻ repository công khai.

### Kết quả xác minh

- `frontend`: `npm run build` thành công.
- `backend`: `mvnw test` BUILD SUCCESS, không có test thất bại; 56 test bị skip vì môi trường không có Docker/Testcontainers. Test unit bootstrap credential và zone access chạy thành công; kiểm thử RBAC tích hợp zone chưa chạy do thiếu Docker.
- `git diff --check`: không phát hiện whitespace error.
- Secret scanner: `gitleaks` không cài sẵn; `rg` xác nhận working tree không còn giá trị credential mặc định, nhưng lịch sử Git vẫn có các commit cũ nêu trên.
