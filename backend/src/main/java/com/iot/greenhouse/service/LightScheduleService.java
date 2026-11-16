package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.LightSchedule;
import com.iot.greenhouse.repository.LightScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dịch vụ đồng bộ cấu hình lịch chiếu sáng xuống ESP32.
 * Thiết bị ESP32 tự quản lý đồng hồ phần cứng (NTP/Epoch) và tự động bật/tắt đèn theo khung giờ,
 * hoạt động độc lập ngay cả khi mất kết nối mạng.
 * Web và máy chủ chỉ đóng vai trò cấu hình thời gian bật/tắt (lightOn, lightOff).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightScheduleService {

    private final LightScheduleRepository lightScheduleRepository;
    private final MqttPublisherService mqttPublisherService;

    /**
     * Đồng bộ lịch bật/tắt đèn và thời gian Epoch của máy chủ xuống ESP32 qua MQTT.
     */
    public void syncDeviceLightScheduleToMqtt(String deviceId) {
        try {
            var schedules = lightScheduleRepository.findByDevice_DeviceIdOrderByTurnOnTimeAsc(deviceId);
            if (!schedules.isEmpty()) {
                var active = schedules.stream().filter(LightSchedule::getEnabled).findFirst().orElse(schedules.get(0));
                String topic = "greenhouse/" + deviceId + "/control";
                long epoch = System.currentTimeMillis() / 1000L;
                String payload = String.format("{\"lightOn\":\"%s\",\"lightOff\":\"%s\",\"epoch\":%d}",
                        active.getTurnOnTime().toString(), active.getTurnOffTime().toString(), epoch);
                mqttPublisherService.publish(topic, payload);
                log.info("[LightSchedule] Đã đồng bộ lịch đèn xuống ESP32 {}: lightOn={}, lightOff={}, epoch={}",
                        deviceId, active.getTurnOnTime(), active.getTurnOffTime(), epoch);
            }
        } catch (Exception e) {
            log.warn("Could not sync light schedule to MQTT for {}: {}", deviceId, e.getMessage());
        }
    }
}
