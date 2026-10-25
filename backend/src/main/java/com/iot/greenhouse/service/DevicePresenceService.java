package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DevicePresenceService {

    private static final String OFFLINE_ALERT = "DEVICE_OFFLINE";
    private static final List<String> UNRESOLVED = List.of("OPEN", "ACKNOWLEDGED");

    private final DeviceRepository deviceRepository;
    private final AlertRepository alertRepository;
    private final AuditLogService auditLogService;

    @Value("${app.device.offline-timeout-seconds:30}")
    private long offlineTimeoutSeconds;

    @Scheduled(fixedDelayString = "${app.device.offline-check-interval-ms:10000}",
            initialDelayString = "${app.device.offline-check-initial-delay-ms:10000}")
    @Transactional
    public int markStaleDevicesOffline() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime cutoff = now.minusSeconds(offlineTimeoutSeconds);
        List<Device> staleDevices = deviceRepository.findActiveDevicesNotSeenSince(cutoff);
        for (Device device : staleDevices) {
            device.setStatus("OFFLINE");
            if (!alertRepository.existsByDevice_IdAndTypeInAndStatusIn(
                    device.getId(), List.of(OFFLINE_ALERT), UNRESOLVED)) {
                alertRepository.save(Alert.builder()
                        .device(device)
                        .type(OFFLINE_ALERT)
                        .severity("HIGH")
                        .message("Thiết bị không gửi telemetry trong thời hạn cho phép.")
                        .status("OPEN")
                        .build());
            }
            auditLogService.record(null, null, "DEVICE_OFFLINE_DETECTED", "Device",
                    device.getDeviceId(), Map.of("rule", "auto-rule", "timeoutSeconds", offlineTimeoutSeconds));
        }
        if (!staleDevices.isEmpty()) {
            deviceRepository.saveAll(staleDevices);
            log.info("Marked {} device(s) offline after {} seconds without telemetry",
                    staleDevices.size(), offlineTimeoutSeconds);
        }
        return staleDevices.size();
    }

    @Transactional
    public void markOnline(Device device) {
        if (!"OFFLINE".equalsIgnoreCase(device.getStatus())) {
            return;
        }
        device.setStatus("ACTIVE");
        deviceRepository.save(device);
        ZonedDateTime now = ZonedDateTime.now();
        List<Alert> unresolved = alertRepository.findByDevice_IdAndTypeAndStatusIn(
                device.getId(), OFFLINE_ALERT, UNRESOLVED);
        unresolved.forEach(alert -> {
            alert.setStatus("RESOLVED");
            alert.setResolvedAt(now);
        });
        alertRepository.saveAll(unresolved);
        auditLogService.record(null, null, "DEVICE_ONLINE_RECOVERED", "Device", device.getDeviceId(),
                Map.of("rule", "auto-rule", "resolvedOfflineAlerts", unresolved.size()));
    }
}
