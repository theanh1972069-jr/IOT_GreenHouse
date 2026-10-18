package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.repository.TelemetryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final TelemetryRepository telemetryRepository;
    private final DeviceRepository deviceRepository;
    private final AlertEvaluationService alertEvaluationService;

    @Transactional
    public void processTelemetryData(String deviceId, Telemetry telemetryData) {
        Optional<Device> deviceOpt = deviceRepository.findByDeviceId(deviceId);
        if (deviceOpt.isEmpty()) {
            log.warn("Received telemetry for unknown device: {}", deviceId);
            return;
        }

        Device device = deviceOpt.get();
        if (telemetryData.getZone() != null && !Objects.equals(device.getZone(), telemetryData.getZone())) {
            log.warn("Ignoring telemetry for device {} with unregistered zone {}",
                    deviceId, telemetryData.getZone());
            return;
        }
        String currentStatus = device.getStatus() == null
                ? "" : device.getStatus().toUpperCase(Locale.ROOT);
        if (Set.of("LOCKED", "DECOMMISSIONED").contains(currentStatus)) {
            log.warn("Ignoring telemetry from device {} in status {}", deviceId, currentStatus);
            return;
        }
        if (telemetryData.getSequenceNumber() != null
                && telemetryRepository.existsByDevice_IdAndSequenceNumber(device.getId(), telemetryData.getSequenceNumber())) {
            log.info("Ignoring duplicate telemetry for device {} with seq {}",
                    deviceId, telemetryData.getSequenceNumber());
            return;
        }
        telemetryData.setDevice(device);
        telemetryData.setZone(device.getZone());
        if (telemetryData.getTimestamp() == null) {
            telemetryData.setTimestamp(ZonedDateTime.now());
        }
        telemetryRepository.save(telemetryData);

        device.setLastSeen(ZonedDateTime.now());
        if ("sensor_fault".equalsIgnoreCase(telemetryData.getQuality())) {
            device.setStatus("FAULT");
        } else if (Set.of("", "REGISTERED", "PROVISIONED", "OFFLINE", "FAULT").contains(currentStatus)) {
            device.setStatus("ACTIVE");
        }
        if (telemetryData.getFirmwareVersion() != null) {
            device.setFirmwareVersion(telemetryData.getFirmwareVersion());
        }
        deviceRepository.save(device);

        log.info("Saved telemetry for device: {}", deviceId);

        // Raises an alert when a reading crosses the device's configured threshold.
        alertEvaluationService.evaluate(device, telemetryData);
    }

    @Transactional(readOnly = true)
    public List<Telemetry> getTelemetryByDeviceId(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> telemetryRepository.findByDeviceIdOrderByTimestampDesc(device.getId()))
                .orElseGet(Collections::emptyList);
    }
}
