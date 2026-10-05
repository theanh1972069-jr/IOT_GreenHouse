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
import java.util.Optional;

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
        telemetryData.setDevice(device);
        if (telemetryData.getTimestamp() == null) {
            telemetryData.setTimestamp(ZonedDateTime.now());
        }
        telemetryRepository.save(telemetryData);

        device.setLastSeen(ZonedDateTime.now());
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
