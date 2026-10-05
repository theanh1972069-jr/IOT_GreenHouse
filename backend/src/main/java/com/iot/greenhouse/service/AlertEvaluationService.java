package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.DeviceConfiguration;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Raises alerts from telemetry readings against a device's configured thresholds.
 *
 * <p>Without this the alerts table was never written to, so the alerts screen had no
 * data source at all. A breach only creates a new alert when the device has no
 * unresolved alert of the same type, which keeps a device that keeps reporting a bad
 * reading from producing one alert per message.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {

    static final String TYPE_TEMPERATURE_HIGH = "TEMPERATURE_HIGH";
    static final String TYPE_SOIL_MOISTURE_LOW = "SOIL_MOISTURE_LOW";

    /** Statuses that still count as "unresolved" for the duplicate guard. */
    private static final List<String> UNRESOLVED = List.of("OPEN", "ACKNOWLEDGED");

    private static final BigDecimal DEFAULT_TEMPERATURE_HIGH = new BigDecimal("35.00");
    private static final BigDecimal DEFAULT_SOIL_MOISTURE_LOW = new BigDecimal("30.00");

    /** Extra deviation that escalates a breach from HIGH to CRITICAL. */
    private static final BigDecimal CRITICAL_TEMPERATURE_DELTA = new BigDecimal("5");
    private static final BigDecimal CRITICAL_SOIL_DELTA = new BigDecimal("15");

    private final AlertRepository alertRepository;
    private final DeviceConfigurationRepository configurationRepository;

    @Transactional
    public void evaluate(Device device, Telemetry telemetry) {
        DeviceConfiguration configuration = configurationRepository.findByDeviceId(device.getId())
                .orElse(null);

        BigDecimal temperatureHigh = configuration == null || configuration.getTemperatureHigh() == null
                ? DEFAULT_TEMPERATURE_HIGH : configuration.getTemperatureHigh();
        BigDecimal soilMoistureLow = configuration == null || configuration.getSoilMoistureLow() == null
                ? DEFAULT_SOIL_MOISTURE_LOW : configuration.getSoilMoistureLow();

        if (telemetry.getTemperature() != null
                && telemetry.getTemperature().compareTo(temperatureHigh) > 0) {
            BigDecimal over = telemetry.getTemperature().subtract(temperatureHigh);
            raise(device, TYPE_TEMPERATURE_HIGH,
                    over.compareTo(CRITICAL_TEMPERATURE_DELTA) >= 0 ? "CRITICAL" : "HIGH",
                    String.format("Nhiệt độ %s°C vượt ngưỡng %s°C.",
                            telemetry.getTemperature().setScale(1, RoundingMode.HALF_UP),
                            temperatureHigh.setScale(1, RoundingMode.HALF_UP)));
        }

        if (telemetry.getSoilMoisture() != null
                && telemetry.getSoilMoisture().compareTo(soilMoistureLow) < 0) {
            BigDecimal under = soilMoistureLow.subtract(telemetry.getSoilMoisture());
            raise(device, TYPE_SOIL_MOISTURE_LOW,
                    under.compareTo(CRITICAL_SOIL_DELTA) >= 0 ? "HIGH" : "MEDIUM",
                    String.format("Độ ẩm đất %s%% thấp hơn ngưỡng %s%%.",
                            telemetry.getSoilMoisture().setScale(1, RoundingMode.HALF_UP),
                            soilMoistureLow.setScale(1, RoundingMode.HALF_UP)));
        }
    }

    private void raise(Device device, String type, String severity, String message) {
        if (alertRepository.existsByDevice_IdAndTypeInAndStatusIn(device.getId(), List.of(type), UNRESOLVED)) {
            log.debug("Skipping duplicate {} alert for device {}", type, device.getDeviceId());
            return;
        }
        alertRepository.save(Alert.builder()
                .device(device)
                .type(type)
                .severity(severity)
                .message(message)
                .status("OPEN")
                .build());
        log.info("Raised {} alert ({}) for device {}", type, severity, device.getDeviceId());
    }
}
