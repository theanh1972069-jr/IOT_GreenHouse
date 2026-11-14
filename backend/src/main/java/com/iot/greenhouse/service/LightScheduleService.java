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

    /**
     * Chạy mỗi phút đầu tiên của phút (giây 00).
     * So sánh giờ:phút hiện tại với turnOnTime và turnOffTime của từng lịch.
     */
    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void evaluateSchedules() {
        LocalTime now = LocalTime.now().withSecond(0).withNano(0);
        List<LightSchedule> schedules = lightScheduleRepository.findByEnabledTrue();

        for (LightSchedule schedule : schedules) {
            String deviceId = schedule.getDevice().getDeviceId();
            String topic = "greenhouse/" + deviceId + "/control";

            LocalTime onTime  = schedule.getTurnOnTime().withSecond(0).withNano(0);
            LocalTime offTime = schedule.getTurnOffTime().withSecond(0).withNano(0);

            if (now.equals(onTime)) {
                log.info("[LightSchedule] Bật đèn thiết bị {} theo lịch \"{}\" lúc {}",
                        deviceId, schedule.getLabel(), onTime);
                mqttPublisherService.publish(topic, "{\"light\":1}");
                updateLightState(deviceId, true);
            } else if (now.equals(offTime)) {
                log.info("[LightSchedule] Tắt đèn thiết bị {} theo lịch \"{}\" lúc {}",
                        deviceId, schedule.getLabel(), offTime);
                mqttPublisherService.publish(topic, "{\"light\":0}");
                updateLightState(deviceId, false);
            }
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

