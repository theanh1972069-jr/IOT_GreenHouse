package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.LightSchedule;
import com.iot.greenhouse.repository.LightScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

/**
 * Dịch vụ tự động bật/tắt đèn quang hợp theo lịch cài đặt.
 *
 * <p>Mỗi phút một lần, service kiểm tra tất cả lịch đang bật (enabled=true).
 * Nếu giờ hiện tại trùng với giờ bật/tắt (trong cùng phút), gửi lệnh MQTT tương ứng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightScheduleService {

    private final LightScheduleRepository lightScheduleRepository;
    private final MqttPublisherService mqttPublisherService;
    private final DeviceLiveService deviceLiveService;

    public static final java.time.ZoneId VIETNAM_ZONE = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    /**
     * Chạy định kỳ mỗi 5 giây.
     * So sánh thời gian hiện tại (giờ Việt Nam GMT+7) với khung giờ bật/tắt (hỗ trợ cả qua đêm):
     * Nếu thời gian hiện tại nằm trong khoảng [onTime, offTime) thì đảm bảo đèn BẬT,
     * ngoài khoảng này thì tắt đèn.
     * Ví dụ: Đặt 19:00 -> 07:00, dù mở lúc 20:00 (8h tối) hệ thống vẫn tự động bật đèn ngay lập tức nếu chưa bật.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void evaluateSchedules() {
        LocalTime now = LocalTime.now(VIETNAM_ZONE).withSecond(0).withNano(0);
        List<LightSchedule> schedules = lightScheduleRepository.findByEnabledTrue();

        for (LightSchedule schedule : schedules) {
            String deviceId = schedule.getDevice().getDeviceId();
            String topic = "greenhouse/" + deviceId + "/control";

            LocalTime onTime  = schedule.getTurnOnTime().withSecond(0).withNano(0);
            LocalTime offTime = schedule.getTurnOffTime().withSecond(0).withNano(0);

            boolean shouldBeOn = isTimeInWindow(now, onTime, offTime);
            var liveStatus = deviceLiveService.getOrCreateLiveStatus(deviceId);

            // Khi ở chế độ tự động, kiểm tra và duy trì trạng thái đèn trong suốt khung giờ
            if (liveStatus != null && !liveStatus.isAutoMode()) {
                continue; // Trong chế độ thủ công, không can thiệp đè lên người dùng
            }

            boolean currentLightState = false;
            if (liveStatus != null && liveStatus.getActuators() != null) {
                currentLightState = liveStatus.getActuators().stream()
                        .filter(a -> a.id().equalsIgnoreCase("light"))
                        .findFirst()
                        .map(com.iot.greenhouse.model.DeviceLiveStatus.ActuatorLiveView::state)
                        .orElse(false);
            }

            // Nếu trạng thái hiện tại chưa khớp với khung giờ (ví dụ mở máy lúc 8h tối mà đèn chưa bật) -> BẬT NGAY!
            if (shouldBeOn != currentLightState) {
                log.info("[LightSchedule] Thiết bị {}: Giờ VN hiện tại {} -> {} đèn (khung giờ {} -> {})",
                        deviceId, now, shouldBeOn ? "BẬT" : "TẮT", onTime, offTime);
                mqttPublisherService.publish(topic, String.format("{\"light\":%d}", shouldBeOn ? 1 : 0));
                updateLightState(deviceId, shouldBeOn);
            }
        }
    }

    public void syncDeviceLightScheduleToMqtt(String deviceId) {
        try {
            var schedules = lightScheduleRepository.findByDevice_DeviceIdOrderByTurnOnTimeAsc(deviceId);
            if (!schedules.isEmpty()) {
                var active = schedules.stream().filter(LightSchedule::getEnabled).findFirst().orElse(schedules.get(0));
                String topic = "greenhouse/" + deviceId + "/control";
                String payload = String.format("{\"lightOn\":\"%s\",\"lightOff\":\"%s\"}",
                        active.getTurnOnTime().toString(), active.getTurnOffTime().toString());
                mqttPublisherService.publish(topic, payload);
            }
        } catch (Exception e) {
            log.warn("Could not sync light schedule to MQTT for {}: {}", deviceId, e.getMessage());
        }
    }

    public static boolean isTimeInWindow(LocalTime now, LocalTime onTime, LocalTime offTime) {
        if (onTime == null || offTime == null || onTime.equals(offTime)) return false;
        if (onTime.isBefore(offTime)) {
            return !now.isBefore(onTime) && now.isBefore(offTime);
        } else {
            // Cross-midnight window: e.g. 19:00 -> 07:00
            return !now.isBefore(onTime) || now.isBefore(offTime);
        }
    }

    private void updateLightState(String deviceId, boolean state) {
        try {
            var liveStatus = deviceLiveService.getOrCreateLiveStatus(deviceId);
            if (liveStatus != null && liveStatus.getActuators() != null) {
                var updated = liveStatus.getActuators().stream()
                        .map(a -> a.id().equalsIgnoreCase("light")
                                ? new com.iot.greenhouse.model.DeviceLiveStatus.ActuatorLiveView(
                                        a.id(), a.name(), a.pin(), a.type(),
                                        state,
                                        state ? a.ratedPowerW() : 0.0,
                                        a.ratedPowerW())
                                : a)
                        .toList();
                liveStatus.setActuators(new java.util.ArrayList<>(updated));
                liveStatus.recalculateTotalPower();
            }
        } catch (Exception e) {
            log.warn("[LightSchedule] Không thể cập nhật trạng thái live cho {}: {}", deviceId, e.getMessage());
        }
    }
}

