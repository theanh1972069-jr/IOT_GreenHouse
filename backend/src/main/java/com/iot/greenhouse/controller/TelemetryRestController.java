package com.iot.greenhouse.controller;

import com.iot.greenhouse.config.AuthInterceptor;
import com.iot.greenhouse.config.RequireRole;
import com.iot.greenhouse.entity.Device;
import com.iot.greenhouse.entity.Telemetry;
import com.iot.greenhouse.entity.User;
import com.iot.greenhouse.repository.DeviceRepository;
import com.iot.greenhouse.service.ZoneAccessService;
import com.iot.greenhouse.service.TelemetryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/telemetry")
@RequiredArgsConstructor
public class TelemetryRestController {

    private final TelemetryService telemetryService;
    private final DeviceRepository deviceRepository;
    private final ZoneAccessService zoneAccessService;

    @GetMapping
    @RequireRole({"ADMIN", "OPERATOR", "VIEWER"})
    public ResponseEntity<?> getTelemetry(
            @RequestParam String deviceId,
            @RequestAttribute(AuthInterceptor.AUTHENTICATED_USER_ATTRIBUTE) User actor,
            HttpServletRequest httpRequest) {
        Device device = deviceRepository.findByDeviceId(deviceId).orElse(null);
        if (device == null) {
            return ResponseEntity.ok(List.of());
        }
        if (!zoneAccessService.canAccess(actor, device.getZone())) {
            zoneAccessService.auditDenied(actor, httpRequest, "Telemetry", deviceId, device.getZone());
            return ResponseEntity.status(403).body(new ApiError("Bạn không có quyền truy cập khu vực này."));
        }
        return ResponseEntity.ok(telemetryService.getTelemetryByDeviceId(deviceId));
    }

    public record ApiError(String message) {}
}
