package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.DeviceConfiguration;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.DeviceConfigurationRepository;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/devices/{deviceId}/configuration")
@RequiredArgsConstructor
public class DeviceConfigurationRestController {

    private final DeviceConfigurationRepository configurationRepository;
    private final DeviceRepository deviceRepository;
    private final AuditLogService auditLogService;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<?> getConfiguration(@PathVariable String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> configurationRepository.findByDeviceId(device.getId())
                        .map(DeviceConfigurationRestController::toView)
                        .orElseGet(() -> new ConfigurationView(null, deviceId, new BigDecimal("35.00"),
                                new BigDecimal("30.00"), 10, true, null)))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping
    @RequireRole("ADMIN")
    @Transactional
    public ResponseEntity<?> saveConfiguration(@PathVariable String deviceId,
                                               @RequestBody ConfigurationRequest request,
                                               @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
                                               HttpServletRequest httpRequest) {
        if (request.temperatureHigh() == null || request.temperatureHigh().compareTo(BigDecimal.ZERO) <= 0
                || request.temperatureHigh().compareTo(new BigDecimal("80")) > 0
                || request.soilMoistureLow() == null || request.soilMoistureLow().compareTo(BigDecimal.ZERO) < 0
                || request.soilMoistureLow().compareTo(new BigDecimal("100")) > 0
                || request.heartbeatInterval() == null || request.heartbeatInterval() < 1 || request.heartbeatInterval() > 3600
                || request.autoWatering() == null) {
            return ResponseEntity.badRequest().body(new ApiError("Giá trị cấu hình nằm ngoài phạm vi cho phép."));
        }
        return deviceRepository.findByDeviceId(deviceId)
                .map(device -> {
                    DeviceConfiguration configuration = configurationRepository.findByDeviceId(device.getId())
                            .orElseGet(() -> DeviceConfiguration.builder().device(device).build());
                    configuration.setTemperatureHigh(request.temperatureHigh());
                    configuration.setSoilMoistureLow(request.soilMoistureLow());
                    configuration.setHeartbeatInterval(request.heartbeatInterval());
                    configuration.setAutoWatering(request.autoWatering());
                    configuration.setUpdatedBy(actor);
                    DeviceConfiguration saved = configurationRepository.save(configuration);
                    auditLogService.record(actor, httpRequest, "DEVICE_CONFIG_UPDATE", "Device", deviceId,
                            Map.of("temperatureHigh", request.temperatureHigh(),
                                    "soilMoistureLow", request.soilMoistureLow(),
                                    "heartbeatInterval", request.heartbeatInterval(),
                                    "autoWatering", request.autoWatering()));
                    return ResponseEntity.ok(toView(saved));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private static ConfigurationView toView(DeviceConfiguration configuration) {
        return new ConfigurationView(configuration.getId(), configuration.getDevice().getDeviceId(),
                configuration.getTemperatureHigh(), configuration.getSoilMoistureLow(),
                configuration.getHeartbeatInterval(), configuration.getAutoWatering(), configuration.getUpdatedAt());
    }

    public record ConfigurationRequest(BigDecimal temperatureHigh, BigDecimal soilMoistureLow,
                                       Integer heartbeatInterval, Boolean autoWatering) {}
    public record ConfigurationView(Long id, String deviceId, BigDecimal temperatureHigh,
                                    BigDecimal soilMoistureLow, Integer heartbeatInterval,
                                    Boolean autoWatering, ZonedDateTime updatedAt) {}
    public record ApiError(String message) {}
}