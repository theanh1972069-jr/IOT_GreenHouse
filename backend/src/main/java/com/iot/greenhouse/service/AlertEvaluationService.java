package com.iot.greenhouse.service;

import com.iot.greenhouse.entity.Alert;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.DeviceConfiguration;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.AlertRepository;
import com.iot.greenhouse.repository.DeviceConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Đánh giá và sinh cảnh báo cho thiết bị dựa trên telemetry và trạng thái vận hành.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluationService {

    public static final String TYPE_TEMPERATURE_HIGH = "TEMPERATURE_HIGH";
    public static final String TYPE_SOIL_MOISTURE_LOW = "SOIL_MOISTURE_LOW";
    public static final String TYPE_MANUAL_MODE = "MANUAL_MODE";

    /** Trạng thái chưa giải quyết để chống trùng lặp cảnh báo */
    private static final List<String> UNRESOLVED = List.of("OPEN");

    private static final BigDecimal DEFAULT_TEMPERATURE_HIGH = new BigDecimal("35.00");
    private static final BigDecimal DEFAULT_SOIL_MOISTURE_LOW = new BigDecimal("30.00");

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

        // Kiểm tra xem thiết bị có đang có cảnh báo MANUAL_MODE ở trạng thái OPEN hay không
        boolean isManualModeOpen = alertRepository.existsByDevice_IdAndTypeInAndStatusIn(
                device.getId(), List.of(TYPE_MANUAL_MODE), UNRESOLVED);

        // Quy tắc: Nếu đang ở MANUAL (có cảnh báo MANUAL_MODE OPEN) -> Mức CRITICAL (Nghiêm trọng)
        //          Nếu đang ở AUTO -> Mức WARNING (Cảnh báo)
        String breachSeverity = isManualModeOpen ? "CRITICAL" : "WARNING";

        if (telemetry.getTemperature() != null
                && telemetry.getTemperature().compareTo(temperatureHigh) > 0) {
            raise(device, TYPE_TEMPERATURE_HIGH, breachSeverity, "FAN", "ON",
                    String.format("Nhiệt độ %s°C vượt ngưỡng %s°C.",
                            telemetry.getTemperature().setScale(1, RoundingMode.HALF_UP),
                            temperatureHigh.setScale(1, RoundingMode.HALF_UP)));
        }

        if (telemetry.getSoilMoisture() != null
                && telemetry.getSoilMoisture().compareTo(soilMoistureLow) < 0) {
            raise(device, TYPE_SOIL_MOISTURE_LOW, breachSeverity, "VALVE", "ON",
                    String.format("Độ ẩm đất %s%% thấp hơn ngưỡng %s%%.",
                            telemetry.getSoilMoisture().setScale(1, RoundingMode.HALF_UP),
                            soilMoistureLow.setScale(1, RoundingMode.HALF_UP)));
        }
    }

    /**
     * Tạo cảnh báo loại MANUAL_MODE ngay khi người dùng chuyển sang MANUAL hoặc bật thiết bị thủ công.
     */
    @Transactional
    public void raiseManualModeAlert(Device device) {
        raise(device, TYPE_MANUAL_MODE, "WARNING", "MODE", "AUTO",
                "Thiết bị đang ở chế độ điều khiển thủ công. Hãy bật lại chế độ tự động (AUTO) để tránh quên.");
    }

    /**
     * Tự động giải quyết cảnh báo MANUAL_MODE khi thiết bị quay trở lại chế độ AUTO.
     */
    @Transactional
    public void resolveManualModeAlert(Device device, User actor) {
        List<Alert> manualAlerts = alertRepository.findAll((root, query, cb) -> cb.and(
                cb.equal(root.get("device").get("id"), device.getId()),
                cb.equal(root.get("type"), TYPE_MANUAL_MODE),
                cb.equal(root.get("status"), "OPEN")
        ));
        for (Alert a : manualAlerts) {
            a.setStatus("PROCESSED");
            a.setProcessedAt(ZonedDateTime.now());
            a.setProcessedBy(actor);
            alertRepository.save(a);
        }
    }

    private void raise(Device device, String type, String severity, String targetActuator, String targetState, String message) {
        if (alertRepository.existsByDevice_IdAndTypeInAndStatusIn(device.getId(), List.of(type), UNRESOLVED)) {
            log.debug("Skipping duplicate {} alert for device {}", type, device.getDeviceId());
            return;
        }
        alertRepository.save(Alert.builder()
                .device(device)
                .type(type)
                .severity(severity)
                .targetActuator(targetActuator)
                .targetState(targetState)
                .message(message)
                .status("OPEN")
                .build());
        log.info("Raised {} alert ({}) for device {} [action: {}/{}]",
                type, severity, device.getDeviceId(), targetActuator, targetState);
    }
}
