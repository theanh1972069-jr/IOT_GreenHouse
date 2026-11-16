package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.model.DeviceLiveStatus;
import com.iot.greenhouse.service.DeviceLiveService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
public class DeviceControlRestController {

    private final DeviceLiveService deviceLiveService;

    @GetMapping("/{deviceId}/live")
    public ResponseEntity<DeviceLiveStatus> getLiveStatus(@PathVariable String deviceId) {
        return ResponseEntity.ok(deviceLiveService.getOrCreateLiveStatus(deviceId));
    }

    @GetMapping("/live")
    public ResponseEntity<List<DeviceLiveStatus>> getAllLiveStatuses() {
        return ResponseEntity.ok(deviceLiveService.getAllLiveStatuses());
    }

    @PostMapping("/{deviceId}/control")
    @RequireRole({"ADMIN", "OPERATOR"})
    public ResponseEntity<?> controlActuator(
            @PathVariable String deviceId,
            @RequestBody ControlRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        if (request == null) {
            return ResponseEntity.badRequest().body(new ApiError("Yêu cầu không hợp lệ."));
        }

        DeviceLiveStatus status = deviceLiveService.getOrCreateLiveStatus(deviceId);

        if (request.autoMode() != null) {
            status = deviceLiveService.setAutoMode(deviceId, request.autoMode(), actor, httpRequest);
        }

        if (request.actuator() != null && request.state() != null) {
            status = deviceLiveService.controlActuator(deviceId, request.actuator(), request.state(), actor, httpRequest);
        }

        return ResponseEntity.ok(status);
    }

    @PutMapping("/{deviceId}/thresholds")
    @RequireRole({"ADMIN", "OPERATOR"})
    public ResponseEntity<?> updateThresholds(
            @PathVariable String deviceId,
            @RequestBody ThresholdsRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        if (request == null) {
            return ResponseEntity.badRequest().body(new ApiError("Nội dung cấu hình ngưỡng không hợp lệ."));
        }

        if (request.temperatureHigh() != null &&
                (request.temperatureHigh().compareTo(BigDecimal.valueOf(10)) < 0 ||
                 request.temperatureHigh().compareTo(BigDecimal.valueOf(70)) > 0)) {
            return ResponseEntity.badRequest().body(new ApiError("Ngưỡng nhiệt độ phải từ 10°C đến 70°C."));
        }

        if (request.soilMoistureLow() != null &&
                (request.soilMoistureLow().compareTo(BigDecimal.ZERO) < 0 ||
                 request.soilMoistureLow().compareTo(BigDecimal.valueOf(100)) > 0)) {
            return ResponseEntity.badRequest().body(new ApiError("Ngưỡng độ ẩm đất phải từ 0% đến 100%."));
        }

        if (request.lightTempLow() != null &&
                (request.lightTempLow().compareTo(BigDecimal.valueOf(5)) < 0 ||
                 request.lightTempLow().compareTo(BigDecimal.valueOf(45)) > 0)) {
            return ResponseEntity.badRequest().body(new ApiError("Ngưỡng nhiệt độ bật đèn phải từ 5°C đến 45°C."));
        }

        DeviceLiveStatus updated = deviceLiveService.updateThresholds(
                deviceId,
                request.temperatureHigh(),
                request.soilMoistureLow(),
                request.lightTempLow(),
                request.autoMode(),
                actor,
                httpRequest
        );

        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{deviceId}/quick-action")
    @RequireRole({"ADMIN", "OPERATOR"})
    public ResponseEntity<?> quickAction(
            @PathVariable String deviceId,
            @RequestBody QuickActionRequest request,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {

        if (request == null || request.action() == null) {
            return ResponseEntity.badRequest().body(new ApiError("Hành động không hợp lệ."));
        }

        DeviceLiveStatus status;
        switch (request.action().toLowerCase()) {
            case "water_on":
                status = deviceLiveService.controlActuator(deviceId, "valve", true, actor, httpRequest);
                break;
            case "water_off":
                status = deviceLiveService.controlActuator(deviceId, "valve", false, actor, httpRequest);
                break;
            case "fan_on":
                status = deviceLiveService.controlActuator(deviceId, "fan", true, actor, httpRequest);
                break;
            case "fan_off":
                status = deviceLiveService.controlActuator(deviceId, "fan", false, actor, httpRequest);
                break;
            case "light_on":
                status = deviceLiveService.controlActuator(deviceId, "light", true, actor, httpRequest);
                break;
            case "light_off":
                status = deviceLiveService.controlActuator(deviceId, "light", false, actor, httpRequest);
                break;
            case "stop_all":
                status = deviceLiveService.controlActuator(deviceId, "all", false, actor, httpRequest);
                break;
            default:
                return ResponseEntity.badRequest().body(new ApiError("Hành động nhanh không được hỗ trợ: " + request.action()));
        }

        return ResponseEntity.ok(status);
    }

    public record ControlRequest(String actuator, Boolean state, Boolean autoMode) {}
    public record ThresholdsRequest(BigDecimal temperatureHigh, BigDecimal soilMoistureLow, BigDecimal lightTempLow, Boolean autoMode) {}
    public record QuickActionRequest(String action) {}
    public record ApiError(String message) {}
}
